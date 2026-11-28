package com.dx9901mk5.pixelvoiceime.diag

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.dx9901mk5.pixelvoiceime.BuildConfig
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.models.PackDownloadState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only diagnostic config exporter protected by android.permission.DUMP (Shell/Shizuku/ADB only).
 *
 * Strictly privacy-safe:
 * - Never exports API keys (only configured/not-configured booleans)
 * - Never exports custom dictionary words, learned phrases, voiceprint embeddings, backup URIs, or typed text
 */
class DiagnosticConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val callingUid = Binder.getCallingUid()
        val allowed = callingUid == Process.SHELL_UID ||
            callingUid == Process.ROOT_UID ||
            callingUid == Process.myUid()
        if (!allowed) {
            throw SecurityException("DiagnosticConfigProvider is restricted to shell/ADB/Shizuku")
        }

        val app = context?.applicationContext as? PixelVoiceApp ?: return null
        val json = buildSanitizedSnapshotJson(app)
        return MatrixCursor(arrayOf("config_json")).apply {
            addRow(arrayOf(json.toString()))
        }
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun buildSanitizedSnapshotJson(app: PixelVoiceApp): JSONObject {
            val cfg = app.prefs.config.value
            val dict = app.customDictionaryStore.state.value
            val packs = app.modelManager.packStates.value
            val enabledLangs = app.modelManager.enabledLanguages.value

            val installedPacksJson = JSONObject()
            packs.forEach { (packId, state) ->
                val label = when (state) {
                    is PackDownloadState.Installed ->
                        "installed (${String.format(java.util.Locale.US, "%.1f", state.sizeBytes / (1024f * 1024f))} MB)"
                    is PackDownloadState.Downloading -> "downloading (${(state.progress * 100).toInt()}%)"
                    is PackDownloadState.Error -> "error (${state.message.take(60)})"
                    PackDownloadState.NotInstalled -> "not_installed"
                }
                installedPacksJson.put(packId, label)
            }

            return JSONObject().apply {
                put("appVersionName", BuildConfig.VERSION_NAME)
                put("appVersionCode", BuildConfig.VERSION_CODE)
                put("isPlayBuild", BuildConfig.IS_PLAY_BUILD)
                put("isBetaFree", BuildConfig.IS_BETA_FREE)
                put("proAccessState", app.entitlementManager.accessState.value::class.java.simpleName)

                put("autoListenOnStart", cfg.autoListenOnStart)
                put("autoReturnToPreviousIme", cfg.autoReturnToPreviousIme)
                put("autoReturnDelayMs", cfg.autoReturnDelayMs)
                put("silenceTimeoutMs", cfg.silenceTimeoutMs)
                put("noSpeechTimeoutMs", cfg.noSpeechTimeoutMs)

                put("activeLanguageTag", cfg.activeLanguageTag)
                put("targetTranslationLang", cfg.targetTranslationLang)
                put("uiLanguage", cfg.uiLanguage.name)
                put("appThemeMode", cfg.appThemeMode.name)
                put("imeBottomLiftDp", cfg.imeBottomLiftDp)

                put("polishMode", cfg.polishMode.name)
                put("geminiPolishEnabled", cfg.geminiPolishEnabled)
                put("hasGeminiApiKey", app.apiKeyStore.hasApiKey.value)
                put("geminiModel", app.apiKeyStore.selectedModel.value)
                put("autoPunctuation", cfg.autoPunctuation)
                put("removeFillerWords", cfg.removeFillerWords)
                put("includeSurroundingContext", cfg.includeSurroundingContext)
                put("localTaiwanTypoFixEnabled", cfg.localTaiwanTypoFixEnabled)
                put("localLlmEnabled", cfg.localLlmEnabled)

                put("customAsrEndpoint", cfg.customAsrEndpoint)
                put("customAsrModel", cfg.customAsrModel)
                put("hasCustomAsrApiKey", cfg.customAsrApiKey.isNotBlank())
                put("customLlmEnabled", cfg.customLlmEnabled)
                put("customLlmEndpoint", cfg.customLlmEndpoint)
                put("customLlmModel", cfg.customLlmModel)
                put("hasCustomLlmApiKey", cfg.customLlmApiKey.isNotBlank())

                put("noiseFilterMode", cfg.noiseFilterMode.name)
                put("hardwareNoiseReduction", cfg.hardwareNoiseReduction)
                put("voiceprintLockEnabled", cfg.voiceprintLockEnabled)
                put("voiceprintThreshold", cfg.voiceprintThreshold.toDouble())
                put("voiceprintModelReady", app.voiceprintManager.isModelReady())
                put("voiceprintEnrolled", app.voiceprintManager.isEnrolled())

                put("floatingBallEnabled", cfg.floatingBallEnabled)
                put("floatingBallAlpha", cfg.floatingBallAlpha.toDouble())
                put("previousImeId", cfg.previousImeId)
                put("previousImeLabel", cfg.previousImeLabel)
                put("autoUpdateEnabled", cfg.autoUpdateEnabled)

                put("customDictWordCount", dict.customWords.size)
                put("customDictReplacementCount", dict.replacements.size)
                put("customDictLearnedCount", dict.learnedPhrases.size)
                put("customDictAutoLearn", dict.autoLearnEnabled)
                put("customDictUseAsHints", dict.useAsHintsEnabled)

                put("enabledLanguages", JSONArray(enabledLangs.sorted()))
                put("offlinePackStates", installedPacksJson)
            }
        }
    }
}
