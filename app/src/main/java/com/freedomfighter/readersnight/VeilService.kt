package com.freedomfighter.readersnight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The fallback when the full permission is missing: a translucent layer over every app, a
 * warm colour for the tint and black for the dimming. Touches go through it. Android keeps
 * such a layer out of the status bar, the notification shade and the keyboard, caps its
 * opacity at 0.8 (above that, touches would be blocked), and cannot turn colours gray this
 * way: only the real settings do that.
 *
 * A foreground service, so the layer outlives the app; its notification is quiet.
 */
class VeilService : Service() {
    companion object {
        private const val CHANNEL = "veil"
        private const val NOTE = 1
        private const val ACTION_STOP = "com.freedomfighter.readersnight.VEIL_STOP"

        @Volatile var running = false
            private set

        /** Starts the layer or redraws it with the current options. False when Android refused. */
        fun start(context: Context): Boolean = runCatching {
            val i = Intent(context, VeilService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            true
        }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VeilService::class.java)) }
        }
    }

    private var view: VeilView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Filter.turnOff(this); return START_NOT_STICKY }
        if (!Filter.veilAllowed(this)) { stopSelf(); return START_NOT_STICKY }
        note()
        val o = Filter.effective(this)
        val v = view ?: VeilView(this).also { attach(it); view = it }
        v.set(o)
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
        super.onDestroy()
    }

    private fun attach(v: View) {
        val wm = getSystemService(WindowManager::class.java)
        // bigger than the screen in every direction, so it reaches under the bars and the cutout
        val m = resources.displayMetrics
        val side = maxOf(m.widthPixels, m.heightPixels) * 2
        val p = WindowManager.LayoutParams(
            side, side,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER
        // the most Android lets through to the touches underneath
        p.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, p)
    }

    private fun note() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.veil_channel), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val off = PendingIntent.getService(this, 11, Intent(this, VeilService::class.java).setAction(ACTION_STOP), flags)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.veil_note))
            .setContentText(getString(R.string.veil_note_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.turn_off), off).build())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTE, n)
    }
}

/** The layer itself: the warm colour first, then the black of the dimming. */
class VeilView(context: Context) : View(context) {
    private var warm = 0
    private var dark = 0

    fun set(o: Options) {
        warm = if (o.warm) Color.argb(96, 255, 110, 0) else 0
        dark = when {
            o.dim <= 0 -> 0
            o.dim <= Filter.DIM_LIGHT -> Color.argb(90, 0, 0, 0)
            o.dim <= Filter.DIM_MEDIUM -> Color.argb(150, 0, 0, 0)
            else -> Color.argb(215, 0, 0, 0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (warm != 0) canvas.drawColor(warm)
        if (dark != 0) canvas.drawColor(dark)
    }
}
