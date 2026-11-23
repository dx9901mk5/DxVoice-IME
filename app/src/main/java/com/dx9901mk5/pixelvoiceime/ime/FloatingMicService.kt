package com.dx9901mk5.pixelvoiceime.ime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.R
import com.dx9901mk5.pixelvoiceime.data.AppThemeMode
import com.dx9901mk5.pixelvoiceime.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating microphone shortcut overlay shown while other IMEs are active.
 * Remembers separate positions for foldable outer (cover) vs inner/tablet screens and supports custom alpha.
 */
class FloatingMicService : Service() {

    private lateinit var app: PixelVoiceApp
    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ballView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isImeActiveForeground: Boolean = false
    private var isExternalKeyboardVisible: Boolean = false

    private val imeVisibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_IME_VISIBILITY -> {
                    isImeActiveForeground = intent.getBooleanExtra(EXTRA_IME_VISIBLE, false)
                    updateBallVisibility()
                }
                ACTION_EXTERNAL_IME_VISIBILITY -> {
                    isExternalKeyboardVisible = intent.getBooleanExtra(EXTRA_EXTERNAL_IME_VISIBLE, false)
                    updateBallVisibility()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as PixelVoiceApp
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(ACTION_IME_VISIBILITY)
            addAction(ACTION_EXTERNAL_IME_VISIBILITY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(imeVisibilityReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(imeVisibilityReceiver, filter)
        }

        createOrUpdateOverlay()

        serviceScope.launch {
            app.prefs.config.collectLatest { config ->
                if (!config.floatingBallEnabled || !Settings.canDrawOverlays(this@FloatingMicService)) {
                    stopSelf()
                    return@collectLatest
                }
                applyStyleFromConfig()
                updateBallVisibility()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this) || !app.prefs.config.value.floatingBallEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        createOrUpdateOverlay()
        applyStyleFromConfig()
        updateBallVisibility()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPositionForCurrentScreen(newConfig)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(imeVisibilityReceiver) }
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun isInnerScreen(config: Configuration = resources.configuration): Boolean {
        return config.smallestScreenWidthDp >= 600
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOrUpdateOverlay() {
        if (ballView != null) return
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()
        val padPx = (14 * density).toInt()

        val imageView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            setPadding(padPx, padPx, padPx, padPx)
            elevation = 8 * density
        }

        val inner = isInnerScreen()
        val cfg = app.prefs.config.value
        val startX = if (inner) cfg.floatingBallInnerX else cfg.floatingBallOuterX
        val startY = if (inner) cfg.floatingBallInnerY else cfg.floatingBallOuterY

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false

        imageView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val metrics = resources.displayMetrics
                        params.x = (initialX + dx).coerceIn(0, (metrics.widthPixels - sizePx).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (metrics.heightPixels - sizePx).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(imageView, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val curInner = isInnerScreen()
                        app.prefs.update { cur ->
                            if (curInner) {
                                cur.copy(floatingBallInnerX = params.x, floatingBallInnerY = params.y)
                            } else {
                                cur.copy(floatingBallOuterX = params.x, floatingBallOuterY = params.y)
                            }
                        }
                    } else {
                        launchImeSwitcher()
                    }
                    true
                }

                else -> false
            }
        }

        ballView = imageView
        layoutParams = params
        runCatching { windowManager.addView(imageView, params) }
    }

    private fun applyStyleFromConfig() {
        val view = ballView ?: return
        val cfg = app.prefs.config.value
        val bgColor = if (cfg.appThemeMode == AppThemeMode.ORANGE_CHARCOAL) {
            Color.parseColor("#FF7A00")
        } else {
            Color.parseColor("#0B57D0")
        }
        val strokeColor = if (cfg.appThemeMode == AppThemeMode.ORANGE_CHARCOAL) {
            Color.parseColor("#FFBD73")
        } else {
            Color.parseColor("#A8C7FA")
        }
        val density = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            setStroke((1.5f * density).toInt(), strokeColor)
        }
        view.alpha = cfg.floatingBallAlpha.coerceIn(0.25f, 1.0f)
    }

    private fun applyPositionForCurrentScreen(config: Configuration) {
        val view = ballView ?: return
        val params = layoutParams ?: return
        val inner = isInnerScreen(config)
        val cfg = app.prefs.config.value
        val metrics = resources.displayMetrics
        val sizePx = params.width
        val targetX = if (inner) cfg.floatingBallInnerX else cfg.floatingBallOuterX
        val targetY = if (inner) cfg.floatingBallInnerY else cfg.floatingBallOuterY
        params.x = targetX.coerceIn(0, (metrics.widthPixels - sizePx).coerceAtLeast(0))
        params.y = targetY.coerceIn(0, (metrics.heightPixels - sizePx).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun updateBallVisibility() {
        val visible = if (PixelVoiceAccessibilityService.isRunning()) {
            !isImeActiveForeground && isExternalKeyboardVisible
        } else {
            !isImeActiveForeground
        }
        ballView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun launchImeSwitcher() {
        if (PixelVoiceAccessibilityService.switchToPixelVoiceIme(this)) {
            return
        }
        val intent = Intent(this, ImeSwitchBridgeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    private fun removeOverlay() {
        ballView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        ballView = null
        layoutParams = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PixelVoice 懸浮球捷徑",
                NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PixelVoice 懸浮語音捷徑運作中")
            .setContentText("使用其他鍵盤時點擊懸浮球即可切換語音輸入")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        const val ACTION_IME_VISIBILITY = "com.dx9901mk5.pixelvoiceime.IME_VISIBILITY"
        const val EXTRA_IME_VISIBLE = "ime_visible"
        const val ACTION_EXTERNAL_IME_VISIBILITY = "com.dx9901mk5.pixelvoiceime.EXTERNAL_IME_VISIBILITY"
        const val EXTRA_EXTERNAL_IME_VISIBLE = "external_ime_visible"
        private const val CHANNEL_ID = "pixelvoice_floating_ball"
        private const val NOTIFICATION_ID = 5030

        fun start(context: Context) {
            val intent = Intent(context, FloatingMicService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingMicService::class.java))
        }

        fun notifyImeVisibility(context: Context, visible: Boolean) {
            val intent = Intent(ACTION_IME_VISIBILITY).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_IME_VISIBLE, visible)
            }
            context.sendBroadcast(intent)
        }

        fun notifyExternalKeyboardVisible(context: Context, visible: Boolean) {
            val intent = Intent(ACTION_EXTERNAL_IME_VISIBILITY).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_EXTERNAL_IME_VISIBLE, visible)
            }
            context.sendBroadcast(intent)
        }
    }
}
