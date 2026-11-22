package com.dx9901mk5.pixelvoiceime.ai

import android.content.Context
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Optional flagship-only on-device LLM polisher powered by MediaPipe Tasks GenAI (Gemma 3 1B INT4).
 * Recommended hardware: Snapdragon 8 Gen 3 / 8 Elite, Dimensity 9300 / 9400+, Tensor G4+, 12GB+ RAM.
 */
class LocalLlmPolisher(
    private val context: Context,
    private val modelManager: OfflineModelManager
) {
    private val mutex = Mutex()
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    fun isModelDownloaded(): Boolean = modelManager.isLocalLlmReady()

    suspend fun preloadIfReady() = withContext(Dispatchers.IO) {
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

        val engine = ensureLoaded()
            ?: return@withContext Result.failure(IllegalStateException("尚未下載本地 Gemma 3 1B 模型"))

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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun ensureLoaded(): LlmInference? = mutex.withLock {
        val modelFile = modelManager.findLocalLlmModelFile() ?: return null
        if (llm != null && loadedPath == modelFile.absolutePath) {
            return llm
        }
        runCatching { llm?.close() }
        llm = null
        loadedPath = null

        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .build()
            val created = LlmInference.createFromOptions(context.applicationContext, options)
            llm = created
            loadedPath = modelFile.absolutePath
            created
        } catch (_: Exception) {
            null
        }
    }

    fun release() {
        runCatching { llm?.close() }
        llm = null
        loadedPath = null
    }
}
