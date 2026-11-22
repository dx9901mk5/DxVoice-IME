package com.dx9901mk5.pixelvoiceime.asr

sealed interface VoiceSessionState {
    data object Idle : VoiceSessionState
    data object PermissionRequired : VoiceSessionState
    data object PasswordProtected : VoiceSessionState
    data class Listening(
        val partialText: String = "",
        val amplitude: Float = 0f,
        val speechDetected: Boolean = false,
        val engineLabel: String = ""
    ) : VoiceSessionState

    data class Polishing(
        val rawText: String,
        val statusText: String = "Gemini 修正語意與標點中…"
    ) : VoiceSessionState

    data class Committed(
        val rawText: String,
        val finalText: String,
        val wasAiPolished: Boolean,
        val returningSoon: Boolean = false
    ) : VoiceSessionState

    data class Error(
        val message: String
    ) : VoiceSessionState
}
