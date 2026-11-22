package com.dx9901mk5.pixelvoiceime.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.ai.GeminiResult
import com.dx9901mk5.pixelvoiceime.ai.WavEncoder
import com.dx9901mk5.pixelvoiceime.asr.AndroidSpeechEngine
import com.dx9901mk5.pixelvoiceime.asr.LocalAudioCaptureEngine
import com.dx9901mk5.pixelvoiceime.asr.VoiceSessionState
import com.dx9901mk5.pixelvoiceime.data.PolishMode
import com.dx9901mk5.pixelvoiceime.models.PackDownloadState
import com.dx9901mk5.pixelvoiceime.models.VoiceLanguageCatalog
import com.dx9901mk5.pixelvoiceime.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * PixelVoice IME Service:
 * 1. Automatically starts listening & voice activity detection upon switching into this IME.
 * 2. Streams partial text, then polishes with Gemini (auto-fix typos + auto-punctuation) and commits back.
 * 3. Remembers the previous IME and switches straight back when finished.
 */
class PixelVoiceImeService : InputMethodService() {

    private val imeLifecycleOwner = ImeLifecycleOwner()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var app: PixelVoiceApp
    private lateinit var speechEngine: AndroidSpeechEngine
    private val audioCaptureEngine = LocalAudioCaptureEngine()

    private val sessionState = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    private var activePolishJob: Job? = null
    private var autoReturnJob: Job? = null
    private var usingAudioCaptureEngine: Boolean = false
    private var lastCommittedLength: Int = 0
    private var lastRawTranscript: String = ""

    override fun onCreate() {
        super.onCreate()
        app = application as PixelVoiceApp
        speechEngine = AndroidSpeechEngine(this)
        imeLifecycleOwner.onCreate()
    }

    override fun onCreateInputView(): View {
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(imeLifecycleOwner)
            decor.setViewTreeViewModelStoreOwner(imeLifecycleOwner)
            decor.setViewTreeSavedStateRegistryOwner(imeLifecycleOwner)
        }

        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by sessionState.collectAsState()
                val config by app.prefs.config.collectAsState()
                val packStates by app.modelManager.packStates.collectAsState()
                val hasApiKey by app.apiKeyStore.hasApiKey.collectAsState()

                val activeLang = VoiceLanguageCatalog.findByTag(config.activeLanguageTag)
                val offlineReady = packStates[activeLang.packId] is PackDownloadState.Installed

                ImeKeyboardPanel(
                    state = state,
                    activeLanguage = activeLang,
                    isOfflinePackReady = offlineReady,
                    isGeminiReady = hasApiKey && config.geminiPolishEnabled,
                    previousImeLabel = config.previousImeLabel.ifBlank { "切回鍵盤" },
                    onCycleLanguage = ::cycleActiveLanguage,
                    onOpenSettings = ::openSettingsActivity,
                    onMicPrimaryAction = ::handleMicButtonClick,
                    onSwitchToPreviousIme = ::returnToPreviousInputMethod,
                    onUndoAiPolish = ::undoLastAiPolish,
                    onBackspace = ::performBackspace,
                    onEnter = ::performEnterOrAction,
                    onOpenPermissionSetup = ::openSettingsActivity
                )
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        imeLifecycleOwner.onStart()
        cancelPendingJobs()
        detectAndRememberOtherIme()

        if (isPasswordField(info)) {
            sessionState.value = VoiceSessionState.PasswordProtected
            return
        }

        if (!hasRecordAudioPermission()) {
            sessionState.value = VoiceSessionState.PermissionRequired
            return
        }

        if (app.prefs.config.value.autoListenOnStart) {
            startListeningSession()
        } else {
            sessionState.value = VoiceSessionState.Idle
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopAllListening()
        cancelPendingJobs()
        currentInputConnection?.finishComposingText()
        imeLifecycleOwner.onStop()
    }

    override fun onDestroy() {
        stopAllListening()
        cancelPendingJobs()
        serviceScope.cancel()
        imeLifecycleOwner.onDestroy()
        super.onDestroy()
    }

    private fun handleMicButtonClick() {
        cancelAutoReturn()
        when (sessionState.value) {
            is VoiceSessionState.Listening -> {
                // User manually signals end-of-speech -> finish and polish immediately
                if (usingAudioCaptureEngine) {
                    audioCaptureEngine.requestFinish()
                } else {
                    speechEngine.finishListening()
                }
            }

            is VoiceSessionState.PermissionRequired -> openSettingsActivity()
            is VoiceSessionState.PasswordProtected -> returnToPreviousInputMethod()
            else -> {
                if (!hasRecordAudioPermission()) {
                    sessionState.value = VoiceSessionState.PermissionRequired
                } else {
                    startListeningSession()
                }
            }
        }
    }

