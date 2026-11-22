package com.dx9901mk5.pixelvoiceime.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dx9901mk5.pixelvoiceime.BuildConfig
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports and restores all PixelVoice-IME preferences, enabled languages, floating ball coordinates,
 * and custom endpoint settings to a user-selected Local or Google Drive SAF folder/document.
 */
object BackupManager {

    fun buildBackupFileName(deviceName: String): String {
        val cleanName = deviceName.trim()
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_')
            .ifBlank { "PixelVoice" }
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
        return "${cleanName}_${dateStr}.json"
    }

    fun exportJson(
        prefs: VoiceImePrefs,
        apiKeyStore: ApiKeyStore,
        modelManager: OfflineModelManager
    ): String {
        val cfg = prefs.config.value
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("deviceName", cfg.backupDeviceName)
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
            put("selectedGeminiModel", apiKeyStore.selectedModel.value)
            put("enabledLanguages", JSONArray(modelManager.enabledLanguages.value.toList()))
            put(
                "config",
                JSONObject().apply {
                    put("autoListenOnStart", cfg.autoListenOnStart)
                    put("autoReturnToPreviousIme", cfg.autoReturnToPreviousIme)
                    put("autoReturnDelayMs", cfg.autoReturnDelayMs)
                    put("silenceTimeoutMs", cfg.silenceTimeoutMs)
                    put("noSpeechTimeoutMs", cfg.noSpeechTimeoutMs)
                    put("geminiPolishEnabled", cfg.geminiPolishEnabled)
                    put("polishMode", cfg.polishMode.name)
                    put("autoPunctuation", cfg.autoPunctuation)
                    put("removeFillerWords", cfg.removeFillerWords)
                    put("includeSurroundingContext", cfg.includeSurroundingContext)
                    put("localTaiwanTypoFixEnabled", cfg.localTaiwanTypoFixEnabled)
                    put("localLlmEnabled", cfg.localLlmEnabled)
                    put("activeLanguageTag", cfg.activeLanguageTag)
                    put("targetTranslationLang", cfg.targetTranslationLang)
                    put("customAsrEndpoint", cfg.customAsrEndpoint)
                    put("customAsrModel", cfg.customAsrModel)
                    put("customLlmEnabled", cfg.customLlmEnabled)
                    put("customLlmEndpoint", cfg.customLlmEndpoint)
                    put("customLlmModel", cfg.customLlmModel)
                    put("githubRepoUrl", cfg.githubRepoUrl)
                    put("autoUpdateEnabled", cfg.autoUpdateEnabled)
                    put("appThemeMode", cfg.appThemeMode.name)
                    put("floatingBallEnabled", cfg.floatingBallEnabled)
                    put("floatingBallAlpha", cfg.floatingBallAlpha.toDouble())
                    put("floatingBallOuterX", cfg.floatingBallOuterX)
                    put("floatingBallOuterY", cfg.floatingBallOuterY)
                    put("floatingBallInnerX", cfg.floatingBallInnerX)
                    put("floatingBallInnerY", cfg.floatingBallInnerY)
                    put("backupDeviceName", cfg.backupDeviceName)
                }
            )
        }
        return root.toString(2)
    }

    fun writeBackupToTreeUri(
        context: Context,
        treeUri: Uri,
        fileName: String,
        jsonContent: String
    ): Result<String> {
        return try {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Result.failure(IllegalStateException("無法存取指定的備份資料夾"))
            val doc = dir.createFile("application/json", fileName)
                ?: return Result.failure(IllegalStateException("無法在目標資料夾建立檔案：$fileName"))
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(jsonContent.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return Result.failure(IllegalStateException("無法開啟寫入串流"))
            Result.success(doc.name ?: fileName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun writeBackupToDocumentUri(
        context: Context,
        docUri: Uri,
        jsonContent: String
    ): Result<Unit> {
        return try {
            context.contentResolver.openOutputStream(docUri)?.use { out ->
                out.write(jsonContent.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return Result.failure(IllegalStateException("無法開啟檔案寫入串流"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun restoreFromDocumentUri(
        context: Context,
        docUri: Uri,
        prefs: VoiceImePrefs,
        apiKeyStore: ApiKeyStore,
        modelManager: OfflineModelManager
    ): Result<String> {
        return try {
            val raw = context.contentResolver.openInputStream(docUri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return Result.failure(IllegalStateException("無法讀取備份檔案"))

            val root = JSONObject(raw)
            val cfgJson = root.optJSONObject("config")
                ?: return Result.failure(IllegalArgumentException("備份檔格式不正確"))

            val model = root.optString("selectedGeminiModel")
            if (model.isNotBlank()) apiKeyStore.setModel(model)

            val langs = root.optJSONArray("enabledLanguages")
            if (langs != null) {
                for (i in 0 until langs.length()) {
                    val tag = langs.optString(i)
                    if (tag.isNotBlank()) modelManager.enableLanguage(tag)
                }
            }

            prefs.update { cur ->
                val polishMode = runCatching {
                    PolishMode.valueOf(cfgJson.optString("polishMode", cur.polishMode.name))
                }.getOrDefault(cur.polishMode)
                val themeMode = runCatching {
                    AppThemeMode.valueOf(cfgJson.optString("appThemeMode", cur.appThemeMode.name))
                }.getOrDefault(cur.appThemeMode)

                cur.copy(
                    autoListenOnStart = cfgJson.optBoolean("autoListenOnStart", cur.autoListenOnStart),
                    autoReturnToPreviousIme = cfgJson.optBoolean("autoReturnToPreviousIme", cur.autoReturnToPreviousIme),
                    autoReturnDelayMs = cfgJson.optLong("autoReturnDelayMs", cur.autoReturnDelayMs),
                    silenceTimeoutMs = cfgJson.optLong("silenceTimeoutMs", cur.silenceTimeoutMs),
                    noSpeechTimeoutMs = cfgJson.optLong("noSpeechTimeoutMs", cur.noSpeechTimeoutMs),
                    geminiPolishEnabled = cfgJson.optBoolean("geminiPolishEnabled", cur.geminiPolishEnabled),
                    polishMode = polishMode,
                    autoPunctuation = cfgJson.optBoolean("autoPunctuation", cur.autoPunctuation),
                    removeFillerWords = cfgJson.optBoolean("removeFillerWords", cur.removeFillerWords),
                    includeSurroundingContext = cfgJson.optBoolean("includeSurroundingContext", cur.includeSurroundingContext),
                    localTaiwanTypoFixEnabled = cfgJson.optBoolean("localTaiwanTypoFixEnabled", cur.localTaiwanTypoFixEnabled),
                    localLlmEnabled = cfgJson.optBoolean("localLlmEnabled", cur.localLlmEnabled),
                    activeLanguageTag = cfgJson.optString("activeLanguageTag", cur.activeLanguageTag),
                    targetTranslationLang = cfgJson.optString("targetTranslationLang", cur.targetTranslationLang),
                    customAsrEndpoint = cfgJson.optString("customAsrEndpoint", cur.customAsrEndpoint),
                    customAsrModel = cfgJson.optString("customAsrModel", cur.customAsrModel),
                    customLlmEnabled = cfgJson.optBoolean("customLlmEnabled", cur.customLlmEnabled),
                    customLlmEndpoint = cfgJson.optString("customLlmEndpoint", cur.customLlmEndpoint),
                    customLlmModel = cfgJson.optString("customLlmModel", cur.customLlmModel),
                    githubRepoUrl = cfgJson.optString("githubRepoUrl", cur.githubRepoUrl),
                    autoUpdateEnabled = cfgJson.optBoolean("autoUpdateEnabled", cur.autoUpdateEnabled),
                    appThemeMode = themeMode,
                    floatingBallEnabled = cfgJson.optBoolean("floatingBallEnabled", cur.floatingBallEnabled),
                    floatingBallAlpha = cfgJson.optDouble("floatingBallAlpha", cur.floatingBallAlpha.toDouble()).toFloat().coerceIn(0.25f, 1.0f),
                    floatingBallOuterX = cfgJson.optInt("floatingBallOuterX", cur.floatingBallOuterX),
                    floatingBallOuterY = cfgJson.optInt("floatingBallOuterY", cur.floatingBallOuterY),
                    floatingBallInnerX = cfgJson.optInt("floatingBallInnerX", cur.floatingBallInnerX),
                    floatingBallInnerY = cfgJson.optInt("floatingBallInnerY", cur.floatingBallInnerY),
                    backupDeviceName = cfgJson.optString("backupDeviceName", cur.backupDeviceName)
                )
            }
            val restoredFrom = root.optString("deviceName").ifBlank { "備份檔" }
            Result.success(restoredFrom)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
