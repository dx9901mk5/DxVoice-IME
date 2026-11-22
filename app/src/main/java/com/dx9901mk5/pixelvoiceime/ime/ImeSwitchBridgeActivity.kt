package com.dx9901mk5.pixelvoiceime.ime

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Transparent bridge Activity launched from FloatingMicService to switch into PixelVoice IME
 * (directly if WRITE_SECURE_SETTINGS is granted, otherwise via the system IME picker).
 */
class ImeSwitchBridgeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imeComponent = ComponentName(this, PixelVoiceImeService::class.java).flattenToShortString()
        val switchedDirectly = try {
            Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, imeComponent)
            true
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
            }, 350L)
        }, 80L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
