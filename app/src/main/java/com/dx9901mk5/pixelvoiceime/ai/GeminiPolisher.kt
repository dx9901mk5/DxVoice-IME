package com.dx9901mk5.pixelvoiceime.ai

import android.util.Base64
import com.dx9901mk5.pixelvoiceime.data.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface GeminiResult<out T> {
    data class Success<T>(val data: T) : GeminiResult<T>
    data class Error(val message: String, val code: Int? = null) : GeminiResult<Nothing>
}

/**
 * Gemini REST client dedicated to voice-typing correction, punctuation restoration,
 * and optional multimodal audio transcription + polish.
 */
class GeminiPolisher(
    private val keyStore: ApiKeyStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
) {
    suspend fun testConnection(): GeminiResult<String> {
        return generateText(
            prompt = "Reply with only: DxVoice-IME OK",
            maxOutputTokens = 512
        )
    }

    suspend fun polishTranscript(
        rawText: String,
        languageTag: String,
        beforeCursor: String = "",
        afterCursor: String = "",
        autoPunctuation: Boolean = true,
        removeFillers: Boolean = true
    ): GeminiResult<String> {
        val cleanInput = rawText.trim()
        if (cleanInput.isBlank()) return GeminiResult.Success("")

        val prompt = buildString {
            appendLine("你是高精度語音輸入法後處理引擎（類似 Google Pixel Assistant Voice Typing）。")
            appendLine("請修正以下語音辨識草稿並直接輸出最終可貼入輸入框的文字：")
            appendLine("- 目標語系：$languageTag（若為 zh-TW 請保持繁體中文台灣用語；若為中英夾雜請保留正確英文拼寫與大小寫）。")
            appendLine("- 修正同音異字、漏字、誤判專有名詞與語病，但不可改變原意、不可憑空捏造內容。")
            if (autoPunctuation) {
                appendLine("- 斷句與逗號規則（極重要）：只要說話者有短暫停頓、換氣、語意轉折或子句切換處，都【必須】加上全形逗號「，」；整段發話只在最後結束時加上一個句號「。」（或依語氣用「？」「！」），中間絕對不可出現句號「。」或分號「；」。英文使用半形標點。")
            } else {
                appendLine("- 盡量維持原斷句，不要強行加入多餘標點。")
            }
            if (removeFillers) {
                appendLine("- 去除無意義的口語贅詞與結巴重複（如：嗯、啊、那個、呃、就是說）。")
            }
            appendLine("- 嚴格規則：只輸出修正後的最終文字本身，不要加任何解釋、引號、前綴或 Markdown。")
            if (beforeCursor.isNotBlank() || afterCursor.isNotBlank()) {
                appendLine()
                appendLine("【輸入框上下文參考（僅供語意銜接，請勿重複輸出上下文）】")
                if (beforeCursor.isNotBlank()) appendLine("游標前文：${beforeCursor.takeLast(60)}")
                if (afterCursor.isNotBlank()) appendLine("游標後文：${afterCursor.take(40)}")
            }
            appendLine()
            appendLine("【語音辨識草稿】")
            append(cleanInput)
        }

        val result = generateText(prompt = prompt, maxOutputTokens = 512)
        return if (autoPunctuation && result is GeminiResult.Success) {
            GeminiResult.Success(normalizeClausePunctuation(result.data, languageTag))
        } else {
            result
        }
    }

    suspend fun transcribeAndPolishAudio(
        wavBytes: ByteArray,
        languageTag: String,
        draftText: String = "",
        beforeCursor: String = "",
        autoPunctuation: Boolean = true,
        removeFillers: Boolean = true
    ): GeminiResult<String> {
        val prompt = buildString {
            appendLine("你是高精度語音輸入法引擎。請直接聆聽這段音訊，精準轉錄並修潤為最終要輸入的文字。")
            appendLine("- 主要語系：$languageTag（若為 zh-TW 務必輸出繁體中文台灣用語）。")
            if (autoPunctuation) {
                appendLine("- 聆聽音訊停頓與標點規則（極重要）：凡聽到說話者有短暫停頓、換氣或子句切換且尚未結束整段發話時，務必在停頓處加上全形逗號「，」；直到整段音訊完全結束，才在最後加上一個句號「。」（或依語氣用「？」「！」），中間絕對不可出現句號「。」或分號「；」。英文使用半形標點。")
            }
            if (removeFillers) {
                appendLine("- 去除無意義口語贅詞（嗯、啊、呃、那個等）。")
            }
            if (draftText.isNotBlank()) {
                appendLine("- 離線初稿參考（可能有錯字，請以音訊為準）：$draftText")
            }
            if (beforeCursor.isNotBlank()) {
                appendLine("- 游標前文參考（請勿重複輸出）：${beforeCursor.takeLast(60)}")
            }
            appendLine("- 嚴格規則：只輸出最終文字本身，不要加任何說明、引號或時間戳記。若完全沒有人聲，請回覆空字串。")
        }

        val base64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", base64)
                )
            )
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 512)
                    .put("temperature", 0.1)
            )
        }
        val result = executeGenerateContent(payload, keyStore.selectedModel.value)
        return if (autoPunctuation && result is GeminiResult.Success) {
            GeminiResult.Success(normalizeClausePunctuation(result.data, languageTag))
        } else {
            result
        }
    }

    suspend fun translateText(
        text: String,
        targetLanguageName: String
    ): GeminiResult<String> {
        val clean = text.trim()
        if (clean.isBlank()) return GeminiResult.Success("")
        val prompt = buildString {
            appendLine("請將以下文字精準、自然地翻譯為「$targetLanguageName」。")
            appendLine("- 保留原意與適當標點符號。")
            appendLine("- 嚴格規則：只輸出翻譯後的最終文字本身，不要加任何解釋、引號或前綴。")
            appendLine()
            append(clean)
        }
        return generateText(prompt = prompt, maxOutputTokens = 512)
    }

    /**
     * Ensures CJK pauses/clauses use commas (，) and only the final ending uses a period (。).
     */
    fun normalizeClausePunctuation(text: String, languageTag: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val isCjk = languageTag.startsWith("zh", ignoreCase = true) ||
            languageTag.startsWith("ja", ignoreCase = true) ||
            languageTag.startsWith("yue", ignoreCase = true)
        if (!isCjk) return trimmed

        val lastIdx = trimmed.lastIndex
        val sb = StringBuilder(trimmed.length)
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (i < lastIdx && (ch == '。' || ch == '；')) {
                sb.append('，')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Local rule-based punctuation fallback when offline or when no API key is configured.
     */
    fun localFallbackPunctuate(rawText: String, languageTag: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return ""
        val isCjk = languageTag.startsWith("zh", ignoreCase = true) ||
            languageTag.startsWith("ja", ignoreCase = true) ||
            languageTag.startsWith("yue", ignoreCase = true)

        val withClauseCommas = if (isCjk) {
            val spaceBetweenCjk = Regex("""(?<=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}])\s+(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}])""")
            normalizeClausePunctuation(trimmed.replace(spaceBetweenCjk, "，"), languageTag)
        } else {
            trimmed
        }

        val lastChar = withClauseCommas.last()
        val alreadyPunctuated = lastChar in setOf('。', '？', '！', '，', '.', '?', '!', ',', '；', ';')
        if (alreadyPunctuated) return withClauseCommas

        return if (isCjk) {
            val isQuestion = withClauseCommas.endsWith("嗎") || withClauseCommas.endsWith("呢") ||
                withClauseCommas.contains("什麼") ||
                withClauseCommas.contains("怎麼") || withClauseCommas.contains("為什麼") ||
                withClauseCommas.contains("哪裡") || withClauseCommas.contains("是不是")
            withClauseCommas + if (isQuestion) "？" else "。"
        } else {
            val lower = withClauseCommas.lowercase()
            val isQuestion = lower.startsWith("what ") || lower.startsWith("how ") ||
                lower.startsWith("why ") || lower.startsWith("where ") ||
                lower.startsWith("when ") || lower.startsWith("who ") ||
                lower.startsWith("can ") || lower.startsWith("do ") ||
                lower.startsWith("is ") || lower.startsWith("are ")
            val capitalized = withClauseCommas.replaceFirstChar { it.uppercase() }
            capitalized + if (isQuestion) "?" else "."
        }
    }

    private suspend fun generateText(
        prompt: String,
        model: String = keyStore.selectedModel.value,
        maxOutputTokens: Int = 512
    ): GeminiResult<String> {
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", maxOutputTokens)
                    .put("temperature", 0.1)
            )
        }
        return executeGenerateContent(payload, model)
    }

    private suspend fun executeGenerateContent(
        payload: JSONObject,
        model: String
    ): GeminiResult<String> = withContext(Dispatchers.IO) {
        val apiKey = keyStore.getApiKey()
        if (apiKey.isBlank()) {
            return@withContext GeminiResult.Error("尚未設定 Gemini API Key")
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = parseErrorMessage(rawBody) ?: "HTTP ${response.code}"
                    return@withContext GeminiResult.Error(msg, response.code)
                }
                val text = parseCandidateText(rawBody)
                    ?: return@withContext GeminiResult.Error("Gemini 回傳空白內容")
                GeminiResult.Success(text.trim())
            }
        } catch (e: Exception) {
            GeminiResult.Error(e.localizedMessage ?: "網路連線失敗")
        }
    }

    private fun parseCandidateText(rawJson: String): String? {
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return null
            buildString {
                for (i in 0 until parts.length()) {
                    append(parts.getJSONObject(i).optString("text"))
                }
            }.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorMessage(rawJson: String): String? {
        return try {
            JSONObject(rawJson).optJSONObject("error")?.optString("message")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
