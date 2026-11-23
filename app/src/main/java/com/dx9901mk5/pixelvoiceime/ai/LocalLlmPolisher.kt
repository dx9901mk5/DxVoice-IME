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
 * Optional flagship-only on-device LLM polisher powered by MediaPipe Tasks GenAI (Gemma 3 1B INT4).
 * Uses tasks-genai 0.10.35+, low-temperature single-turn session, strict few-shot formatting,
 * anti-chatter / repetition sanity guardrails, and a native-crash circuit breaker.
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
        polisherHelper: GeminiPolisher,
        referenceHints: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = rawText.trim()
        if (clean.isBlank()) return@withContext Result.success("")
        if (isBlockedByCrashGuard()) {
            return@withContext Result.failure(IllegalStateException("此裝置先前載入本地模型異常，已自動安全略過"))
        }

        val engine = ensureLoaded()
            ?: return@withContext Result.failure(IllegalStateException("尚未下載或無法初始化本地 Gemma 3 1B 模型"))

        val preferTraditional = !languageTag.startsWith("zh-CN", ignoreCase = true)
        val preInput = if (preferTraditional) {
            LocalTextPolisher.toTraditionalTaiwan(clean, applyTaiwanTypoFix = true)
        } else {
            clean
        }

        val prompt = buildString {
            append("<start_of_turn>user\n")
            if (preferTraditional) {
                appendLine("你是語音錯字標點修正器。只輸出修正後的繁體中文句子本身，嚴禁任何問候、解釋、引號或重複。")
                if (removeFillers) {
                    appendLine("去除口語贅詞，句中停頓用「，」，句尾用「。」或「？」。")
                }
                appendLine("輸入：嗯今天天氣因該不錯我們去吃午飯吧")
                appendLine("輸出：今天天氣應該不錯，我們去吃午飯吧。")
                appendLine("輸入：簡單測試幫忙看看我的話有沒有錯")
                appendLine("輸出：簡單測試，幫忙看看我的話有沒有錯。")
                if (referenceHints.isNotBlank()) {
                    appendLine("參考詞彙（僅供拼寫參考）：${referenceHints.take(300)}")
                }
            } else {
                appendLine("你是语音错字标点修正器。只输出修正后的句子本身，严禁任何问候、解释、引号或重复。")
                appendLine("输入：嗯今天天气应该不错我们去吃午饭吧")
                appendLine("输出：今天天气应该不错，我们去吃午饭吧。")
            }
            append("輸入：")
            appendLine(preInput)
            append("輸出：<end_of_turn>\n<start_of_turn>model\n")
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

    /**
     * Strips model chatter / prefixes / quotes and rejects hallucinated or looping responses
     * so the IME seamlessly falls back to ultra-clean local punctuation + Traditional Chinese output.
     */
    private fun sanitizeAndValidateOutput(rawOut: String, originalInput: String): String? {
        if (rawOut.isBlank()) return null

        val firstTurn = rawOut
            .substringBefore("<end_of_turn>")
            .substringBefore("<start_of_turn>")
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

        // Strip wrapping quotes if the model enclosed the sentence
        val quotePairs = listOf("「" to "」", "『" to "』", "“" to "”", "\"" to "\"")
        for ((open, close) in quotePairs) {
            if (text.startsWith(open) && text.endsWith(close) && text.length > open.length + close.length) {
                text = text.substring(open.length, text.length - close.length).trim()
            }
        }

        if (text.isBlank()) return null

        // Reject conversational assistant chatter that wasn't in the user's speech
        for (marker in CHATTER_MARKERS) {
            if (text.contains(marker) && !originalInput.contains(marker)) {
                return null
            }
        }

        // Reject runaway / bloated outputs (e.g. looping repetitions)
        val maxAllowedLen = (originalInput.length * 1.6f + 12).toInt().coerceAtLeast(24)
        if (text.length > maxAllowedLen) {
            return null
        }

        // Reject obvious repetition loops inside the single line
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
                .setMaxTokens(256)
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
