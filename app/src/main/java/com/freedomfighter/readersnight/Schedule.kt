package com.freedomfighter.readersnight

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import java.util.Calendar

/**
 * The filter by itself: on at a chosen time or at sunset, off at a chosen time. Two exact
 * alarms, armed again after each one fires, after a boot, an update or a clock change. A
 * tap in between is not undone: the schedule only acts at its two moments.
 *
 * Sunset needs a place: the phone's last known position (coarse), kept in the preferences
 * so it serves even when no position is available later. Without any, the chosen time is
 * used and the app says so.
 */
object Schedule {
    const val ACTION_ON = "com.freedomfighter.readersnight.SCHEDULE_ON"
    const val ACTION_OFF = "com.freedomfighter.readersnight.SCHEDULE_OFF"
    const val DEFAULT_ON = 21 * 60
    const val DEFAULT_OFF = 7 * 60

    data class State(val enabled: Boolean, val sunset: Boolean, val onMinutes: Int, val offMinutes: Int, val lat: Double?, val lon: Double?) {
        val located: Boolean get() = lat != null && lon != null
    }

    private fun sp(context: Context) = context.getSharedPreferences("filter", Context.MODE_PRIVATE)

    fun state(context: Context): State = sp(context).let {
        State(
            it.getBoolean("sched", false), it.getBoolean("sched_sunset", false),
            it.getInt("sched_on", DEFAULT_ON), it.getInt("sched_off", DEFAULT_OFF),
            if (it.contains("lat")) it.getFloat("lat", 0f).toDouble() else null,
            if (it.contains("lon")) it.getFloat("lon", 0f).toDouble() else null
        )
    }

    fun set(context: Context, enabled: Boolean? = null, sunset: Boolean? = null, onMinutes: Int? = null, offMinutes: Int? = null) {
        val e = sp(context).edit()
        enabled?.let { e.putBoolean("sched", it) }
        sunset?.let { e.putBoolean("sched_sunset", it) }
        onMinutes?.let { e.putInt("sched_on", it) }
        offMinutes?.let { e.putInt("sched_off", it) }
        e.apply()
        if (sunset == true) locate(context)
        arm(context)
        Filter.changed(context)
    }

    /** Today's sunset in minutes, from the kept position; null without one or where the sun does not set. */
    fun sunsetToday(context: Context, day: Calendar = Calendar.getInstance()): Int? {
        val s = state(context)
        if (!s.located) return null
        return Sun.sunsetMinutes(s.lat!!, s.lon!!, day)
    }

    /** The minute the filter goes on, on [day]: sunset when chosen and known, else the time. */
    fun onMinutes(context: Context, day: Calendar = Calendar.getInstance()): Int {
        val s = state(context)
        return if (s.sunset) sunsetToday(context, day) ?: s.onMinutes else s.onMinutes
    }

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The position the phone last knew, kept so the sunset follows the phone; nothing is asked of the network. */
    @SuppressLint("MissingPermission")
    fun refreshPosition(context: Context): Boolean {
        if (!hasLocationPermission(context)) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        val best = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return false
        sp(context).edit().putFloat("lat", best.latitude.toFloat()).putFloat("lon", best.longitude.toFloat()).apply()
        return true
    }

    /** [refreshPosition], and when the phone keeps none, one cheap request. */
    @SuppressLint("MissingPermission")
    fun locate(context: Context) {
        if (refreshPosition(context)) return
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        // no position kept by the phone: ask once, cheaply
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> return
            }
            runCatching {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    if (loc != null) {
                        sp(context).edit().putFloat("lat", loc.latitude.toFloat()).putFloat("lon", loc.longitude.toFloat()).apply()
                        arm(context)
                        Filter.changed(context)
                    }
                }
            }
        }
    }

    /** The next two moments, as exact alarms; nothing when the schedule is off. */
    fun arm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val onPi = pending(context, ACTION_ON)
        val offPi = pending(context, ACTION_OFF)
        am.cancel(onPi); am.cancel(offPi)
        val s = state(context)
        if (!s.enabled) return
        if (s.sunset) refreshPosition(context)
        val now = Calendar.getInstance()
        val nextOn = nextAt(now) { day -> onMinutes(context, day) }
        val nextOff = nextAt(now) { s.offMinutes }
        exact(am, nextOn, onPi)
        exact(am, nextOff, offPi)
    }

    private fun exact(am: AlarmManager, at: Long, pi: PendingIntent) {
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** The first moment after [now] when the clock reads the minute [minutesOf] gives for that day. */
    private fun nextAt(now: Calendar, minutesOf: (Calendar) -> Int): Long {
        for (d in 0..2) {
            val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, d) }
            val m = minutesOf(day)
            day.set(Calendar.HOUR_OF_DAY, m / 60); day.set(Calendar.MINUTE, m % 60); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0)
            if (day.timeInMillis > now.timeInMillis + 1000) return day.timeInMillis
        }
        return now.timeInMillis + 24 * 3600 * 1000L
    }

    private fun pending(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, if (action == ACTION_ON) 20 else 21,
            Intent(context, ScheduleReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Called when an alarm fires: the filter moves, then the next alarms are armed. */
    fun fire(context: Context, action: String) {
        if (state(context).enabled) {
            if (action == ACTION_ON) Filter.turnOn(context) else Filter.turnOff(context)
        }
        arm(context)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Schedule.ACTION_ON || action == Schedule.ACTION_OFF) Schedule.fire(context, action)
    }
}

/** Alarms do not survive a reboot, nor an update; the veil neither. Both are put back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Schedule.arm(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Filter.restoreAfterBoot(context)
    }
}
