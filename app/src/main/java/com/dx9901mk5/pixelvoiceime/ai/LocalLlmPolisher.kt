package com.dx9901mk5.pixelvoiceime.ai

import android.content.Context
import android.content.SharedPreferences
import com.dx9901mk5.pixelvoiceime.BuildConfig
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Optional flagship-only on-device LLM polisher powered by MediaPipe Tasks GenAI (Gemma 3 1B INT4).
 * Uses tasks-genai 0.10.35+ (Gemma 3 compatible), GPU-to-CPU automatic fallback, and a persistent
 * native-crash circuit breaker so an incompatible device never enters an IME crash loop.
 */
class LocalLlmPolisher(
    private val context: Context,
    private val modelManager: OfflineModelManager
) {
    private val mutex = Mutex()
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    private val guardPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(GUARD_PREFS_NAME, Context.MODE_PRIVATE)

    fun isModelDownloaded(): Boolean {
        if (!modelManager.isLocalLlmReady()) return false
        val file = modelManager.findLocalLlmModelFile() ?: return false
        return file.exists() && file.length() >= MIN_VALID_MODEL_BYTES
    }

    /**
     * Returns true if a previous native crash occurred during load on this exact app version.
     * Automatically resets whenever the user installs a newer app version or manually re-enables the toggle.
     */
    fun isBlockedByCrashGuard(): Boolean {
        val crashedInProgress = guardPrefs.getBoolean(KEY_LOADING_IN_PROGRESS, false)
        val recordedVersion = guardPrefs.getInt(KEY_VERSION_CODE, -1)
        if (!crashedInProgress) return false
        if (recordedVersion != BuildConfig.VERSION_CODE) {
            clearCrashGuard()
            return false
        }
        return true
    }

    fun clearCrashGuard() {
        guardPrefs.edit()
            .putBoolean(KEY_LOADING_IN_PROGRESS, false)
            .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
    }

    suspend fun preloadIfReady() = withContext(Dispatchers.IO) {
        if (!isModelDownloaded() || isBlockedByCrashGuard()) return@withContext
        ensureLoaded()
    }

    suspend fun polish(
        rawText: String,
        languageTag: String,
        autoPunctuation: Boolean,
        removeFillers: Boolean,
        polisherHelper: GeminiPolisher
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = rawText.trim()
        if (clean.isBlank()) return@withContext Result.success("")
        if (isBlockedByCrashGuard()) {
            return@withContext Result.failure(IllegalStateException("此裝置先前載入本地模型異常，已自動安全略過"))
        }

        val engine = ensureLoaded()
            ?: return@withContext Result.failure(IllegalStateException("尚未下載或無法初始化本地 Gemma 3 1B 模型"))

        val prompt = buildString {
            append("<start_of_turn>user\n")
            appendLine("你是台灣繁體中文語音輸入法錯字與標點修潤助手。")
            appendLine("請修正以下語音辨識文字中的同音錯別字與語病，保持台灣在地用語習慣（絕對不可改成簡體或中國大陸用語）。")
            if (autoPunctuation) {
                appendLine("標點規則：句與句或停頓之間使用全形逗號「，」，僅在整段話最後結尾使用句號「。」（或「？」「！」）。")
            }
            if (removeFillers) {
                appendLine("去除無意義口語贅詞（嗯、啊、呃、那個等）。")
            }
            appendLine("只輸出修正後的最終文字本身，不要任何解釋或引號。")
            appendLine()
            append(clean)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }

        try {
            val rawOut = engine.generateResponse(prompt).trim()
            if (rawOut.isBlank()) {
                return@withContext Result.failure(IllegalStateException("本地模型回傳空白"))
            }
            var finalOut = LocalTextPolisher.toTraditionalTaiwan(rawOut, applyTaiwanTypoFix = true)
            if (autoPunctuation) {
                finalOut = polisherHelper.normalizeClausePunctuation(finalOut, languageTag)
            }
            Result.success(finalOut)
        } catch (t: Throwable) {
            Result.failure(RuntimeException(t.localizedMessage ?: "本地模型推論失敗", t))
        }
    }

    private suspend fun ensureLoaded(): LlmInference? = mutex.withLock {
        if (isBlockedByCrashGuard()) return null
        val modelFile = modelManager.findLocalLlmModelFile() ?: return null
        if (!modelFile.exists() || modelFile.length() < MIN_VALID_MODEL_BYTES) return null
        if (llm != null && loadedPath == modelFile.absolutePath) {
            return llm
        }
        runCatching { llm?.close() }
        llm = null
        loadedPath = null

        // Persist guard synchronously before invoking native JNI so any SIGABRT/SIGSEGV is detected next launch.
        guardPrefs.edit()
            .putBoolean(KEY_LOADING_IN_PROGRESS, true)
            .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
            .commit()

        val created = tryCreateInference(modelFile.absolutePath, LlmInference.Backend.CPU)
            ?: tryCreateInference(modelFile.absolutePath, LlmInference.Backend.GPU)

        guardPrefs.edit()
            .putBoolean(KEY_LOADING_IN_PROGRESS, false)
            .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()

        if (created != null) {
            llm = created
            loadedPath = modelFile.absolutePath
        }
        return created
    }

    private fun tryCreateInference(modelPath: String, backend: LlmInference.Backend): LlmInference? {
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(512)
                .setPreferredBackend(backend)
                .build()
            LlmInference.createFromOptions(context.applicationContext, options)
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        runCatching { llm?.close() }
        llm = null
        loadedPath = null
    }

    companion object {
        private const val GUARD_PREFS_NAME = "pixelvoice_local_llm_guard"
        private const val KEY_LOADING_IN_PROGRESS = "loading_in_progress"
        private const val KEY_VERSION_CODE = "version_code"
        private const val MIN_VALID_MODEL_BYTES = 50L * 1024L * 1024L
    }
}