    private fun startListeningSession() {
        stopAllListening()
        cancelPendingJobs()
        lastCommittedLength = 0
        lastRawTranscript = ""

        val config = app.prefs.config.value
        val activeLang = VoiceLanguageCatalog.findByTag(config.activeLanguageTag)
        val offlineReady = app.modelManager.isLanguageOfflineReady(activeLang.bcp47)
        val hasApiKey = app.apiKeyStore.hasApiKey.value

        val shouldUseAudioCapture = hasApiKey &&
            (config.polishMode == PolishMode.AUDIO_MULTIMODAL || !speechEngine.isAvailable())

        if (!hasApiKey && !speechEngine.isAvailable()) {
            sessionState.value = VoiceSessionState.Error(
                "此裝置未偵測到系統/本機語音辨識服務，請開啟系統語音服務或設定 Gemini API Key"
            )
            return
        }

        usingAudioCaptureEngine = shouldUseAudioCapture

        if (shouldUseAudioCapture) {
            sessionState.value = VoiceSessionState.Listening(
                partialText = "",
                amplitude = 0.1f,
                speechDetected = false,
                engineLabel = "Audio + Gemini"
            )
            audioCaptureEngine.start(
                scope = serviceScope,
                silenceTimeoutMs = config.silenceTimeoutMs,
                noSpeechTimeoutMs = config.noSpeechTimeoutMs,
                callbacks = object : LocalAudioCaptureEngine.Callbacks {
                    override fun onAmplitude(normalized: Float, speechDetected: Boolean) {
                        val cur = sessionState.value
                        if (cur is VoiceSessionState.Listening) {
                            sessionState.value = cur.copy(
                                amplitude = normalized,
                                speechDetected = cur.speechDetected || speechDetected
                            )
                        }
                    }

                    override fun onCaptured(pcmBytes: ByteArray) {
                        processCapturedAudio(pcmBytes)
                    }

                    override fun onNoSpeechTimeout() {
                        sessionState.value = VoiceSessionState.Idle
                    }

                    override fun onError(message: String) {
                        sessionState.value = VoiceSessionState.Error(message)
                    }
                }
            )
        } else {
            sessionState.value = VoiceSessionState.Listening(
                partialText = "",
                amplitude = 0.1f,
                speechDetected = false,
                engineLabel = if (offlineReady) "Offline ASR" else "System ASR"
            )
            speechEngine.start(
                languageTag = activeLang.bcp47,
                preferOffline = offlineReady,
                silenceTimeoutMs = config.silenceTimeoutMs,
                callbacks = object : AndroidSpeechEngine.Callbacks {
                    override fun onReady() = Unit

                    override fun onAmplitude(normalized: Float) {
                        val cur = sessionState.value
                        if (cur is VoiceSessionState.Listening) {
                            sessionState.value = cur.copy(amplitude = normalized)
                        }
                    }

                    override fun onPartial(text: String) {
                        val cur = sessionState.value
                        if (cur is VoiceSessionState.Listening) {
                            sessionState.value = cur.copy(
                                partialText = text,
                                speechDetected = true
                            )
                        }
                        currentInputConnection?.setComposingText(text, 1)
                    }

                    override fun onFinal(text: String) {
                        processTranscript(text)
                    }

                    override fun onError(code: Int, message: String) {
                        currentInputConnection?.finishComposingText()
                        sessionState.value = VoiceSessionState.Error(message)
                    }
                }
            )
        }
    }

    private fun processTranscript(rawText: String) {
        val cleanRaw = rawText.trim()
        if (cleanRaw.isBlank()) {
            sessionState.value = VoiceSessionState.Idle
            return
        }

        val config = app.prefs.config.value
        val hasApiKey = app.apiKeyStore.hasApiKey.value

        activePolishJob = serviceScope.launch {
            if (config.geminiPolishEnabled && hasApiKey) {
                sessionState.value = VoiceSessionState.Polishing(
                    rawText = cleanRaw,
                    statusText = "Gemini 修正語意與標點中…"
                )
                val beforeCursor = if (config.includeSurroundingContext) {
                    currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
                } else ""
                val afterCursor = if (config.includeSurroundingContext) {
                    currentInputConnection?.getTextAfterCursor(40, 0)?.toString().orEmpty()
                } else ""

                val result = app.geminiPolisher.polishTranscript(
                    rawText = cleanRaw,
                    languageTag = config.activeLanguageTag,
                    beforeCursor = beforeCursor,
                    afterCursor = afterCursor,
                    autoPunctuation = config.autoPunctuation,
                    removeFillers = config.removeFillerWords
                )
                val finalText = when (result) {
                    is GeminiResult.Success -> result.data.ifBlank { cleanRaw }
                    is GeminiResult.Error -> {
                        if (config.autoPunctuation) {
                            app.geminiPolisher.localFallbackPunctuate(cleanRaw, config.activeLanguageTag)
                        } else cleanRaw
                    }
                }
                commitAndMaybeReturn(
                    rawText = cleanRaw,
                    finalText = finalText,
                    wasAiPolished = result is GeminiResult.Success
                )
            } else {
                val finalText = if (config.autoPunctuation) {
                    app.geminiPolisher.localFallbackPunctuate(cleanRaw, config.activeLanguageTag)
                } else cleanRaw
                commitAndMaybeReturn(
                    rawText = cleanRaw,
                    finalText = finalText,
                    wasAiPolished = false
                )
            }
        }
    }

