package com.ki.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.sin

class TotOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TotBuddyView
    private lateinit var overlayParams: WindowManager.LayoutParams
    private var overlayAttached = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, false)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }
        val requestedActivity = intent
            ?.takeIf { it.action == ACTION_SET_ACTIVITY }
            ?.getStringExtra(EXTRA_ACTIVITY)
            ?.takeIf { it in setOf("idle", "listening", "thinking", "speaking", "happy", "curious", "sleepy") }
        if (requestedActivity != null && ::overlayView.isInitialized && overlayAttached) {
            overlayView.setActivity(requestedActivity)
            return START_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, true)
            .apply()

        if (!::overlayView.isInitialized) {
            showOverlay()
        }
        requestedActivity?.let(overlayView::setActivity)
        return START_STICKY
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        overlayView = TotBuddyView(this) { openCompanion() }
        overlayParams = WindowManager.LayoutParams(
            (88 * density).toInt(),
            (98 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(KEY_X, (16 * density).toInt())
                .coerceIn(0, (resources.displayMetrics.widthPixels - width).coerceAtLeast(0))
            y = preferences.getInt(KEY_Y, (150 * density).toInt())
                .coerceIn(0, (resources.displayMetrics.heightPixels - height).coerceAtLeast(0))
        }
        overlayView.onPositionChanged = {
            preferences.edit().putInt(KEY_X, overlayParams.x).putInt(KEY_Y, overlayParams.y).apply()
        }
        overlayView.onDragged = { deltaX, deltaY ->
            val bounds = resources.displayMetrics
            overlayParams.x = (overlayParams.x + deltaX).coerceIn(
                0,
                (bounds.widthPixels - overlayParams.width).coerceAtLeast(0),
            )
            overlayParams.y = (overlayParams.y + deltaY).coerceIn(
                0,
                (bounds.heightPixels - overlayParams.height).coerceAtLeast(0),
            )
            windowManager.updateViewLayout(overlayView, overlayParams)
        }
        windowManager.addView(overlayView, overlayParams)
        overlayAttached = true
    }

    private fun openCompanion() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "Could not open Tot chat.", Toast.LENGTH_SHORT).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, "Android blocked opening Tot chat.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tot floating buddy",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Tot available as a draggable buddy over your other apps."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopTot = PendingIntent.getService(
            this,
            1,
            Intent(this, TotOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tot is with you")
            .setContentText("Drag Tot anywhere. Tap here to open Tot.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide Tot", stopTot)
            .build()
    }

    override fun onDestroy() {
        if (::overlayView.isInitialized && overlayAttached) {
            windowManager.removeView(overlayView)
            overlayAttached = false
        }
        if (::overlayView.isInitialized) overlayView.dispose()
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFERENCES = "ki_companion"
        const val KEY_ENABLED = "tot_overlay_enabled"
        const val KEY_X = "tot_overlay_x"
        const val KEY_Y = "tot_overlay_y"
        private const val ACTION_STOP = "com.ki.companion.action.STOP_TOT"
        private const val ACTION_SET_ACTIVITY = "com.ki.companion.action.SET_TOT_ACTIVITY"
        private const val EXTRA_ACTIVITY = "activity"
        private const val CHANNEL_ID = "tot_floating_buddy"
        private const val NOTIFICATION_ID = 7421

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TotOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TotOverlayService::class.java))
        }

        fun setActivity(context: Context, activity: String) {
            if (
                activity !in setOf("idle", "listening", "thinking", "speaking", "happy", "curious", "sleepy") ||
                !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false)
            ) {
                return
            }
            context.startService(
                Intent(context, TotOverlayService::class.java)
                    .setAction(ACTION_SET_ACTIVITY)
                    .putExtra(EXTRA_ACTIVITY, activity),
            )
        }
    }
}

