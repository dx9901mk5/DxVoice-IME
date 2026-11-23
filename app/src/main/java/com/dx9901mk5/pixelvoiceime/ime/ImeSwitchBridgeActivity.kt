package com.dx9901mk5.pixelvoiceime.ime

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Transparent bridge Activity launched from FloatingMicService to switch into PixelVoice IME
 * (directly if WRITE_SECURE_SETTINGS is granted, otherwise via the system IME picker,
 * where PixelVoiceAccessibilityService auto-selects PixelVoice IME when active).
 */
class ImeSwitchBridgeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imeId = PixelVoiceAccessibilityService.resolveOwnImeId(this)
        val switchedDirectly = try {
            Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, imeId)
            PixelVoiceAccessibilityService.isOwnImeCurrentlyActive(this)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }

        if (switchedDirectly) {
            finish()
            overridePendingTransition(0, 0)
            return
        }

        handler.postDelayed({
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (_: Exception) {
            }
            handler.postDelayed({
                finish()
                overridePendingTransition(0, 0)
            }, 220L)
        }, 60L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
