package com.dx9901mk5.pixelvoiceime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible ASR (/audio/transcriptions) and LLM (/chat/completions) client.
 * Supports self-hosted Mac/LAN Whisper/Ollama servers as well as Groq, OpenAI, and DeepSeek.
 */
class OpenAiCompatibleClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) {

    suspend fun transcribeAudio(
        wavBytes: ByteArray,
        endpoint: String,
        model: String,
        apiKey: String,
        languageTag: String
    ): GeminiResult<String> = withContext(Dispatchers.IO) {
        val url = buildUrl(endpoint, "/audio/transcriptions")
        val isoLang = languageTag.substringBefore('-').lowercase().let {
            if (it == "yue") "zh" else it
        }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", model.trim().ifBlank { "whisper-1" })
            .addFormDataPart("language", isoLang)
            .addFormDataPart("response_format", "json")

        val reqBuilder = Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = parseOpenAiError(raw) ?: "HTTP ${resp.code}"
                    return@withContext GeminiResult.Error(errMsg, resp.code)
                }
                val text = runCatching { JSONObject(raw).optString("text") }.getOrNull()?.trim()
                    ?: raw.trim()
                if (text.isBlank()) {
                    GeminiResult.Success("")
                } else {
                    GeminiResult.Success(text)
                }
            }
        } catch (e: Exception) {
            GeminiResult.Error(e.localizedMessage ?: "自架 / OpenAI 語音端點連線失敗")
        }
    }

    suspend fun polishOrTranslateText(
        text: String,
        endpoint: String,
        model: String,
        apiKey: String,
        languageTag: String,
        autoPunctuation: Boolean,
        removeFillers: Boolean,
        targetTranslationName: String = ""
    ): GeminiResult<String> = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext GeminiResult.Success("")

        val url = buildUrl(endpoint, "/chat/completions")
        val sysPrompt = buildString {
            appendLine("你是高精度語音輸入法文字後處理引擎。")
            appendLine("- 目標語系：$languageTag（若為 zh-TW 請嚴格保持繁體中文台灣用語）。")
            appendLine("- 修正同音異字、誤判詞彙與語病，不可改變原意。")
            if (autoPunctuation) {
                appendLine("- 中文標點規則：話語停頓與子句間使用全形逗號「，」，僅在整段話最後結尾使用句號「。」（或「？」「！」）。")
            }
            if (removeFillers) {
                appendLine("- 去除無意義口語贅詞（嗯、啊、呃、那個等）。")
            }
            if (targetTranslationName.isNotBlank()) {
                appendLine("- 最後請將修潤後的結果直接翻譯成「$targetTranslationName」。")
            }
            append("- 嚴格規則：只輸出最終文字本身，不要加任何解釋、引號或 Markdown。")
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sysPrompt))
            .put(JSONObject().put("role", "user").put("content", clean))

        val payload = JSONObject()
            .put("model", model.trim().ifBlank { "deepseek-chat" })
            .put("messages", messages)
            .put("temperature", 0.1)

        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = parseOpenAiError(raw) ?: "HTTP ${resp.code}"
                    return@withContext GeminiResult.Error(errMsg, resp.code)
                }
                val content = JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    .orEmpty()
                if (content.isBlank()) {
                    GeminiResult.Error("LLM 端點回傳空白內容")
                } else {
                    GeminiResult.Success(content)
                }
            }
        } catch (e: Exception) {
            GeminiResult.Error(e.localizedMessage ?: "LLM 端點連線失敗")
        }
    }

    private fun buildUrl(base: String, suffix: String): String {
        val trimmed = base.trim().removeSuffix("/")
        return if (trimmed.endsWith(suffix)) trimmed else "$trimmed$suffix"
    }

    private fun parseOpenAiError(rawJson: String): String? {
        return try {
            JSONObject(rawJson).optJSONObject("error")?.optString("message")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