private class TotBuddyView(context: Context, private val onTap: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303236.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF414449.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0F0F0.toInt() }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0F0F0.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0A64B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33E0A64B }
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var startedAt = System.currentTimeMillis()
    private var activity = "idle"
    private var activityUntil = 0L
    var onDragged: ((Int, Int) -> Unit)? = null
    var onPositionChanged: (() -> Unit)? = null

    private val animation = object : Runnable {
        override fun run() {
            if (activity == "happy" && System.currentTimeMillis() >= activityUntil) {
                activity = "idle"
            }
            invalidate()
            handler.postDelayed(this, 50)
        }
    }
    init {
        contentDescription = "Tot, animated phone buddy. Drag to move."
        handler.post(animation)
    }

    fun setActivity(activity: String) {
        this.activity = activity
        activityUntil = if (activity == "happy") System.currentTimeMillis() + 1_400 else 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val pulse = (0.5 + 0.5 * sin((now - startedAt) / 240.0)).toFloat()
        val bob = sin((now - startedAt) / 380.0).toFloat() * 1.5f * density
        val panelLeft = 4f * density
        val panelTop = 10f * density + bob
        val panelRight = width - 4f * density
        val panelBottom = height - 24f * density + bob
        val panelWidth = panelRight - panelLeft
        val panelHeight = panelBottom - panelTop
        val centerX = width / 2f
        canvas.drawRoundRect(panelLeft, panelTop, panelRight, panelBottom, 2f * density, 2f * density, panelPaint)
        canvas.drawRoundRect(panelLeft, panelTop, panelRight, panelBottom, 2f * density, 2f * density, borderPaint)

        val eyeY = panelTop + panelHeight * 0.42f
        val thinkingShift = if (activity == "thinking") sin((now - startedAt) / 420.0).toFloat() * 2f * density else 0f
        val eyeOffset = panelWidth * 0.23f
        val eyeRadius = 2.2f * density
        val blink = now % 4_300 in 0L..120L
        if (activity == "sleepy") {
            canvas.drawLine(centerX - eyeOffset - eyeRadius, eyeY, centerX - eyeOffset + eyeRadius, eyeY, mouthPaint)
            canvas.drawLine(centerX + eyeOffset - eyeRadius, eyeY, centerX + eyeOffset + eyeRadius, eyeY, mouthPaint)
        } else if (blink) {
            canvas.drawLine(centerX - eyeOffset + thinkingShift - eyeRadius, eyeY, centerX - eyeOffset + thinkingShift + eyeRadius, eyeY, mouthPaint)
            canvas.drawLine(centerX + eyeOffset + thinkingShift - eyeRadius, eyeY, centerX + eyeOffset + thinkingShift + eyeRadius, eyeY, mouthPaint)
        } else {
            canvas.drawCircle(centerX - eyeOffset + thinkingShift, eyeY, eyeRadius, eyePaint)
            canvas.drawCircle(centerX + eyeOffset + thinkingShift, eyeY, eyeRadius, eyePaint)
        }
        val mouthCenterY = panelTop + panelHeight * 0.64f
        when (activity) {
            "speaking" -> {
                val mouthWidth = panelWidth * 0.28f
                val mouthHeight = (2f + pulse * 7f) * density
                canvas.drawRoundRect(
                    centerX - mouthWidth / 2f,
                    mouthCenterY - mouthHeight / 2f,
                    centerX + mouthWidth / 2f,
                    mouthCenterY + mouthHeight / 2f,
                    3f * density,
                    3f * density,
                    mouthPaint,
                )
            }
            "listening" -> {
                val mouthSize = (3f + pulse * 2f) * density
                canvas.drawOval(
                    centerX - mouthSize * 0.6f,
                    mouthCenterY - mouthSize / 2f,
                    centerX + mouthSize * 0.6f,
                    mouthCenterY + mouthSize / 2f,
                    mouthPaint,
                )
            }
            "curious" -> {
                val mouthSize = (4f + pulse * 1.5f) * density
                canvas.drawCircle(centerX, mouthCenterY, mouthSize / 2f, mouthPaint)
            }
            "happy" -> {
                val smileRect = RectF(
                    centerX - panelWidth * 0.15f,
                    mouthCenterY - 5f * density,
                    centerX + panelWidth * 0.15f,
                    mouthCenterY + 4f * density,
                )
                mouthPaint.style = Paint.Style.STROKE
                mouthPaint.strokeWidth = 1.5f * density
                canvas.drawArc(smileRect, 15f, 150f, false, mouthPaint)
                mouthPaint.style = Paint.Style.FILL
            }
            else -> {
                val mouthWidth = panelWidth * 0.28f
                canvas.drawLine(
                    centerX - mouthWidth / 2f,
                    mouthCenterY,
                    centerX + mouthWidth / 2f,
                    mouthCenterY,
                    mouthPaint,
                )
            }
        }
        val ringCenterY = height - 9f * density
        val ringColor = if (activity == "listening") 0xFF8FE8FF.toInt() else 0xFFE0A64B.toInt()
        ringGlowPaint.color = if (activity == "listening") 0x338FE8FF else 0x33E0A64B
        ringPaint.color = ringColor
        canvas.drawCircle(centerX, ringCenterY, (4f + pulse * 1.5f) * density, ringGlowPaint)
        canvas.drawCircle(centerX, ringCenterY, 3.2f * density, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - lastRawX
                val deltaY = event.rawY - lastRawY
                if (!dragging &&
                    (kotlin.math.abs(event.rawX - downRawX) > 8f * density ||
                        kotlin.math.abs(event.rawY - downRawY) > 8f * density)
                ) {
                    dragging = true
                }
                if (dragging) onDragged?.invoke(deltaX.toInt(), deltaY.toInt())
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    onPositionChanged?.invoke()
                } else {
                    announceForAccessibility("Opening Tot chat.")
                    performClick()
                    onTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun dispose() {
        handler.removeCallbacks(animation)
    }
}
