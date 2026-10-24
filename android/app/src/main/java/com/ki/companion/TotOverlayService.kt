package com.ki.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
        return START_STICKY
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        overlayView = TotBuddyView(this)
        overlayParams = WindowManager.LayoutParams(
            (88 * density).toInt(),
            (112 * density).toInt(),
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
        private const val CHANNEL_ID = "tot_floating_buddy"
        private const val NOTIFICATION_ID = 7421

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TotOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TotOverlayService::class.java))
        }
    }
}

private class TotBuddyView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF49445C.toInt() }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8CFFF.toInt() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33C6A9FF }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF292536.toInt() }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2262930.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F4F6.toInt()
        textSize = 10f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF292536.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val phrases = listOf("Hey! I'm Tot!", "Ki & me = team!", "Need help?", "Robot stretch!")
    private var phrase: String? = "Hi! I'm Tot!"
    private var phraseUntil = System.currentTimeMillis() + 3_500
    private var phraseIndex = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var startedAt = System.currentTimeMillis()
    var onDragged: ((Int, Int) -> Unit)? = null
    var onPositionChanged: (() -> Unit)? = null

    private val animation = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 50)
        }
    }
    private val smile = Path()

    init {
        contentDescription = "Tot, your animated phone buddy. Drag me anywhere. Tap me for a hello."
        handler.post(animation)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val bob = sin((now - startedAt) / 260.0).toFloat() * 2.5f * density
        val centerX = width / 2f
        val bodyTop = 43f * density + bob
        val bodyWidth = 43f * density
        val bodyHeight = 45f * density

        val currentPhrase = phrase
        if (currentPhrase != null && now < phraseUntil) {
            canvas.drawRoundRect(
                2f * density,
                2f * density,
                width - 2f * density,
                34f * density,
                12f * density,
                12f * density,
                bubblePaint,
            )
            canvas.drawText(currentPhrase, centerX, 21f * density, textPaint)
        } else {
            phrase = null
        }

        val bodyLeft = centerX - bodyWidth / 2f
        val bodyRight = centerX + bodyWidth / 2f
        val bodyBottom = bodyTop + bodyHeight
        canvas.drawRoundRect(
            bodyLeft - 7f * density,
            bodyTop - 7f * density,
            bodyRight + 7f * density,
            bodyBottom + 7f * density,
            17f * density,
            17f * density,
            glowPaint,
        )
        canvas.drawRoundRect(bodyLeft, bodyTop, bodyRight, bodyBottom, 13f * density, 13f * density, bodyPaint)
        canvas.drawRoundRect(
            bodyLeft + 5f * density,
            bodyTop + 7f * density,
            bodyRight - 5f * density,
            bodyTop + 34f * density,
            9f * density,
            9f * density,
            facePaint,
        )
        canvas.drawRoundRect(
            bodyLeft + 8f * density,
            bodyTop - 8f * density,
            bodyLeft + 12f * density,
            bodyTop + 1f * density,
            2f * density,
            2f * density,
            bodyPaint,
        )
        canvas.drawCircle(bodyLeft + 10f * density, bodyTop - 9f * density, 2.5f * density, facePaint)

        val blink = now % 3_800 in 0L..130L
        if (blink) {
            canvas.drawLine(bodyLeft + 13f * density, bodyTop + 20f * density, bodyLeft + 17f * density, bodyTop + 20f * density, mouthPaint)
            canvas.drawLine(bodyLeft + 26f * density, bodyTop + 20f * density, bodyLeft + 30f * density, bodyTop + 20f * density, mouthPaint)
        } else {
            canvas.drawCircle(bodyLeft + 15f * density, bodyTop + 20f * density, 2f * density, eyePaint)
            canvas.drawCircle(bodyLeft + 28f * density, bodyTop + 20f * density, 2f * density, eyePaint)
        }
        smile.reset()
        smile.moveTo(bodyLeft + 17f * density, bodyTop + 27f * density)
        smile.quadTo(bodyLeft + 21.5f * density, bodyTop + 32f * density, bodyLeft + 26f * density, bodyTop + 27f * density)
        canvas.drawPath(smile, mouthPaint)
        val legBounce = sin((now - startedAt) / 160.0).toFloat() * 2f * density
        canvas.drawRoundRect(
            bodyLeft + 6f * density,
            bodyBottom - 1f * density + legBounce,
            bodyLeft + 18f * density,
            bodyBottom + 5f * density + legBounce,
            3f * density,
            3f * density,
            bodyPaint,
        )
        canvas.drawRoundRect(
            bodyLeft + 25f * density,
            bodyBottom - 1f * density - legBounce,
            bodyLeft + 37f * density,
            bodyBottom + 5f * density - legBounce,
            3f * density,
            3f * density,
            bodyPaint,
        )
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
                    phrase = phrases[phraseIndex % phrases.size]
                    phraseIndex += 1
                    phraseUntil = System.currentTimeMillis() + 2_800
                    announceForAccessibility(phrase)
                    performClick()
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
