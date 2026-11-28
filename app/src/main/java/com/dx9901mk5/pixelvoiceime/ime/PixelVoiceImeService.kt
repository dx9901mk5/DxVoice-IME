package com.dx9901mk5.pixelvoiceime.ime

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.ai.GeminiResult
import com.dx9901mk5.pixelvoiceime.ai.LocalTextPolisher
import com.dx9901mk5.pixelvoiceime.ai.LocalTranslator
import com.dx9901mk5.pixelvoiceime.ai.WavEncoder
import com.dx9901mk5.pixelvoiceime.asr.AndroidSpeechEngine
import com.dx9901mk5.pixelvoiceime.asr.LocalAudioCaptureEngine
import com.dx9901mk5.pixelvoiceime.asr.VoiceSessionState
import com.dx9901mk5.pixelvoiceime.data.PolishMode
import com.dx9901mk5.pixelvoiceime.models.PackDownloadState
import com.dx9901mk5.pixelvoiceime.models.VoiceLanguageCatalog
import com.dx9901mk5.pixelvoiceime.ui.ProvideUiLanguage
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
    private val systemNavBarOverlapDp = MutableStateFlow(0)
    private var inputComposeView: ComposeView? = null
    private var activePolishJob: Job? = null
    private var autoReturnJob: Job? = null
    private var usingAudioCaptureEngine: Boolean = false
    private var usingLocalSherpa: Boolean = false
    private var usingCustomOpenAi: Boolean = false
    private var lastCommittedLength: Int = 0
    private var lastRawTranscript: String = ""
    private var lastCommittedPrefix: String = ""
    private var lastCommittedOutput: String = ""
    private var lastCommitTimestampMs: Long = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                stopAllListening()
                cancelPendingJobs()
                if (app.prefs.config.value.autoReturnToPreviousIme) {
                    val delayMs = app.prefs.config.value.autoReturnDelayMs.coerceAtLeast(300L)
                    autoReturnJob = serviceScope.launch {
                        delay(delayMs)
                        returnToPreviousInputMethod()
                    }
                } else {
                    sessionState.value = VoiceSessionState.Idle
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as PixelVoiceApp
        speechEngine = AndroidSpeechEngine(this)
        imeLifecycleOwner.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOffReceiver, filter)
        }
    }

    override fun onCreateInputView(): View {
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(imeLifecycleOwner)
            decor.setViewTreeViewModelStoreOwner(imeLifecycleOwner)
            decor.setViewTreeSavedStateRegistryOwner(imeLifecycleOwner)
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                updateSystemNavBarOverlap(view, insets)
                insets
            }
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                updateSystemNavBarOverlap(view, null)
            }
            setContent {
                val state by sessionState.collectAsState()
                val config by app.prefs.config.collectAsState()
                val packStates by app.modelManager.packStates.collectAsState()
                val hasApiKey by app.apiKeyStore.hasApiKey.collectAsState()
                val navBarOverlapDp by systemNavBarOverlapDp.collectAsState()

                val activeLang = VoiceLanguageCatalog.findByTag(config.activeLanguageTag)
                val offlineReady = packStates[activeLang.packId] is PackDownloadState.Installed

                ProvideUiLanguage(uiLanguage = config.uiLanguage) {
                    ImeKeyboardPanel(
                        state = state,
                        activeLanguage = activeLang,
                        isOfflinePackReady = offlineReady,
                        isGeminiReady = hasApiKey && config.geminiPolishEnabled,
                        targetTranslationCode = config.targetTranslationLang,
                        themeMode = config.appThemeMode,
                        previousImeLabel = config.previousImeLabel,
                        bottomLiftDp = config.imeBottomLiftDp,
                        systemNavBarDp = navBarOverlapDp,
                        onCycleLanguage = ::cycleActiveLanguage,
                        onCycleTranslation = ::cycleTargetTranslation,
                        onCycleBottomLift = ::cycleImeBottomLift,
                        onOpenSettings = ::openSettingsActivity,
                        onOpenOfflineLanguages = ::openOfflineLanguagesSettings,
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
        inputComposeView = composeView
        return composeView
    }

    private fun updateSystemNavBarOverlap(view: View, dispatchedInsets: WindowInsetsCompat?) {
        val dispatchedNavBottom = dispatchedInsets?.getInsets(
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
        )?.bottom ?: 0

        val overlapPx = if (dispatchedNavBottom > 0) {
            dispatchedNavBottom
        } else {
            val rootInsets = ViewCompat.getRootWindowInsets(view)
            val rootNavBottom = rootInsets?.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.navigationBars()
            )?.bottom ?: 0
            val decor = window?.window?.decorView
            if (rootNavBottom > 0 && decor != null && decor.height > 0 && view.height > 0) {
                val screenHeight = resources.displayMetrics.heightPixels
                val decorReachesBottom = Build.VERSION.SDK_INT >= 35 || decor.height >= screenHeight - (rootNavBottom / 2)
                if (decorReachesBottom) {
                    val decorLoc = IntArray(2)
                    val viewLoc = IntArray(2)
                    decor.getLocationInWindow(decorLoc)
                    view.getLocationInWindow(viewLoc)
                    val viewBottomInDecor = (viewLoc[1] - decorLoc[1]) + view.height
                    val gapBelowView = (decor.height - viewBottomInDecor).coerceAtLeast(0)
                    (rootNavBottom - gapBelowView).coerceAtLeast(0)
                } else {
                    0
                }
            } else if (Build.VERSION.SDK_INT >= 35 && rootNavBottom > 0) {
                rootNavBottom
            } else {
                0
            }
        }

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val overlapDp = kotlin.math.ceil(overlapPx / density).toInt().coerceIn(0, 96)
        if (systemNavBarOverlapDp.value != overlapDp) {
            systemNavBarOverlapDp.value = overlapDp
        }
    }

    private fun cycleImeBottomLift() {
        val presets = listOf(0, 16, 32, 52)
        val cur = app.prefs.config.value.imeBottomLiftDp
        val next = presets.firstOrNull { it > cur } ?: presets.first()
        app.prefs.update { it.copy(imeBottomLiftDp = next) }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        imeLifecycleOwner.onStart()
        inputComposeView?.let { ViewCompat.requestApplyInsets(it) }
        FloatingMicService.ensureStarted(this)
        FloatingMicService.notifyImeVisibility(this, true)
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

        val cfg = app.prefs.config.value
        val activeLangTag = cfg.activeLanguageTag
        val needLocalLlm = cfg.localLlmEnabled &&
            app.entitlementManager.hasProAccess() &&
            !(cfg.geminiPolishEnabled && app.apiKeyStore.hasApiKey.value) &&
            app.localLlmPolisher.isModelDownloaded() &&
            !app.localLlmPolisher.isBlockedByCrashGuard()

        serviceScope.launch {
            if (app.localSherpaAsrEngine.isReadyForLanguage(activeLangTag)) {
                runCatching { app.localSherpaAsrEngine.preload(activeLangTag) }
            }
            if (needLocalLlm) {
                runCatching { app.localLlmPolisher.preloadIfReady() }
            }
        }

        if (app.prefs.config.value.autoListenOnStart) {
            startListeningSession()
        } else {
            sessionState.value = VoiceSessionState.Idle
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        checkPendingUserCorrection()
        super.onFinishInputView(finishingInput)
        stopAllListening()
        cancelPendingJobs()
        currentInputConnection?.finishComposingText()
        FloatingMicService.notifyImeVisibility(this, false)
        imeLifecycleOwner.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        stopAllListening()
        cancelPendingJobs()
        serviceScope.cancel()
        inputComposeView = null
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
        val sherpaReady = app.localSherpaAsrEngine.isReadyForLanguage(activeLang.bcp47)
        val hasApiKey = app.apiKeyStore.hasApiKey.value

        val useCustomOpenAiMode = config.geminiPolishEnabled && config.polishMode == PolishMode.OPENAI_COMPATIBLE
        val useGeminiAudioMode = config.geminiPolishEnabled &&
            hasApiKey &&
            (config.polishMode == PolishMode.AUDIO_MULTIMODAL || (!sherpaReady && !speechEngine.isAvailable()))
        // Voiceprint lock captures raw PCM, so only enter PCM path when a PCM-capable transcriber is ready;
        // otherwise seamlessly use online SpeechRecognizer so users without offline packs or Gemini keys can still dictate.
        val canTranscribeCapturedPcm = useCustomOpenAiMode || sherpaReady || (config.geminiPolishEnabled && hasApiKey)
        val voiceprintLockActive = config.voiceprintLockEnabled &&
            app.entitlementManager.hasProAccess() &&
            app.voiceprintManager.isEnrolled() &&
            app.voiceprintManager.isModelReady() &&
            canTranscribeCapturedPcm
        // Prioritize Android's streaming SpeechRecognizer (same live word-by-word engine as Gboard)
        // whenever available and voiceprint lock is off; when voiceprint lock is on, capture raw PCM
        // so 3D-Speaker can verify the user's voiceprint before transcribing.
        val preferStreamingAsr = speechEngine.isAvailable() && !voiceprintLockActive
        val useSherpaLocalMode = !useCustomOpenAiMode && !useGeminiAudioMode && sherpaReady && !preferStreamingAsr
        val shouldUseAudioCapture = useCustomOpenAiMode || useGeminiAudioMode || useSherpaLocalMode || voiceprintLockActive

        if (!shouldUseAudioCapture && !speechEngine.isAvailable()) {
            sessionState.value = VoiceSessionState.Error(
                "此裝置未偵測到系統語音服務，請至「離線語系」下載 SenseVoice 本地包或改用雲端/自架端點"
            )
            return
        }

        usingAudioCaptureEngine = shouldUseAudioCapture
        usingLocalSherpa = useSherpaLocalMode
        usingCustomOpenAi = useCustomOpenAiMode

        if (shouldUseAudioCapture) {
            val engineLabel = when {
                useCustomOpenAiMode -> "自架 / OpenAI 端點"
                useSherpaLocalMode -> "本地極速 (Sherpa)"
                else -> "Audio + Gemini"
            }
            sessionState.value = VoiceSessionState.Listening(
                partialText = "",
                amplitude = 0.1f,
                speechDetected = false,
                engineLabel = engineLabel
            )
            audioCaptureEngine.start(
                scope = serviceScope,
                silenceTimeoutMs = config.silenceTimeoutMs,
                noSpeechTimeoutMs = config.noSpeechTimeoutMs,
                noiseFilterMode = config.noiseFilterMode,
                hardwareNoiseReduction = config.hardwareNoiseReduction,
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
                        if (voiceprintLockActive) {
                            activePolishJob = serviceScope.launch {
                                sessionState.value = VoiceSessionState.Polishing(
                                    rawText = "",
                                    statusText = "正在比對個人聲紋…"
                                )
                                val check = app.voiceprintManager.verifySpeaker(
                                    pcmBytes = pcmBytes,
                                    threshold = config.voiceprintThreshold
                                )
                                if (!check.matched) {
                                    val pct = (check.similarity * 100f).toInt().coerceIn(0, 100)
                                    handleNoSpeechAutoReturn("非本人聲音已過濾（相似度 $pct%）")
                                    return@launch
                                }
                                dispatchCapturedAudio(pcmBytes)
                            }
                        } else {
                            dispatchCapturedAudio(pcmBytes)
                        }
                    }

                    override fun onNoSpeechTimeout() {
                        handleNoSpeechAutoReturn("未偵測到語音")
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
                preferOffline = false,
                silenceTimeoutMs = config.silenceTimeoutMs,
                noiseFilterMode = config.noiseFilterMode,
                callbacks = object : AndroidSpeechEngine.Callbacks {
                    override fun onReady() = Unit

                    override fun onAmplitude(normalized: Float) {
                        val cur = sessionState.value
                        if (cur is VoiceSessionState.Listening) {
                            sessionState.value = cur.copy(amplitude = normalized)
                        }
                    }

                    override fun onPartial(text: String) {
                        val liveText = if (
                            !activeLang.bcp47.startsWith("zh-CN", ignoreCase = true) &&
                            (activeLang.bcp47.startsWith("zh", ignoreCase = true) ||
                                activeLang.bcp47.startsWith("yue", ignoreCase = true))
                        ) {
                            app.customDictionaryStore.applyReplacements(
                                LocalTextPolisher.toTraditionalTaiwan(
                                    text,
                                    applyTaiwanTypoFix = config.localTaiwanTypoFixEnabled
                                )
                            )
                        } else {
                            app.customDictionaryStore.applyReplacements(text)
                        }
                        val cur = sessionState.value
                        if (cur is VoiceSessionState.Listening) {
                            sessionState.value = cur.copy(
                                partialText = liveText,
                                speechDetected = true
                            )
                        }
                        currentInputConnection?.setComposingText(liveText, 1)
                    }

                    override fun onFinal(text: String) {
                        processTranscript(text)
                    }

                    override fun onError(code: Int, message: String) {
                        val shouldFallbackToSherpa =
                            code == android.speech.SpeechRecognizer.ERROR_NETWORK ||
                                code == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                                code == android.speech.SpeechRecognizer.ERROR_SERVER ||
                                code == android.speech.SpeechRecognizer.ERROR_CLIENT ||
                                code == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                code == 11 ||
                                code == 12 ||
                                code == 13
                        if (shouldFallbackToSherpa && sherpaReady) {
                            currentInputConnection?.setComposingText("", 1)
                            startSherpaOfflineFallback(config)
                            return
                        }
                        currentInputConnection?.finishComposingText()
                        if (code == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            code == android.speech.SpeechRecognizer.ERROR_NO_MATCH
                        ) {
                            handleNoSpeechAutoReturn(message)
                        } else {
                            sessionState.value = VoiceSessionState.Error(message)
                        }
                    }
                }
            )
        }
    }

    private fun dispatchCapturedAudio(pcmBytes: ByteArray) {
        when {
            usingCustomOpenAi -> processCapturedAudioWithCustomEndpoint(pcmBytes)
            usingLocalSherpa || app.localSherpaAsrEngine.isReadyForLanguage(app.prefs.config.value.activeLanguageTag) ->
                processCapturedAudioWithSherpa(pcmBytes)
            else -> processCapturedAudio(pcmBytes)
        }
    }

    private fun startSherpaOfflineFallback(config: com.dx9901mk5.pixelvoiceime.data.VoiceImeConfig) {
        usingAudioCaptureEngine = true
        usingLocalSherpa = true
        usingCustomOpenAi = false
        sessionState.value = VoiceSessionState.Listening(
            partialText = "",
            amplitude = 0.1f,
            speechDetected = false,
            engineLabel = "離線備援 (Sherpa)"
        )
        audioCaptureEngine.start(
            scope = serviceScope,
            silenceTimeoutMs = config.silenceTimeoutMs,
            noSpeechTimeoutMs = config.noSpeechTimeoutMs,
            noiseFilterMode = config.noiseFilterMode,
            hardwareNoiseReduction = config.hardwareNoiseReduction,
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
                    processCapturedAudioWithSherpa(pcmBytes)
                }

                override fun onNoSpeechTimeout() {
                    handleNoSpeechAutoReturn("未偵測到語音")
                }

                override fun onError(message: String) {
                    sessionState.value = VoiceSessionState.Error(message)
                }
            }
        )
    }

    private fun handleNoSpeechAutoReturn(reason: String) {
        val config = app.prefs.config.value
        if (config.autoReturnToPreviousIme) {
            val waitMs = config.autoReturnDelayMs.coerceAtLeast(600L)
            sessionState.value = VoiceSessionState.Error("$reason，即將自動切回原輸入法…")
            cancelAutoReturn()
            autoReturnJob = serviceScope.launch {
                delay(waitMs)
                returnToPreviousInputMethod()
            }
        } else {
            sessionState.value = VoiceSessionState.Idle
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
                val polished = when (result) {
                    is GeminiResult.Success -> result.data.ifBlank { cleanRaw }
                    is GeminiResult.Error -> {
                        LocalTextPolisher.polish(
                            rawText = cleanRaw,
                            languageTag = config.activeLanguageTag,
                            autoPunctuation = config.autoPunctuation,
                            removeFillers = config.removeFillerWords,
                            applyTaiwanTypoFix = config.localTaiwanTypoFixEnabled,
                            punctuationModel = app.localSherpaAsrEngine.getPunctuationModel(),
                            polisherHelper = app.geminiPolisher
                        )
                    }
                }
                val finalText = applyTranslationIfNeeded(polished, config.activeLanguageTag, config.targetTranslationLang, hasApiKey)
                commitAndMaybeReturn(
                    rawText = cleanRaw,
                    finalText = finalText,
                    wasAiPolished = result is GeminiResult.Success
                )
            } else {
                val localLlmOut = if (config.localLlmEnabled &&
                    app.entitlementManager.hasProAccess() &&
                    app.localLlmPolisher.isModelDownloaded()
                ) {
                    sessionState.value = VoiceSessionState.Polishing(
                        rawText = cleanRaw,
                        statusText = "本地 Qwen2.5 0.5B 極速修潤中…"
                    )
                    app.localLlmPolisher.polish(
                        rawText = cleanRaw,
                        languageTag = config.activeLanguageTag,
                        autoPunctuation = config.autoPunctuation,
                        removeFillers = config.removeFillerWords,
                        polisherHelper = app.geminiPolisher
                    ).getOrNull()
                } else null

                val polished = localLlmOut ?: LocalTextPolisher.polish(
                    rawText = cleanRaw,
                    languageTag = config.activeLanguageTag,
                    autoPunctuation = config.autoPunctuation,
                    removeFillers = config.removeFillerWords,
                    applyTaiwanTypoFix = config.localTaiwanTypoFixEnabled,
                    punctuationModel = app.localSherpaAsrEngine.getPunctuationModel(),
                    polisherHelper = app.geminiPolisher
                )
                val finalText = applyTranslationIfNeeded(polished, config.activeLanguageTag, config.targetTranslationLang, hasApiKey)
                commitAndMaybeReturn(
                    rawText = cleanRaw,
                    finalText = finalText,
                    wasAiPolished = localLlmOut != null
                )
            }
        }
    }

    private fun processCapturedAudioWithCustomEndpoint(pcmBytes: ByteArray) {
        val config = app.prefs.config.value
        val hasGeminiKey = app.apiKeyStore.hasApiKey.value

        activePolishJob = serviceScope.launch {
            sessionState.value = VoiceSessionState.Polishing(
                rawText = "",
                statusText = "正在送交自架 / OpenAI 語音端點辨識…"
            )
            val wav = WavEncoder.pcm16ToWav(pcmBytes)
            val whisperPrompt = app.customDictionaryStore.buildWhisperPrompt()
            val llmHints = app.customDictionaryStore.buildLlmReferenceHints()
            when (
                val asrRes = app.openAiClient.transcribeAudio(
                    wavBytes = wav,
                    endpoint = config.customAsrEndpoint,
                    model = config.customAsrModel,
                    apiKey = config.customAsrApiKey,
                    languageTag = config.activeLanguageTag,
                    promptHint = whisperPrompt
                )
            ) {
                is GeminiResult.Success -> {
                    val rawText = asrRes.data.trim()
                    if (rawText.isBlank()) {
                        sessionState.value = VoiceSessionState.Idle
                        return@launch
                    }

                    val targetName = if (config.targetTranslationLang.isNotBlank()) {
                        LocalTranslator.findTarget(config.targetTranslationLang).fullName
                    } else ""

                    if (config.customLlmEnabled && config.customLlmEndpoint.isNotBlank()) {
                        sessionState.value = VoiceSessionState.Polishing(
                            rawText = rawText,
                            statusText = "正在透過自訂 LLM 端點修潤…"
                        )
                        val llmRes = app.openAiClient.polishOrTranslateText(
                            text = rawText,
                            endpoint = config.customLlmEndpoint,
                            model = config.customLlmModel,
                            apiKey = config.customLlmApiKey,
                            languageTag = config.activeLanguageTag,
                            autoPunctuation = config.autoPunctuation,
                            removeFillers = config.removeFillerWords,
                            targetTranslationName = targetName,
                            referenceHints = llmHints
                        )
                        if (llmRes is GeminiResult.Success) {
                            val normalized = if (config.targetTranslationLang.isBlank()) {
                                app.geminiPolisher.normalizeClausePunctuation(llmRes.data, config.activeLanguageTag)
                            } else {
                                llmRes.data
                            }
                            commitAndMaybeReturn(
                                rawText = rawText,
                                finalText = normalized.ifBlank { rawText },
                                wasAiPolished = true
                            )
                            return@launch
                        }
                    }

                    val localPolished = LocalTextPolisher.polish(
                        rawText = rawText,
                        languageTag = config.activeLanguageTag,
                        autoPunctuation = config.autoPunctuation,
                        removeFillers = config.removeFillerWords,
                        applyTaiwanTypoFix = config.localTaiwanTypoFixEnabled,
                        punctuationModel = app.localSherpaAsrEngine.getPunctuationModel(),
                        polisherHelper = app.geminiPolisher
                    )
                    val finalText = applyTranslationIfNeeded(
                        text = localPolished,
                        sourceTag = config.activeLanguageTag,
                        targetCode = config.targetTranslationLang,
                        hasApiKey = hasGeminiKey
                    )
                    commitAndMaybeReturn(
                        rawText = rawText,
                        finalText = finalText,
                        wasAiPolished = false
                    )
                }

                is GeminiResult.Error -> {
                    sessionState.value = VoiceSessionState.Error("端點辨識失敗：${asrRes.message}")
                }
            }
        }
    }

    private fun processCapturedAudioWithSherpa(pcmBytes: ByteArray) {
        val config = app.prefs.config.value
        val hasApiKey = app.apiKeyStore.hasApiKey.value

        activePolishJob = serviceScope.launch {
            sessionState.value = VoiceSessionState.Polishing(
                rawText = "",
                statusText = "本地極速辨識與標點中…"
            )

            val asrResult = app.localSherpaAsrEngine.transcribePcm16(pcmBytes, config.activeLanguageTag)
            if (asrResult.isFailure) {
                sessionState.value = VoiceSessionState.Error(
                    asrResult.exceptionOrNull()?.localizedMessage ?: "本地辨識失敗"
                )
                return@launch
            }

            val rawText = asrResult.getOrNull()?.trim().orEmpty()
            if (rawText.isBlank()) {
                sessionState.value = VoiceSessionState.Idle
                return@launch
            }

            var wasAiPolished = false
            var polished = if (config.geminiPolishEnabled && hasApiKey) {
                sessionState.value = VoiceSessionState.Polishing(
                    rawText = rawText,
                    statusText = "Gemini 修正語意與標點中…"
                )
                val beforeCursor = if (config.includeSurroundingContext) {
                    currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
                } else ""
                val afterCursor = if (config.includeSurroundingContext) {
                    currentInputConnection?.getTextAfterCursor(40, 0)?.toString().orEmpty()
                } else ""
                when (
                    val res = app.geminiPolisher.polishTranscript(
                        rawText = rawText,
                        languageTag = config.activeLanguageTag,
                        beforeCursor = beforeCursor,
                        afterCursor = afterCursor,
                        autoPunctuation = config.autoPunctuation,
                        removeFillers = config.removeFillerWords,
                        referenceHints = app.customDictionaryStore.buildLlmReferenceHints()
                    )
                ) {
                    is GeminiResult.Success -> {
                        wasAiPolished = true
                        res.data.ifBlank { rawText }
                    }
                    is GeminiResult.Error -> null
                }
            } else null

            if (polished == null &&
                config.localLlmEnabled &&
                app.entitlementManager.hasProAccess() &&
                app.localLlmPolisher.isModelDownloaded()
            ) {
                sessionState.value = VoiceSessionState.Polishing(
                    rawText = rawText,
                    statusText = "本地 Qwen2.5 0.5B 極速修潤中…"
                )
                val llmRes = app.localLlmPolisher.polish(
                    rawText = rawText,
                    languageTag = config.activeLanguageTag,
                    autoPunctuation = config.autoPunctuation,
                    removeFillers = config.removeFillerWords,
                    polisherHelper = app.geminiPolisher,
                    referenceHints = app.customDictionaryStore.buildLlmReferenceHints()
                )
                if (llmRes.isSuccess) {
                    wasAiPolished = true
                    polished = llmRes.getOrNull()
                }
            }

            if (polished == null) {
                polished = LocalTextPolisher.polish(
                    rawText = rawText,
                    languageTag = config.activeLanguageTag,
                    autoPunctuation = config.autoPunctuation,
                    removeFillers = config.removeFillerWords,
                    applyTaiwanTypoFix = config.localTaiwanTypoFixEnabled,
                    punctuationModel = app.localSherpaAsrEngine.getPunctuationModel(),
                    polisherHelper = app.geminiPolisher
                )
            }

            val finalText = applyTranslationIfNeeded(
                text = polished,
                sourceTag = config.activeLanguageTag,
                targetCode = config.targetTranslationLang,
                hasApiKey = hasApiKey
            )

            commitAndMaybeReturn(
                rawText = rawText,
                finalText = finalText,
                wasAiPolished = wasAiPolished
            )
        }
    }

    private suspend fun applyTranslationIfNeeded(
        text: String,
        sourceTag: String,
        targetCode: String,
        hasApiKey: Boolean
    ): String {
        if (targetCode.isBlank() || text.isBlank()) return text
        sessionState.value = VoiceSessionState.Polishing(
            rawText = text,
            statusText = "正在翻譯為 ${LocalTranslator.findTarget(targetCode).fullName}…"
        )
        val localRes = LocalTranslator.translateOnDevice(text, sourceTag, targetCode)
        if (localRes.isSuccess) {
            return localRes.getOrNull()?.ifBlank { text } ?: text
        }
        if (hasApiKey) {
            val geminiRes = app.geminiPolisher.translateText(
                text = text,
                targetLanguageName = LocalTranslator.findTarget(targetCode).fullName
            )
            if (geminiRes is GeminiResult.Success) {
                return geminiRes.data.ifBlank { text }
            }
        }
        return text
    }

    private fun processCapturedAudio(pcmBytes: ByteArray) {
        val config = app.prefs.config.value
        if (!app.apiKeyStore.hasApiKey.value) {
            val activeLang = VoiceLanguageCatalog.findByTag(config.activeLanguageTag)
            if (app.localSherpaAsrEngine.isReadyForLanguage(activeLang.bcp47)) {
                processCapturedAudioWithSherpa(pcmBytes)
                return
            }
            if (speechEngine.isAvailable()) {
                sessionState.value = VoiceSessionState.Error("尚未下載離線語言包，請點擊麥克風改用線上語音或點上方下載語言包")
                return
            }
            sessionState.value = VoiceSessionState.Error("尚未下載本地語音包且未設定 Gemini API Key，請點上方「下載語言包」")
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
                    removeFillers = config.removeFillerWords,
                    referenceHints = app.customDictionaryStore.buildLlmReferenceHints()
                )
            ) {
                is GeminiResult.Success -> {
                    val polished = result.data.trim()
                    if (polished.isBlank()) {
                        sessionState.value = VoiceSessionState.Idle
                    } else {
                        val finalText = applyTranslationIfNeeded(
                            text = polished,
                            sourceTag = config.activeLanguageTag,
                            targetCode = config.targetTranslationLang,
                            hasApiKey = true
                        )
                        commitAndMaybeReturn(
                            rawText = polished,
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
        checkPendingUserCorrection()
        val config = app.prefs.config.value
        val convertedText = if (
            config.targetTranslationLang.isBlank() &&
            !config.activeLanguageTag.startsWith("zh-CN", ignoreCase = true) &&
            (config.activeLanguageTag.startsWith("zh", ignoreCase = true) ||
                config.activeLanguageTag.startsWith("yue", ignoreCase = true))
        ) {
            LocalTextPolisher.toTraditionalTaiwan(finalText, applyTaiwanTypoFix = config.localTaiwanTypoFixEnabled)
        } else {
            finalText
        }
        val outputText = app.customDictionaryStore.applyReplacements(convertedText)

        lastCommittedPrefix = currentInputConnection?.getTextBeforeCursor(24, 0)?.toString().orEmpty()
        currentInputConnection?.commitText(outputText, 1)
        lastCommittedLength = outputText.length
        lastRawTranscript = rawText
        lastCommittedOutput = outputText
        lastCommitTimestampMs = System.currentTimeMillis()
        app.customDictionaryStore.recordCommittedText(outputText)

        val shouldAutoReturn = config.autoReturnToPreviousIme && app.entitlementManager.hasProAccess()
        sessionState.value = VoiceSessionState.Committed(
            rawText = rawText,
            finalText = outputText,
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

    private fun checkPendingUserCorrection() {
        val committed = lastCommittedOutput
        val ts = lastCommitTimestampMs
        if (committed.isBlank() || ts == 0L) return
        if (System.currentTimeMillis() - ts > 45_000L) {
            lastCommittedOutput = ""
            lastCommitTimestampMs = 0L
            return
        }
        val ic = currentInputConnection ?: return
        val beforeNow = ic.getTextBeforeCursor(committed.length + 48, 0)?.toString().orEmpty()
        if (beforeNow.isBlank()) return
        val editedSlice = if (lastCommittedPrefix.isNotEmpty()) {
            val idx = beforeNow.lastIndexOf(lastCommittedPrefix)
            if (idx >= 0) beforeNow.substring(idx + lastCommittedPrefix.length) else return
        } else {
            beforeNow
        }
        app.customDictionaryStore.learnFromUserCorrection(committed, editedSlice)
        lastCommittedOutput = ""
        lastCommitTimestampMs = 0L
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

    private fun cycleTargetTranslation() {
        val options = LocalTranslator.targets
        val current = app.prefs.config.value.targetTranslationLang
        val idx = options.indexOfFirst { it.code == current }.let { if (it < 0) 0 else it }
        val next = options[(idx + 1) % options.size]
        app.prefs.update { it.copy(targetTranslationLang = next.code) }
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

    private fun openOfflineLanguagesSettings() {
        stopAllListening()
        cancelPendingJobs()
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_INITIAL_TAB, 1)
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
