package com.dx9901mk5.pixelvoiceime.ai

import android.content.Context
import android.content.SharedPreferences
import com.dx9901mk5.pixelvoiceime.BuildConfig
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fast on-device Chinese LLM polisher powered by MediaPipe Tasks GenAI (Qwen2.5-0.5B-Instruct Q8).
 * Features:
 * 1. GPU-first acceleration with automatic persistent fallback to CPU if GPU init crashes or fails.
 * 2. Compact Qwen ChatML prompt + low-temperature single-turn session for minimal latency.
 * 3. Anti-chatter / repetition sanity guardrails + native-crash circuit breaker.
 */
class LocalLlmPolisher(
    private val context: Context,
    private val modelManager: OfflineModelManager
) {
    private val mutex = Mutex()
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    @Volatile
    private var activeBackend: LlmInference.Backend? = null

    private val guardPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(GUARD_PREFS_NAME, Context.MODE_PRIVATE)

    fun isModelDownloaded(): Boolean {
        if (!modelManager.isLocalLlmReady()) return false
        val file = modelManager.findLocalLlmModelFile() ?: return false
        return file.exists() && file.length() >= MIN_VALID_MODEL_BYTES
    }

    fun activeBackendLabel(): String {
        return when (activeBackend) {
            LlmInference.Backend.GPU -> "GPU 加速"
            LlmInference.Backend.CPU -> "CPU 模式"
            else -> if (shouldSkipGpu()) "CPU 降級" else "GPU 優先"
        }
    }

    private fun shouldSkipGpu(): Boolean {
        val recordedVersion = guardPrefs.getInt(KEY_VERSION_CODE, -1)
        if (recordedVersion != BuildConfig.VERSION_CODE) {
            return false
        }
        val gpuFailed = guardPrefs.getBoolean(KEY_GPU_FAILED, false)
        val gpuCrashed = guardPrefs.getBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)
        if (gpuCrashed && !gpuFailed) {
            guardPrefs.edit()
                .putBoolean(KEY_GPU_FAILED, true)
                .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)
                .apply()
            return true
        }
        return gpuFailed
    }

    fun isBlockedByCrashGuard(): Boolean {
        val recordedVersion = guardPrefs.getInt(KEY_VERSION_CODE, -1)
        if (recordedVersion != BuildConfig.VERSION_CODE) {
            clearCrashGuard()
            return false
        }
        // If GPU crashed previously, we do NOT block local LLM — we automatically fall back to CPU.
        if (guardPrefs.getBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)) {
            guardPrefs.edit()
                .putBoolean(KEY_GPU_FAILED, true)
                .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)
                .apply()
            return false
        }
        return guardPrefs.getBoolean(KEY_CPU_LOADING_IN_PROGRESS, false)
    }

    fun clearCrashGuard() {
        guardPrefs.edit()
            .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)
            .putBoolean(KEY_GPU_FAILED, false)
            .putBoolean(KEY_CPU_LOADING_IN_PROGRESS, false)
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
        polisherHelper: GeminiPolisher,
        referenceHints: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = rawText.trim()
        if (clean.isBlank()) return@withContext Result.success("")
        if (isBlockedByCrashGuard()) {
            return@withContext Result.failure(IllegalStateException("此裝置先前載入本地模型異常，已自動安全略過"))
        }

        val engine = ensureLoaded()
            ?: return@withContext Result.failure(IllegalStateException("尚未下載或無法初始化本地 Qwen2.5 0.5B 模型"))

        val preferTraditional = !languageTag.startsWith("zh-CN", ignoreCase = true)
        val preInput = if (preferTraditional) {
            LocalTextPolisher.toTraditionalTaiwan(clean, applyTaiwanTypoFix = true)
        } else {
            clean
        }

        // Compact Qwen2.5 ChatML prompt for low prefill latency & strict direct output
        val prompt = buildString {
            append("<|im_start|>system\n")
            if (preferTraditional) {
                append("你是繁體中文語音錯字標點修正器。只輸出修正後的這一句話，禁止問候、解釋、引號或重複。")
                if (removeFillers) {
                    append("去除口語贅詞，句中用「，」，句尾用「。」或「？」。")
                }
                if (referenceHints.isNotBlank()) {
                    append("\n參考詞彙：").append(referenceHints.take(220))
                }
            } else {
                append("你是语音错字标点修正器。只输出修正后的这一句话，禁止问候、解释、引号或重复。")
            }
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            if (preferTraditional) {
                appendLine("嗯今天天氣因該不錯我們去吃午飯吧")
                append("<|im_end|>\n<|im_start|>assistant\n")
                appendLine("今天天氣應該不錯，我們去吃午飯吧。")
                append("<|im_end|>\n<|im_start|>user\n")
            }
            append(preInput)
            append("<|im_end|>\n<|im_start|>assistant\n")
        }

        try {
            val rawOut = generateLowTempResponse(engine, prompt)
            val sanitized = sanitizeAndValidateOutput(rawOut = rawOut, originalInput = preInput)
                ?: return@withContext Result.failure(IllegalStateException("本地模型輸出異常，已自動改用本地規則直出"))

            var finalOut = if (preferTraditional) {
                LocalTextPolisher.toTraditionalTaiwan(sanitized, applyTaiwanTypoFix = true)
            } else {
                sanitized
            }
            if (autoPunctuation) {
                finalOut = polisherHelper.normalizeClausePunctuation(finalOut, languageTag)
            }
            Result.success(finalOut.trim())
        } catch (t: Throwable) {
            Result.failure(RuntimeException(t.localizedMessage ?: "本地模型推論失敗", t))
        }
    }

    private fun generateLowTempResponse(engine: LlmInference, prompt: String): String {
        val sessionResult = runCatching {
            val sessionOpts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(0.1f)
                .setTopK(12)
                .build()
            LlmInferenceSession.createFromOptions(engine, sessionOpts).use { session ->
                session.addQueryChunk(prompt)
                session.generateResponse()
            }
        }
        return sessionResult.getOrElse {
            engine.generateResponse(prompt)
        }.trim()
    }

    private fun sanitizeAndValidateOutput(rawOut: String, originalInput: String): String? {
        if (rawOut.isBlank()) return null

        val firstTurn = rawOut
            .substringBefore("<|im_end|>")
            .substringBefore("<|endoftext|>")
            .substringBefore("<|im_start|>")
            .substringBefore("<end_of_turn>")
            .trim()

        val firstLine = firstTurn
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        var text = firstLine
            .removePrefix("輸出：")
            .removePrefix("输出：")
            .removePrefix("輸出:")
            .removePrefix("输出:")
            .removePrefix("修正：")
            .removePrefix("修正後：")
            .trim()

        val quotePairs = listOf("「" to "」", "『" to "』", "“" to "”", "\"" to "\"")
        for ((open, close) in quotePairs) {
            if (text.startsWith(open) && text.endsWith(close) && text.length > open.length + close.length) {
                text = text.substring(open.length, text.length - close.length).trim()
            }
        }

        if (text.isBlank()) return null

        for (marker in CHATTER_MARKERS) {
            if (text.contains(marker) && !originalInput.contains(marker)) {
                return null
            }
        }

        val maxAllowedLen = (originalInput.length * 1.6f + 12).toInt().coerceAtLeast(24)
        if (text.length > maxAllowedLen) {
            return null
        }

        if (hasRepetitionLoop(text) && !hasRepetitionLoop(originalInput)) {
            return null
        }

        return text
    }

    private fun hasRepetitionLoop(text: String): Boolean {
        val compact = text.replace(Regex("[\\s，。！？、,.!?\"“”「」]"), "")
        if (compact.length < 12) return false
        for (span in 4..minOf(14, compact.length / 2)) {
            for (i in 0..(compact.length - span * 2)) {
                val chunk = compact.substring(i, i + span)
                if (compact.indexOf(chunk, i + span) != -1) {
                    return true
                }
            }
        }
        return false
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
        activeBackend = null

        var created: LlmInference? = null
        var usedBackend: LlmInference.Backend? = null

        // Phase 1: Try GPU acceleration first (2~3x faster on flagship chips) with crash guard
        if (!shouldSkipGpu()) {
            guardPrefs.edit()
                .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, true)
                .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                .commit()

            val gpuInstance = tryCreateInference(modelFile.absolutePath, LlmInference.Backend.GPU)
            if (gpuInstance != null) {
                guardPrefs.edit()
                    .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)
                    .putBoolean(KEY_GPU_FAILED, false)
                    .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                    .apply()
                created = gpuInstance
                usedBackend = LlmInference.Backend.GPU
            } else {
                guardPrefs.edit()
                    .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)
                    .putBoolean(KEY_GPU_FAILED, true)
                    .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                    .apply()
            }
        }

        // Phase 2: Automatic fallback to CPU if GPU is unavailable or previously crashed
        if (created == null) {
            guardPrefs.edit()
                .putBoolean(KEY_CPU_LOADING_IN_PROGRESS, true)
                .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                .commit()

            val cpuInstance = tryCreateInference(modelFile.absolutePath, LlmInference.Backend.CPU)
            guardPrefs.edit()
                .putBoolean(KEY_CPU_LOADING_IN_PROGRESS, false)
                .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                .apply()

            if (cpuInstance != null) {
                created = cpuInstance
                usedBackend = LlmInference.Backend.CPU
            }
        }

        if (created != null) {
            llm = created
            loadedPath = modelFile.absolutePath
            activeBackend = usedBackend
        }
        return created
    }

    private fun tryCreateInference(modelPath: String, backend: LlmInference.Backend): LlmInference? {
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(192)
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
        activeBackend = null
    }

    companion object {
        private const val GUARD_PREFS_NAME = "pixelvoice_local_llm_guard"
        private const val KEY_GPU_LOADING_IN_PROGRESS = "gpu_loading_in_progress"
        private const val KEY_GPU_FAILED = "gpu_failed_fallback_cpu"
        private const val KEY_CPU_LOADING_IN_PROGRESS = "cpu_loading_in_progress"
        private const val KEY_VERSION_CODE = "version_code"
        private const val MIN_VALID_MODEL_BYTES = 50L * 1024L * 1024L

        private val CHATTER_MARKERS = listOf(
            "您好",
            "您說的是",
            "您说的是",
            "我認為",
            "我认为",
            "我覺得",
            "我觉得",
            "以下是",
            "好的，",
            "好的,",
            "修正後的",
            "修正后的",
            "這句話",
            "这句话",
            "原文："
        )
    }
}
