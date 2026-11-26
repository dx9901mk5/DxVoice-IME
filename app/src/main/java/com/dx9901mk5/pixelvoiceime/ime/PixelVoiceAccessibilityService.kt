package com.dx9901mk5.pixelvoiceime.ime

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.R

/**
 * Lightweight AccessibilityService used solely to:
 * 1. Detect when any external soft keyboard (TYPE_INPUT_METHOD) opens/closes so the floating ball follows it.
 * 2. Directly switch into PixelVoiceImeService on Android 11+ via SoftKeyboardController.switchToInputMethod(),
 *    with an instant picker auto-select fallback for OEM ROMs (e.g. ColorOS) that intercept direct switching.
 */
class PixelVoiceAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickerPollRunnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() > autoSelectDeadlineUptimeMs) {
                autoSelectDeadlineUptimeMs = 0L
                return
            }
            if (tryAutoClickOwnImeInPicker()) {
                autoSelectDeadlineUptimeMs = 0L
                return
            }
            mainHandler.postDelayed(this, 75L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FloatingMicService.ensureStarted(this)
        updateKeyboardVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            updateKeyboardVisibility()
            if (autoSelectDeadlineUptimeMs > SystemClock.uptimeMillis()) {
                if (tryAutoClickOwnImeInPicker()) {
                    autoSelectDeadlineUptimeMs = 0L
                    mainHandler.removeCallbacks(pickerPollRunnable)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupInstance()
        super.onDestroy()
    }

    private fun cleanupInstance() {
        mainHandler.removeCallbacksAndMessages(null)
        autoSelectDeadlineUptimeMs = 0L
        if (instance === this) {
            instance = null
        }
        FloatingMicService.notifyExternalKeyboardVisible(this, false)
    }

    private fun updateKeyboardVisibility() {
        val visible = computeExternalKeyboardVisible()
        if (visible && !FloatingMicService.isRunning()) {
            FloatingMicService.ensureStarted(this)
        }
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
        return hasImeWindow && !isOwnImeCurrentlyActive(this)
    }

    private fun startPickerPoll() {
        mainHandler.removeCallbacks(pickerPollRunnable)
        mainHandler.postDelayed(pickerPollRunnable, 60L)
    }

    private fun tryAutoClickOwnImeInPicker(): Boolean {
        if (isOwnImeCurrentlyActive(this)) {
            return true
        }
        val winList = runCatching { windows }.getOrNull().orEmpty()
        val roots = ArrayList<AccessibilityNodeInfo>(winList.size + 1)
        for (win in winList) {
            val winTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { win.title?.toString().orEmpty() }.getOrDefault("")
            } else {
                ""
            }
            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == packageName) continue
            if (isSystemPickerWindow(pkg, winTitle)) {
                roots.add(root)
            }
        }
        if (roots.isEmpty()) {
            val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
            val pkg = activeRoot?.packageName?.toString().orEmpty()
            if (activeRoot != null && pkg != packageName && isSystemPickerWindow(pkg, "")) {
                roots.add(activeRoot)
            }
        }

        val imeLabel = getString(R.string.ime_name)
        for (root in roots) {
            val targetNode = findImeItemNode(root, imeLabel)
            if (targetNode != null && performClickUpHierarchy(targetNode)) {
                return true
            }
        }
        return false
    }

    private fun isSystemPickerWindow(pkg: String, title: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == "android" ||
            pkg.startsWith("com.android.") ||
            pkg.startsWith("com.google.android.ext") ||
            pkg.startsWith("com.oplus.") ||
            pkg.startsWith("com.coloros.") ||
            pkg.startsWith("com.heytap.") ||
            pkg.startsWith("com.oneplus.") ||
            pkg.startsWith("com.samsung.") ||
            pkg.startsWith("com.sec.") ||
            pkg.startsWith("com.miui.") ||
            pkg.startsWith("com.xiaomi.") ||
            pkg.startsWith("com.vivo.") ||
            pkg.startsWith("com.bbk.") ||
            pkg.startsWith("com.huawei.") ||
            pkg.startsWith("com.hihonor.")
        ) {
            return true
        }
        return title.contains("InputMethod", ignoreCase = true) ||
            title.contains("輸入法") ||
            title.contains("输入法") ||
            title.contains("Keyboard", ignoreCase = true) ||
            title.contains("鍵盤")
    }

    private fun findImeItemNode(root: AccessibilityNodeInfo, imeLabel: String): AccessibilityNodeInfo? {
        val byKeyword = runCatching { root.findAccessibilityNodeInfosByText("DxVoice") }
            .getOrNull()
            .orEmpty()
        if (byKeyword.isNotEmpty()) {
            return byKeyword.first()
        }
        val byFullLabel = runCatching { root.findAccessibilityNodeInfosByText(imeLabel) }
            .getOrNull()
            .orEmpty()
        if (byFullLabel.isNotEmpty()) {
            return byFullLabel.first()
        }
        return searchNodeRecursive(root, 0)
    }

    private fun searchNodeRecursive(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 12) return null
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (text.contains("DxVoice", ignoreCase = true) ||
            desc.contains("DxVoice", ignoreCase = true)
        ) {
            return node
        }
        val count = node.childCount
        for (i in 0 until count) {
            val found = searchNodeRecursive(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun performClickUpHierarchy(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops <= 6) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            cur = cur.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    companion object {
        @Volatile
        private var instance: PixelVoiceAccessibilityService? = null

        @Volatile
        private var autoSelectDeadlineUptimeMs: Long = 0L

        fun isRunning(): Boolean = instance != null

        fun isEnabledInSystemSettings(context: Context): Boolean {
            val expected = ComponentName(context, PixelVoiceAccessibilityService::class.java)
            val raw = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
            }.getOrDefault("")
            if (raw.isBlank()) return false
            return raw.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        }

        fun isOwnImeCurrentlyActive(context: Context): Boolean {
            val activeIme = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            }.getOrDefault("")
            return activeIme.contains(context.packageName)
        }

        fun queryExternalKeyboardVisible(): Boolean {
            return instance?.computeExternalKeyboardVisible() ?: false
        }

        fun resolveOwnImeId(context: Context): String {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val fromEnabled = imm?.enabledInputMethodList
                ?.firstOrNull { it.packageName == context.packageName }
                ?.id
            if (!fromEnabled.isNullOrBlank()) return fromEnabled

            val fromInstalled = imm?.inputMethodList
                ?.firstOrNull { it.packageName == context.packageName }
                ?.id
            if (!fromInstalled.isNullOrBlank()) return fromInstalled

            return ComponentName(context, PixelVoiceImeService::class.java).flattenToShortString()
        }

        fun rememberCurrentExternalIme(context: Context) {
            runCatching {
                val activeId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                ).orEmpty()
                if (activeId.isBlank() || activeId.contains(context.packageName)) return@runCatching
                val imm = context.getSystemService(InputMethodManager::class.java)
                val info = imm?.enabledInputMethodList?.firstOrNull { it.id == activeId }
                val label = info?.loadLabel(context.packageManager)?.toString().orEmpty()
                val app = context.applicationContext as? PixelVoiceApp
                app?.prefs?.rememberPreviousIme(activeId, label)
            }
        }

        fun switchToPixelVoiceIme(context: Context): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            rememberCurrentExternalIme(context)
            val imeId = resolveOwnImeId(context)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { svc.softKeyboardController.setInputMethodEnabled(imeId, true) }
                }
                svc.softKeyboardController.switchToInputMethod(imeId)
            } catch (_: Exception) {
                false
            }
        }

        fun armAutoSelectPickerWindow(durationMs: Long = 2200L) {
            val svc = instance ?: return
            autoSelectDeadlineUptimeMs = SystemClock.uptimeMillis() + durationMs
            svc.startPickerPoll()
        }
    }
}
