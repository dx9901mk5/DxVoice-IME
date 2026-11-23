package com.dx9901mk5.pixelvoiceime.ime

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference

/**
 * Lightweight AccessibilityService (canRetrieveWindowContent=false) used solely to:
 * 1. Detect when any soft keyboard (TYPE_INPUT_METHOD) opens/closes so the floating ball follows it.
 * 2. Directly switch to PixelVoiceImeService on Android 11+ via SoftKeyboardController.switchToInputMethod().
 */
class PixelVoiceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        updateKeyboardVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            updateKeyboardVisibility()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        FloatingMicService.notifyExternalKeyboardVisible(this, false)
        super.onDestroy()
    }

    private fun updateKeyboardVisibility() {
        val visible = computeExternalKeyboardVisible()
        FloatingMicService.notifyExternalKeyboardVisible(this, visible)
    }

    private fun computeExternalKeyboardVisible(): Boolean {
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return false
        if (winList.isEmpty()) return false
        val hasImeWindow = winList.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val activeIme = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        }.getOrDefault("")
        val isOwnIme = activeIme.contains(packageName)
        return hasImeWindow && !isOwnIme
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<PixelVoiceAccessibilityService>? = null

        fun isRunning(): Boolean = instanceRef?.get() != null

        fun queryExternalKeyboardVisible(): Boolean {
            return instanceRef?.get()?.computeExternalKeyboardVisible() ?: false
        }

        fun switchToPixelVoiceIme(context: Context): Boolean {
            val svc = instanceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val imeId = ComponentName(context, PixelVoiceImeService::class.java).flattenToShortString()
            return try {
                svc.softKeyboardController.switchToInputMethod(imeId)
            } catch (_: Exception) {
                false
            }
        }
    }
}
