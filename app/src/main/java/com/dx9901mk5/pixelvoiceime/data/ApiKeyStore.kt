package com.dx9901mk5.pixelvoiceime.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the user's BYOK Gemini API key and model preference securely on-device.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private val _hasApiKey = MutableStateFlow(getApiKey().isNotBlank())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(loadModel())
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private fun loadModel(): String {
        val saved = prefs.getString(KEY_MODEL, DEFAULT_MODEL)?.trim().orEmpty()
        if (saved.isBlank() || saved == "gemini-4-hydrogen") {
            prefs.edit().putString(KEY_MODEL, DEFAULT_MODEL).apply()
            return DEFAULT_MODEL
        }
        return saved
    }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_API_KEY, trimmed).apply()
        _hasApiKey.value = trimmed.isNotBlank()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
        _hasApiKey.value = false
    }

    fun setModel(model: String) {
        val clean = model.trim().ifBlank { DEFAULT_MODEL }
        prefs.edit().putString(KEY_MODEL, clean).apply()
        _selectedModel.value = clean
    }

    private fun createPrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val PREFS_NAME = "pixelvoice_secure_prefs"
        private const val PREFS_NAME_FALLBACK = "pixelvoice_prefs_fallback"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        val PRESET_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-2.5-pro"
        )
    }
}
