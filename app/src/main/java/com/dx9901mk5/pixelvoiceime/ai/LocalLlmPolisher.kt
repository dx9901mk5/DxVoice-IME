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
import kotlinx.coroutines.withTimeoutOrNull

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
            LlmInference.Backend.CPU -> "XNNPACK 極速 CPU"
            LlmInference.Backend.GPU -> "GPU 加速"
            else -> "XNNPACK 極速 CPU"
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

        // Only run Chinese local LLM on Chinese/Cantonese inputs; skip very short phrases (< 6 core chars)
        // where a 0.5B model is prone to hallucination or answering instead of correcting.
        val isChinese = languageTag.isBlank() ||
            languageTag.startsWith("zh", ignoreCase = true) ||
            languageTag.startsWith("yue", ignoreCase = true)
        if (!isChinese) {
            return@withContext Result.failure(IllegalStateException("非中文語系略過本地中文小模型"))
        }

        val preferTraditional = !languageTag.startsWith("zh-CN", ignoreCase = true)
        val preInput = if (preferTraditional) {
            LocalTextPolisher.toTraditionalTaiwan(clean, applyTaiwanTypoFix = true)
        } else {
            clean
        }
        val origCore = extractCoreChars(preInput)
        if (origCore.length < MIN_CORE_CHARS_FOR_LLM) {
            return@withContext Result.failure(IllegalStateException("短句改用 0ms 本地詞庫與標點直出"))
        }

        // Plain single-turn instruction without manual <|im_start|>/<|im_end|> tokens:
        // Qwen2.5-0.5B-Instruct .task bundles embed the ChatML prompt template in MediaPipe metadata;
        // double-wrapping ChatML tags breaks tokenization and causes garbled/weird characters.
        val prompt = buildString {
            if (preferTraditional) {
                appendLine("任務：僅修正下方「原文」的同音錯別字與標點符號。")
                appendLine("規則：1.必須保留原意與幾乎所有原字，不可回答問題、不可增刪內容、不可改寫。")
                appendLine("2.只輸出修正後的一行繁體中文，不可加引號、解釋、前綴或外文。")
                if (removeFillers) {
                    appendLine("3.可微調口語贅詞，句中用「，」，句尾用「。」或「？」。")
                }
                if (referenceHints.isNotBlank()) {
                    append("參考專有名詞：").appendLine(referenceHints.take(160))
                }
                append("原文：").appendLine(preInput)
                append("修正：")
            } else {
                appendLine("任务：仅修正下方「原文」的同音错别字与标点符号。")
                appendLine("规则：1.必须保留原意与几乎所有原字，不可回答问题、不可增删内容、不可改写。")
                appendLine("2.只输出修正后的一行简体中文，不可加引号、解释、前缀或外文。")
                if (referenceHints.isNotBlank()) {
                    append("参考专有名词：").appendLine(referenceHints.take(160))
                }
                append("原文：").appendLine(preInput)
                append("修正：")
            }
        }

        try {
            val rawOut = withTimeoutOrNull(4500L) {
                generateLowTempResponse(engine, prompt)
            } ?: return@withContext Result.failure(IllegalStateException("本地模型回應逾時，已自動改用 0ms 本地詞庫直出"))
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
                .setTemperature(0.05f)
                .setTopK(4)
                .setTopP(0.8f)
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
            .removePrefix("修正:")
            .removePrefix("修正後：")
            .removePrefix("修正后：")
            .removePrefix("結果：")
            .removePrefix("结果：")
            .trim()

        val quotePairs = listOf("「" to "」", "『" to "』", "“" to "”", "\"" to "\"")
        for ((open, close) in quotePairs) {
            if (text.startsWith(open) && text.endsWith(close) && text.length > open.length + close.length) {
                text = text.substring(open.length, text.length - close.length).trim()
            }
        }

        if (text.isBlank()) return null

        // Reject special tokens, markdown artifacts, or replacement/garbled characters
        if (text.contains("<|") || text.contains("|>") || text.contains('\uFFFD') ||
            text.contains("**") || text.contains("```") || text.contains("##")
        ) {
            return null
        }

        for (marker in CHATTER_MARKERS) {
            if (text.contains(marker) && !originalInput.contains(marker)) {
                return null
            }
        }

        // Reject unexpected foreign scripts (Korean Hangul, Japanese Kana, Cyrillic, Arabic, etc.)
        // or sudden English bursts that were NOT present in the original ASR input.
        if (hasUnexpectedAlienCharacters(text, originalInput)) {
            return null
        }

        val origCore = extractCoreChars(originalInput)
        val outCore = extractCoreChars(text)
        if (origCore.isBlank() || outCore.isBlank()) return null

        // Strict length guardrail: a typo/punctuation fixer must not drastically shrink or expand the text
        val minAllowedCore = (origCore.length * 0.78f).toInt().coerceAtLeast(3)
        val maxAllowedCore = (origCore.length * 1.18f + 2).toInt()
        if (outCore.length < minAllowedCore || outCore.length > maxAllowedCore) {
            return null
        }

        if (hasRepetitionLoop(text) && !hasRepetitionLoop(originalInput)) {
            return null
        }

        // Fidelity guardrail via LCS similarity: reject hallucinations, rewrites, or Q&A replies
        val maxLen = maxOf(origCore.length, outCore.length)
        val lcs = longestCommonSubsequenceLength(origCore, outCore)
        val similarity = lcs.toFloat() / maxLen.toFloat()
        if (similarity < MIN_LCS_FIDELITY) {
            return null
        }

        return text
    }

    private fun extractCoreChars(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (!ch.isWhitespace() && ch !in PUNCTUATION_CHARS) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    private fun hasUnexpectedAlienCharacters(output: String, original: String): Boolean {
        val origHasLatin = original.any { it in 'a'..'z' || it in 'A'..'Z' }
        var extraLatinCount = 0
        for (ch in output) {
            if (ch in original) continue
            val code = ch.code
            // Control chars or Unicode replacement / private use
            if (Character.isISOControl(ch) || code == 0xFFFD || code in 0xE000..0xF8FF) {
                return true
            }
            // Korean Hangul / Jamo
            if (code in 0xAC00..0xD7AF || code in 0x1100..0x11FF || code in 0x3130..0x318F) {
                return true
            }
            // Japanese Hiragana / Katakana
            if (code in 0x3040..0x30FF || code in 0x31F0..0x31FF) {
                return true
            }
            // Cyrillic / Arabic / Thai / Devanagari / Greek
            if (code in 0x0370..0x06FF || code in 0x0900..0x0E7F) {
                return true
            }
            // Unexpected Latin burst when original had no English
            if (!origHasLatin && (ch in 'a'..'z' || ch in 'A'..'Z')) {
                extraLatinCount++
                if (extraLatinCount >= 3) return true
            }
        }
        return false
    }

    private fun longestCommonSubsequenceLength(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        // Cap DP memory for unusually long inputs
        val s1 = if (a.length > 240) a.substring(0, 240) else a
        val s2 = if (b.length > 240) b.substring(0, 240) else b
        var prev = IntArray(s2.length + 1)
        var curr = IntArray(s2.length + 1)
        for (i in 1..s1.length) {
            val c1 = s1[i - 1]
            for (j in 1..s2.length) {
                curr[j] = if (c1 == s2[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], curr[j - 1])
                }
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[s2.length]
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

        // Phase 1: Qwen2.5-0.5B multi-prefill-seq_q8_ekv1280 is optimized for XNNPACK CPU
        // (zero OpenCL shader compilation freeze and ~30 tok/s on flagship chips).
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

        // Phase 2: Fallback to GPU only if CPU delegate fails
        if (created == null && !shouldSkipGpu()) {
            guardPrefs.edit()
                .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, true)
                .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                .commit()

            val gpuInstance = tryCreateInference(modelFile.absolutePath, LlmInference.Backend.GPU)
            guardPrefs.edit()
                .putBoolean(KEY_GPU_LOADING_IN_PROGRESS, false)
                .putBoolean(KEY_GPU_FAILED, gpuInstance == null)
                .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                .apply()

            if (gpuInstance != null) {
                created = gpuInstance
                usedBackend = LlmInference.Backend.GPU
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
        activeBackend = null
    }

    companion object {
        private const val GUARD_PREFS_NAME = "pixelvoice_local_llm_guard"
        private const val KEY_GPU_LOADING_IN_PROGRESS = "gpu_loading_in_progress"
        private const val KEY_GPU_FAILED = "gpu_failed_fallback_cpu"
        private const val KEY_CPU_LOADING_IN_PROGRESS = "cpu_loading_in_progress"
        private const val KEY_VERSION_CODE = "version_code"
        private const val MIN_VALID_MODEL_BYTES = 50L * 1024L * 1024L
        private const val MIN_CORE_CHARS_FOR_LLM = 6
        private const val MIN_LCS_FIDELITY = 0.68f
        private const val PUNCTUATION_CHARS = "，。！？、；：「」『』（）(),.!?;:'\"—–-…·"

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
