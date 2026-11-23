package com.dx9901mk5.pixelvoiceime.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PolishMode {
    FAST_TEXT,
    AUDIO_MULTIMODAL,
    OPENAI_COMPATIBLE
}

enum class AppThemeMode {
    DYNAMIC_PIXEL,
    ORANGE_CHARCOAL
}

data class VoiceImeConfig(
    val autoListenOnStart: Boolean = true,
    val autoReturnToPreviousIme: Boolean = true,
    val autoReturnDelayMs: Long = 2500L,
    val silenceTimeoutMs: Long = 3000L,
    val noSpeechTimeoutMs: Long = 6000L,
    val geminiPolishEnabled: Boolean = true,
    val polishMode: PolishMode = PolishMode.FAST_TEXT,
    val autoPunctuation: Boolean = true,
    val removeFillerWords: Boolean = true,
    val includeSurroundingContext: Boolean = true,
    val localTaiwanTypoFixEnabled: Boolean = true,
    val localLlmEnabled: Boolean = false,
    val activeLanguageTag: String = "zh-TW",
    val targetTranslationLang: String = "",
    val customAsrEndpoint: String = "https://api.groq.com/openai/v1",
    val customAsrModel: String = "whisper-large-v3-turbo",
    val customAsrApiKey: String = "",
    val customLlmEnabled: Boolean = false,
    val customLlmEndpoint: String = "https://api.deepseek.com/v1",
    val customLlmModel: String = "deepseek-chat",
    val customLlmApiKey: String = "",
    val githubRepoUrl: String = DEFAULT_GITHUB_REPO,
    val autoUpdateEnabled: Boolean = true,
    val previousImeId: String = "",
    val previousImeLabel: String = "",
    val appThemeMode: AppThemeMode = AppThemeMode.DYNAMIC_PIXEL,
    val floatingBallEnabled: Boolean = false,
    val floatingBallAlpha: Float = 0.85f,
    val floatingBallOuterX: Int = 40,
    val floatingBallOuterY: Int = 420,
    val floatingBallInnerX: Int = 60,
    val floatingBallInnerY: Int = 520,
    val backupDeviceName: String = Build.MODEL ?: "DxVoice-IME",
    val backupTreeUri: String = ""
) {
    companion object {
        const val DEFAULT_GITHUB_REPO = "https://github.com/dx9901mk5/DxVoice-IME"
        private const val LEGACY_GITHUB_REPO = "https://github.com/dx9901mk5/PixelVoice-IME"
        fun normalizeRepoUrl(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            return if (trimmed.isBlank() || trimmed.equals(LEGACY_GITHUB_REPO, ignoreCase = true)) {
                DEFAULT_GITHUB_REPO
            } else {
                trimmed
            }
        }
    }
}

class VoiceImePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<VoiceImeConfig> = _config.asStateFlow()

    private fun load(): VoiceImeConfig {
        val modeName = prefs.getString(KEY_POLISH_MODE, PolishMode.FAST_TEXT.name)
        val polishMode = runCatching { PolishMode.valueOf(modeName ?: "") }
            .getOrDefault(PolishMode.FAST_TEXT)
        val themeName = prefs.getString(KEY_THEME_MODE, AppThemeMode.DYNAMIC_PIXEL.name)
        val themeMode = runCatching { AppThemeMode.valueOf(themeName ?: "") }
            .getOrDefault(AppThemeMode.DYNAMIC_PIXEL)
        val rawSilence = prefs.getLong(KEY_SILENCE_TIMEOUT, 3000L)
        val silenceTimeoutMs = if (rawSilence < 2500L) 3000L else rawSilence
        val rawDelay = prefs.getLong(KEY_AUTO_RETURN_DELAY, 2500L)
        val migratedDelay = if (!prefs.getBoolean(KEY_MIGRATED_DELAY_2500, false)) {
            val nextDelay = if (rawDelay == 450L) 2500L else rawDelay
            prefs.edit()
                .putLong(KEY_AUTO_RETURN_DELAY, nextDelay)
                .putBoolean(KEY_MIGRATED_DELAY_2500, true)
                .apply()
            nextDelay
        } else {
            rawDelay
        }
        return VoiceImeConfig(
            autoListenOnStart = prefs.getBoolean(KEY_AUTO_LISTEN, true),
            autoReturnToPreviousIme = prefs.getBoolean(KEY_AUTO_RETURN, true),
            autoReturnDelayMs = migratedDelay,
            silenceTimeoutMs = silenceTimeoutMs,
            noSpeechTimeoutMs = prefs.getLong(KEY_NO_SPEECH_TIMEOUT, 6000L),
            geminiPolishEnabled = prefs.getBoolean(KEY_GEMINI_POLISH, true),
            polishMode = polishMode,
            autoPunctuation = prefs.getBoolean(KEY_AUTO_PUNCTUATION, true),
            removeFillerWords = prefs.getBoolean(KEY_REMOVE_FILLERS, true),
            includeSurroundingContext = prefs.getBoolean(KEY_INCLUDE_CONTEXT, true),
            localTaiwanTypoFixEnabled = prefs.getBoolean(KEY_LOCAL_TYPO_FIX, true),
            localLlmEnabled = prefs.getBoolean(KEY_LOCAL_LLM_ENABLED, false),
            activeLanguageTag = prefs.getString(KEY_ACTIVE_LANG, "zh-TW") ?: "zh-TW",
            targetTranslationLang = prefs.getString(KEY_TARGET_TRANSLATION, "") ?: "",
            customAsrEndpoint = prefs.getString(KEY_CUSTOM_ASR_ENDPOINT, "https://api.groq.com/openai/v1")
                ?.ifBlank { "https://api.groq.com/openai/v1" } ?: "https://api.groq.com/openai/v1",
            customAsrModel = prefs.getString(KEY_CUSTOM_ASR_MODEL, "whisper-large-v3-turbo")
                ?.ifBlank { "whisper-large-v3-turbo" } ?: "whisper-large-v3-turbo",
            customAsrApiKey = prefs.getString(KEY_CUSTOM_ASR_KEY, "") ?: "",
            customLlmEnabled = prefs.getBoolean(KEY_CUSTOM_LLM_ENABLED, false),
            customLlmEndpoint = prefs.getString(KEY_CUSTOM_LLM_ENDPOINT, "https://api.deepseek.com/v1")
                ?.ifBlank { "https://api.deepseek.com/v1" } ?: "https://api.deepseek.com/v1",
            customLlmModel = prefs.getString(KEY_CUSTOM_LLM_MODEL, "deepseek-chat")
                ?.ifBlank { "deepseek-chat" } ?: "deepseek-chat",
            customLlmApiKey = prefs.getString(KEY_CUSTOM_LLM_KEY, "") ?: "",
            githubRepoUrl = VoiceImeConfig.normalizeRepoUrl(prefs.getString(KEY_GITHUB_REPO, VoiceImeConfig.DEFAULT_GITHUB_REPO)),
            autoUpdateEnabled = prefs.getBoolean(KEY_AUTO_UPDATE, true),
            previousImeId = prefs.getString(KEY_PREV_IME_ID, "") ?: "",
            previousImeLabel = prefs.getString(KEY_PREV_IME_LABEL, "") ?: "",
            appThemeMode = themeMode,
            floatingBallEnabled = prefs.getBoolean(KEY_FLOATING_BALL_ENABLED, false),
            floatingBallAlpha = prefs.getFloat(KEY_FLOATING_BALL_ALPHA, 0.85f).coerceIn(0.25f, 1.0f),
            floatingBallOuterX = prefs.getInt(KEY_FLOATING_OUTER_X, 40),
            floatingBallOuterY = prefs.getInt(KEY_FLOATING_OUTER_Y, 420),
            floatingBallInnerX = prefs.getInt(KEY_FLOATING_INNER_X, 60),
            floatingBallInnerY = prefs.getInt(KEY_FLOATING_INNER_Y, 520),
            backupDeviceName = prefs.getString(KEY_BACKUP_DEVICE_NAME, Build.MODEL ?: "DxVoice-IME")
                ?.ifBlank { Build.MODEL ?: "DxVoice-IME" } ?: "DxVoice-IME",
            backupTreeUri = prefs.getString(KEY_BACKUP_TREE_URI, "") ?: ""
        )
    }

    fun update(transform: (VoiceImeConfig) -> VoiceImeConfig) {
        val updated = transform(_config.value)
        prefs.edit()
            .putBoolean(KEY_AUTO_LISTEN, updated.autoListenOnStart)
            .putBoolean(KEY_AUTO_RETURN, updated.autoReturnToPreviousIme)
            .putLong(KEY_AUTO_RETURN_DELAY, updated.autoReturnDelayMs)
            .putLong(KEY_SILENCE_TIMEOUT, updated.silenceTimeoutMs)
            .putLong(KEY_NO_SPEECH_TIMEOUT, updated.noSpeechTimeoutMs)
            .putBoolean(KEY_GEMINI_POLISH, updated.geminiPolishEnabled)
            .putString(KEY_POLISH_MODE, updated.polishMode.name)
            .putBoolean(KEY_AUTO_PUNCTUATION, updated.autoPunctuation)
            .putBoolean(KEY_REMOVE_FILLERS, updated.removeFillerWords)
            .putBoolean(KEY_INCLUDE_CONTEXT, updated.includeSurroundingContext)
            .putBoolean(KEY_LOCAL_TYPO_FIX, updated.localTaiwanTypoFixEnabled)
            .putBoolean(KEY_LOCAL_LLM_ENABLED, updated.localLlmEnabled)
            .putString(KEY_ACTIVE_LANG, updated.activeLanguageTag)
            .putString(KEY_TARGET_TRANSLATION, updated.targetTranslationLang)
            .putString(KEY_CUSTOM_ASR_ENDPOINT, updated.customAsrEndpoint)
            .putString(KEY_CUSTOM_ASR_MODEL, updated.customAsrModel)
            .putString(KEY_CUSTOM_ASR_KEY, updated.customAsrApiKey)
            .putBoolean(KEY_CUSTOM_LLM_ENABLED, updated.customLlmEnabled)
            .putString(KEY_CUSTOM_LLM_ENDPOINT, updated.customLlmEndpoint)
            .putString(KEY_CUSTOM_LLM_MODEL, updated.customLlmModel)
            .putString(KEY_CUSTOM_LLM_KEY, updated.customLlmApiKey)
            .putString(KEY_GITHUB_REPO, updated.githubRepoUrl)
            .putBoolean(KEY_AUTO_UPDATE, updated.autoUpdateEnabled)
            .putString(KEY_PREV_IME_ID, updated.previousImeId)
            .putString(KEY_PREV_IME_LABEL, updated.previousImeLabel)
            .putString(KEY_THEME_MODE, updated.appThemeMode.name)
            .putBoolean(KEY_FLOATING_BALL_ENABLED, updated.floatingBallEnabled)
            .putFloat(KEY_FLOATING_BALL_ALPHA, updated.floatingBallAlpha)
            .putInt(KEY_FLOATING_OUTER_X, updated.floatingBallOuterX)
            .putInt(KEY_FLOATING_OUTER_Y, updated.floatingBallOuterY)
            .putInt(KEY_FLOATING_INNER_X, updated.floatingBallInnerX)
            .putInt(KEY_FLOATING_INNER_Y, updated.floatingBallInnerY)
            .putString(KEY_BACKUP_DEVICE_NAME, updated.backupDeviceName)
            .putString(KEY_BACKUP_TREE_URI, updated.backupTreeUri)
            .apply()
        _config.value = updated
    }

    fun rememberPreviousIme(id: String, label: String) {
        if (id.isBlank()) return
        update { it.copy(previousImeId = id, previousImeLabel = label) }
    }

    companion object {
        private const val PREFS_NAME = "pixelvoice_ime_prefs"
        private const val KEY_AUTO_LISTEN = "auto_listen_on_start"
        private const val KEY_AUTO_RETURN = "auto_return_prev_ime"
        private const val KEY_AUTO_RETURN_DELAY = "auto_return_delay_ms"
        private const val KEY_MIGRATED_DELAY_2500 = "migrated_auto_return_delay_2500"
        private const val KEY_SILENCE_TIMEOUT = "silence_timeout_ms"
        private const val KEY_NO_SPEECH_TIMEOUT = "no_speech_timeout_ms"
        private const val KEY_GEMINI_POLISH = "gemini_polish_enabled"
        private const val KEY_POLISH_MODE = "polish_mode"
        private const val KEY_AUTO_PUNCTUATION = "auto_punctuation"
        private const val KEY_REMOVE_FILLERS = "remove_fillers"
        private const val KEY_INCLUDE_CONTEXT = "include_context"
        private const val KEY_LOCAL_TYPO_FIX = "local_taiwan_typo_fix"
        private const val KEY_LOCAL_LLM_ENABLED = "local_llm_enabled"
        private const val KEY_ACTIVE_LANG = "active_language_tag"
        private const val KEY_TARGET_TRANSLATION = "target_translation_lang"
        private const val KEY_CUSTOM_ASR_ENDPOINT = "custom_asr_endpoint"
        private const val KEY_CUSTOM_ASR_MODEL = "custom_asr_model"
        private const val KEY_CUSTOM_ASR_KEY = "custom_asr_key"
        private const val KEY_CUSTOM_LLM_ENABLED = "custom_llm_enabled"
        private const val KEY_CUSTOM_LLM_ENDPOINT = "custom_llm_endpoint"
        private const val KEY_CUSTOM_LLM_MODEL = "custom_llm_model"
        private const val KEY_CUSTOM_LLM_KEY = "custom_llm_key"
        private const val KEY_GITHUB_REPO = "github_repo_url"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_PREV_IME_ID = "previous_ime_id"
        private const val KEY_PREV_IME_LABEL = "previous_ime_label"
        private const val KEY_THEME_MODE = "app_theme_mode"
        private const val KEY_FLOATING_BALL_ENABLED = "floating_ball_enabled"
        private const val KEY_FLOATING_BALL_ALPHA = "floating_ball_alpha"
        private const val KEY_FLOATING_OUTER_X = "floating_ball_outer_x"
        private const val KEY_FLOATING_OUTER_Y = "floating_ball_outer_y"
        private const val KEY_FLOATING_INNER_X = "floating_ball_inner_x"
        private const val KEY_FLOATING_INNER_Y = "floating_ball_inner_y"
        private const val KEY_BACKUP_DEVICE_NAME = "backup_device_name"
        private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
    }
}
