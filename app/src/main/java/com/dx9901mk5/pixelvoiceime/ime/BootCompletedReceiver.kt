package com.dx9901mk5.pixelvoiceime.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores FloatingMicService automatically after device reboot, user unlock,
 * or Google Play app update when the user has enabled the floating voice ball.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_USER_UNLOCKED
        ) {
            FloatingMicService.ensureStarted(context.applicationContext)
        }
    }
}
