package com.dx9901mk5.pixelvoiceime.ai

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class TranslationTargetOption(
    val code: String,
    val shortLabel: String,
    val fullName: String
)

/**
 * Lightweight on-device post-ASR translator powered by Google ML Kit (~30MB per language pair),
 * with optional fallback to Gemini API when online.
 */
object LocalTranslator {

    val targets: List<TranslationTargetOption> = listOf(
        TranslationTargetOption("", "原文", "不翻譯 (維持原語言)"),
        TranslationTargetOption("en", "英", "英文 (English)"),
        TranslationTargetOption("ja", "日", "日文 (日本語)"),
        TranslationTargetOption("ko", "韓", "韓文 (한국어)"),
        TranslationTargetOption("zh", "繁中", "繁體中文"),
        TranslationTargetOption("es", "西", "西班牙文"),
        TranslationTargetOption("fr", "法", "法文")
    )

    fun findTarget(code: String): TranslationTargetOption =
        targets.firstOrNull { it.code == code } ?: targets.first()

    suspend fun translateOnDevice(
        text: String,
        sourceBcp47: String,
        targetCode: String
    ): Result<String> {
        if (targetCode.isBlank() || text.isBlank()) return Result.success(text)
        val srcLang = mapToMlKitLang(sourceBcp47)
            ?: return Result.failure(IllegalArgumentException("不支援的來源語言：$sourceBcp47"))
        val dstLang = mapToMlKitLang(targetCode)
            ?: return Result.failure(IllegalArgumentException("不支援的目標語言：$targetCode"))
        if (srcLang == dstLang) return Result.success(text)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang)
            .setTargetLanguage(dstLang)
            .build()
        val client = Translation.getClient(options)

        return try {
            val downloadOk = suspendCancellableCoroutine<Result<Unit>> { cont ->
                client.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { if (cont.isActive) cont.resume(Result.success(Unit)) }
                    .addOnFailureListener { e -> if (cont.isActive) cont.resume(Result.failure(e)) }
            }
            if (downloadOk.isFailure) {
                return Result.failure(downloadOk.exceptionOrNull() ?: IllegalStateException("翻譯模型下載失敗"))
            }

            suspendCancellableCoroutine { cont ->
                client.translate(text)
                    .addOnSuccessListener { out ->
                        val finalOut = if (targetCode == "zh") {
                            LocalTextPolisher.toTraditionalTaiwan(out)
                        } else {
                            out
                        }
                        if (cont.isActive) cont.resume(Result.success(finalOut))
                    }
                    .addOnFailureListener { e ->
                        if (cont.isActive) cont.resume(Result.failure(e))
                    }
            }
        } finally {
            client.close()
        }
    }

    private fun mapToMlKitLang(tag: String): String? {
        val lower = tag.lowercase()
        return when {
            lower.startsWith("zh") || lower.startsWith("yue") -> TranslateLanguage.CHINESE
            lower.startsWith("en") -> TranslateLanguage.ENGLISH
            lower.startsWith("ja") -> TranslateLanguage.JAPANESE
            lower.startsWith("ko") -> TranslateLanguage.KOREAN
            lower.startsWith("es") -> TranslateLanguage.SPANISH
            lower.startsWith("fr") -> TranslateLanguage.FRENCH
            lower.startsWith("de") -> TranslateLanguage.GERMAN
            lower.startsWith("vi") -> TranslateLanguage.VIETNAMESE
            lower.startsWith("th") -> TranslateLanguage.THAI
            lower.startsWith("id") -> TranslateLanguage.INDONESIAN
            else -> TranslateLanguage.fromLanguageTag(lower.substringBefore('-'))
        }
    }
}
