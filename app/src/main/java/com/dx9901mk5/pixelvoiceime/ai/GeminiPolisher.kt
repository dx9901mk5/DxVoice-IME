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
        removeFillers: Boolean = true,
        referenceHints: String = ""
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
            if (referenceHints.isNotBlank()) {
                appendLine("- 修飾依據／個人詞庫（僅作同音字與專有名詞拼寫參考，嚴禁插入未說出的詞）：")
                appendLine(referenceHints)
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
        removeFillers: Boolean = true,
        referenceHints: String = ""
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
            if (referenceHints.isNotBlank()) {
                appendLine("- 修飾依據／個人詞庫（僅作同音字與專有名詞拼寫參考，嚴禁插入未說出的詞）：")
                appendLine(referenceHints)
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
     * Ensures CJK pauses/clauses use single commas (，), collapses duplicate punctuation (e.g. ，， -> ，, ，。 -> 。),
     * and only the final ending uses a period (。) / question / exclamation mark.
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
            .replace(Regex("""[，,]+"""), "，")
            .replace(Regex("""[、]+"""), "、")
            .replace(Regex("""[。]+"""), "。")
            .replace(Regex("""[？]+"""), "？")
            .replace(Regex("""[！]+"""), "！")
            .replace(Regex("""[，、；,;]+([。？！])"""), "$1")
            .replace(Regex("""([。？！])[，、；,;]+"""), "$1")
            .replace(Regex("""^[，、；。,.!?;:\s]+"""), "")
            .trim()
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
                .replace(Regex("""[，、；,;]+$"""), "")
                .trim()
        } else {
            trimmed.replace(Regex("""[,;]+$"""), "").trim()
        }
        if (withClauseCommas.isBlank()) return ""

        val lastChar = withClauseCommas.last()
        if (lastChar in setOf('？', '！', '?', '!')) {
            return withClauseCommas
        }
        val body = if (lastChar == '。' || lastChar == '.') {
            withClauseCommas.dropLast(1).trimEnd()
        } else {
            withClauseCommas
        }
        if (body.isBlank()) return ""

        return if (isCjk) {
            body + detectCjkEndingPunctuation(body)
        } else {
            val capitalized = body.replaceFirstChar { it.uppercase() }
            capitalized + detectEnglishEndingPunctuation(body)
        }
    }

    private fun detectCjkEndingPunctuation(text: String): String {
        val t = text.trim()
        // Support both Traditional and Simplified Chinese since CT-Transformer runs before S2T conversion
        val isDeclarativeIdiom = (t.contains("沒什麼") || t.contains("没什么") ||
            t.contains("沒有什麼") || t.contains("没有什么") ||
            t.contains("不知道") || t.contains("不曉得") || t.contains("不晓得") ||
            t.contains("不清楚")) &&
            !t.endsWith("嗎") && !t.endsWith("吗") && !t.endsWith("呢") && !t.endsWith("吧")

        val endsWithQuestionParticle = t.endsWith("嗎") || t.endsWith("吗") ||
            t.endsWith("呢") || t.endsWith("嘛") ||
            t.endsWith("對吧") || t.endsWith("对吧") || t.endsWith("是吧") ||
            t.endsWith("好嗎") || t.endsWith("好吗") || t.endsWith("行嗎") || t.endsWith("行吗") ||
            t.endsWith("可以嗎") || t.endsWith("可以吗") ||
            t.endsWith("對不對") || t.endsWith("对不对") || t.endsWith("是不是") ||
            t.endsWith("有沒有") || t.endsWith("有没有") || t.endsWith("要不要") ||
            t.endsWith("會不會") || t.endsWith("会不会") || t.endsWith("能不能") ||
            t.endsWith("可不可以") || t.endsWith("好不好") || t.endsWith("行不行") ||
            t.endsWith("該不該") || t.endsWith("该不该") || t.endsWith("懂不懂") ||
            t.endsWith("知不知道") || t.endsWith("記不記得") || t.endsWith("记不记得") ||
            t.endsWith("嗎啊") || t.endsWith("吗啊") || t.endsWith("呢啊")

        val hasQuestionWord = !isDeclarativeIdiom && (
            t.contains("什麼") || t.contains("什么") ||
                t.contains("怎麼") || t.contains("怎么") ||
                t.contains("為何") || t.contains("为何") ||
                t.contains("為什麼") || t.contains("为什么") ||
                t.contains("哪裡") || t.contains("哪里") ||
                t.contains("哪邊") || t.contains("哪边") ||
                t.contains("哪個") || t.contains("哪个") ||
                t.contains("哪位") || t.contains("哪些") || t.contains("哪天") ||
                t.contains("幾點") || t.contains("几点") ||
                t.contains("幾天") || t.contains("几天") ||
                t.contains("幾個") || t.contains("几个") ||
                t.contains("幾歲") || t.contains("几岁") ||
                t.contains("多少") || t.contains("多久") ||
                t.contains("多遠") || t.contains("多远") ||
                t.contains("誰") || t.contains("谁") ||
                t.contains("難道") || t.contains("难道") ||
                t.contains("是否") || t.contains("何時") || t.contains("何时") ||
                t.contains("如何") || t.contains("怎樣") || t.contains("怎样") ||
                t.contains("對不對") || t.contains("对不对") ||
                t.contains("是不是") || t.contains("有沒有") || t.contains("有没有") ||
                t.contains("要不要") || t.contains("會不會") || t.contains("会不会") ||
                t.contains("能不能") || t.contains("可不可以")
            )

        if (endsWithQuestionParticle || hasQuestionWord) {
            return "？"
        }

        val startsExclamation = t.startsWith("哇") || t.startsWith("天啊") || t.startsWith("天哪") ||
            t.startsWith("我的天") || t.startsWith("老天") || t.startsWith("哎呀") ||
            t.startsWith("唉呀") || t.startsWith("好耶") || t.startsWith("恭喜") ||
            t.startsWith("加油") || t.startsWith("小心") ||
            t.startsWith("快點") || t.startsWith("快点") ||
            t.startsWith("拜託") || t.startsWith("拜托") || t.startsWith("真的假的")

        val hasStrongExclaimPhrase = t.contains("太好了") || t.contains("太棒了") ||
            t.contains("太強了") || t.contains("太强了") ||
            t.contains("太讚了") || t.contains("太赞了") ||
            t.contains("太扯了") || t.contains("太誇張了") || t.contains("太夸张了") ||
            t.contains("好厲害") || t.contains("好厉害") || t.contains("好好用") ||
            t.contains("好讚") || t.contains("好赞") || t.contains("真棒") ||
            t.contains("真厲害") || t.contains("真厉害") ||
            t.contains("超讚") || t.contains("超赞") || t.contains("超棒") ||
            t.contains("超厲害") || t.contains("超厉害") ||
            t.endsWith("加油") || t.endsWith("恭喜")

        val endsExclaimParticle = t.endsWith("啊") || t.endsWith("呀") || t.endsWith("哇") ||
            t.endsWith("啦") || t.endsWith("囉") || t.endsWith("啰") ||
            t.endsWith("喔") || t.endsWith("耶")
        val hasDegreeBooster = t.contains("太") || t.contains("超") || t.contains("好") ||
            t.contains("真") || t.contains("非常") ||
            t.contains("超級") || t.contains("超级") ||
            t.contains("有夠") || t.contains("有够") ||
            t.contains("簡直") || t.contains("简直") ||
            t.contains("居然") || t.contains("竟然")

        if (startsExclamation || hasStrongExclaimPhrase || (endsExclaimParticle && (hasDegreeBooster || t.length <= 10))) {
            return "！"
        }

        return "。"
    }

    private fun detectEnglishEndingPunctuation(text: String): String {
        val lower = text.trim().lowercase()
        val isQuestion = lower.startsWith("what ") || lower.startsWith("how ") ||
            lower.startsWith("why ") || lower.startsWith("where ") ||
            lower.startsWith("when ") || lower.startsWith("who ") ||
            lower.startsWith("which ") || lower.startsWith("whose ") ||
            lower.startsWith("can ") || lower.startsWith("could ") ||
            lower.startsWith("would ") || lower.startsWith("will ") ||
            lower.startsWith("do ") || lower.startsWith("does ") ||
            lower.startsWith("did ") || lower.startsWith("is ") ||
            lower.startsWith("are ") || lower.startsWith("am ") ||
            lower.endsWith(" right") || lower.endsWith(" isn't it")
        if (isQuestion) return "?"

        val isExclaim = lower.startsWith("wow") || lower.startsWith("awesome") ||
            lower.startsWith("amazing") || lower.startsWith("great") ||
            lower.startsWith("omg") || lower.startsWith("oh my god") ||
            lower.startsWith("congrats") || lower.startsWith("congratulations") ||
            lower.startsWith("watch out") || lower.startsWith("be careful") ||
            lower.startsWith("hurry")
        return if (isExclaim) "!" else "."
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