    private fun processCapturedAudio(pcmBytes: ByteArray) {
        val config = app.prefs.config.value
        if (!app.apiKeyStore.hasApiKey.value) {
            sessionState.value = VoiceSessionState.Error("音訊精修模式需要先設定 Gemini API Key")
            return
        }

        activePolishJob = serviceScope.launch {
            sessionState.value = VoiceSessionState.Polishing(
                rawText = "",
                statusText = "Gemini 聆聽音訊並自動標點中…"
            )
            val wav = WavEncoder.pcm16ToWav(pcmBytes)
            val beforeCursor = if (config.includeSurroundingContext) {
                currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
            } else ""

            when (
                val result = app.geminiPolisher.transcribeAndPolishAudio(
                    wavBytes = wav,
                    languageTag = config.activeLanguageTag,
                    beforeCursor = beforeCursor,
                    autoPunctuation = config.autoPunctuation,
                    removeFillers = config.removeFillerWords
                )
            ) {
                is GeminiResult.Success -> {
                    val finalText = result.data.trim()
                    if (finalText.isBlank()) {
                        sessionState.value = VoiceSessionState.Idle
                    } else {
                        commitAndMaybeReturn(
                            rawText = finalText,
                            finalText = finalText,
                            wasAiPolished = true
                        )
                    }
                }

                is GeminiResult.Error -> {
                    sessionState.value = VoiceSessionState.Error("Gemini 處理失敗：${result.message}")
                }
            }
        }
    }

    private fun commitAndMaybeReturn(
        rawText: String,
        finalText: String,
        wasAiPolished: Boolean
    ) {
        currentInputConnection?.commitText(finalText, 1)
        lastCommittedLength = finalText.length
        lastRawTranscript = rawText

        val config = app.prefs.config.value
        val shouldAutoReturn = config.autoReturnToPreviousIme
        sessionState.value = VoiceSessionState.Committed(
            rawText = rawText,
            finalText = finalText,
            wasAiPolished = wasAiPolished,
            returningSoon = shouldAutoReturn
        )

        if (shouldAutoReturn) {
            autoReturnJob = serviceScope.launch {
                if (config.autoReturnDelayMs > 0L) {
                    delay(config.autoReturnDelayMs)
                }
                returnToPreviousInputMethod()
            }
        }
    }

    private fun undoLastAiPolish() {
        cancelAutoReturn()
        if (lastCommittedLength > 0 && lastRawTranscript.isNotBlank()) {
            currentInputConnection?.deleteSurroundingText(lastCommittedLength, 0)
            currentInputConnection?.commitText(lastRawTranscript, 1)
            lastCommittedLength = lastRawTranscript.length
            sessionState.value = VoiceSessionState.Committed(
                rawText = lastRawTranscript,
                finalText = lastRawTranscript,
                wasAiPolished = false,
                returningSoon = false
            )
        }
    }

    /**
     * Switches immediately back to the previous input method remembered by Android,
     * with safe fallbacks for OEM ROMs.
     */
    private fun returnToPreviousInputMethod() {
        stopAllListening()
        cancelPendingJobs()
        currentInputConnection?.finishComposingText()

        val switched = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token = window?.window?.attributes?.token
                if (token != null) imm.switchToLastInputMethod(token) else false
            }
        } catch (_: Exception) {
            false
        }

        if (!switched) {
            val nextOk = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    switchToNextInputMethod(false)
                } else false
            } catch (_: Exception) {
                false
            }
            if (!nextOk) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
    }

    private fun detectAndRememberOtherIme() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList
            val other = enabled.firstOrNull { it.packageName != packageName }
            if (other != null && app.prefs.config.value.previousImeLabel.isBlank()) {
                val label = other.loadLabel(packageManager).toString()
                app.prefs.rememberPreviousIme(other.id, label)
            }
        } catch (_: Exception) {
        }
    }

    private fun cycleActiveLanguage() {
        val enabled = app.modelManager.enabledLanguages.value.toList()
        if (enabled.size <= 1) return
        val current = app.prefs.config.value.activeLanguageTag
        val idx = enabled.indexOf(current)
        val next = enabled[(idx + 1) % enabled.size]
        app.prefs.update { it.copy(activeLanguageTag = next) }
        if (sessionState.value is VoiceSessionState.Listening) {
            startListeningSession()
        }
    }

    private fun performBackspace() {
        cancelAutoReturn()
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun performEnterOrAction() {
        cancelAutoReturn()
        val ic = currentInputConnection ?: return
        val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun openSettingsActivity() {
        stopAllListening()
        cancelPendingJobs()
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun stopAllListening() {
        speechEngine.stop()
        audioCaptureEngine.stop()
    }

    private fun cancelAutoReturn() {
        autoReturnJob?.cancel()
        autoReturnJob = null
    }

    private fun cancelPendingJobs() {
        activePolishJob?.cancel()
        activePolishJob = null
        cancelAutoReturn()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val cls = inputType and InputType.TYPE_MASK_CLASS
        return (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}
