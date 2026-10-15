package com.freedomfighter.readersnight

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import com.freedomfighter.readersnight.widget.NightWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the filter does to the screen, as chosen in the app. */
data class Options(val gray: Boolean = true, val warm: Boolean = true, val dim: Int = Filter.DIM_MEDIUM) {
    val nothing: Boolean get() = !gray && !warm && dim == 0
}

/**
 * The filter is three of Android's own display settings driven together: colour correction
 * set to grayscale, Night Light at the warmest temperature the phone accepts, and extra dim.
 * Turning it on remembers what each setting was; turning it off puts every one back.
 * All of it needs WRITE_SECURE_SETTINGS, which only `adb shell pm grant` can give.
 *
 * Without that permission the filter falls back to a veil: a translucent layer drawn over
 * every app by [VeilService] (the "draw over other apps" permission, given on the phone),
 * which adds the warm tint and the dimming but cannot turn colours gray. The moment the
 * full permission is there, [sync] drops the veil and drives the settings instead.
 */
object Filter {
    const val DIM_LIGHT = 30
    const val DIM_MEDIUM = 60
    const val DIM_STRONG = 90

    private const val GRAY_ON = "accessibility_display_daltonizer_enabled"
    private const val GRAY_MODE = "accessibility_display_daltonizer"
    private const val NIGHT_ON = "night_display_activated"
    private const val NIGHT_TEMP = "night_display_color_temperature"
    private const val NIGHT_AUTO = "night_display_auto_mode"
    private const val DIM_ON = "reduce_bright_colors_activated"
    private const val DIM_LEVEL = "reduce_bright_colors_level"

    private val GRAY_KEYS = listOf(GRAY_MODE, GRAY_ON)
    // the schedule is switched off with the tint, or sunrise would end it behind our back
    private val NIGHT_KEYS = listOf(NIGHT_AUTO, NIGHT_TEMP, NIGHT_ON)
    private val DIM_KEYS = listOf(DIM_LEVEL, DIM_ON)
    private val ALL_KEYS = GRAY_KEYS + NIGHT_KEYS + DIM_KEYS

    /** The system's extra dim exists from Android 12; the veil dims on every version. */
    val dimAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val _version = MutableStateFlow(0)
    /** Bumped at every change of state or options; screens re-read on it. */
    val version: StateFlow<Int> = _version

    private fun sp(context: Context): SharedPreferences = context.getSharedPreferences("filter", Context.MODE_PRIVATE)

    /** The full filter: the permission given from a computer. */
    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** The veil: the "draw over other apps" permission, given on the phone. */
    fun veilAllowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** True when the switch can do something, fully or as a veil. */
    fun canAct(context: Context): Boolean = allowed(context) || veilAllowed(context)

    /** True while the on state is held by the veil rather than by the settings. */
    fun veiled(context: Context): Boolean = sp(context).getBoolean("veil", false)

    fun isOn(context: Context): Boolean = sp(context).getBoolean("on", false)

    fun options(context: Context): Options = sp(context).let {
        Options(it.getBoolean("gray", true), it.getBoolean("warm", true), it.getInt("dim", DIM_MEDIUM))
    }

    /** What is really applied: the veil has no grayscale; the settings have no dimming before Android 12. */
    fun effective(context: Context): Options = options(context).let { o ->
        if (!allowed(context)) o.copy(gray = false) else if (dimAvailable) o else o.copy(dim = 0)
    }

    fun setOptions(context: Context, o: Options) {
        sp(context).edit().putBoolean("gray", o.gray).putBoolean("warm", o.warm).putInt("dim", o.dim).apply()
        if (isOn(context)) apply(context)
        changed(context)
    }

    /** The next dimming in the cycle off → light → medium → strong → off. */
    fun nextDim(dim: Int): Int = when {
        dim <= 0 -> DIM_LIGHT
        dim <= DIM_LIGHT -> DIM_MEDIUM
        dim <= DIM_MEDIUM -> DIM_STRONG
        else -> 0
    }

    /** The dimming moved one step; shown at once while the filter is on. */
    fun cycleDim(context: Context) {
        val o = options(context)
        setOptions(context, o.copy(dim = nextDim(o.dim)))
    }

    /** False when neither permission is there: the caller sends the user to the app. */
    fun toggle(context: Context): Boolean {
        if (!canAct(context)) return false
        return if (isOn(context)) { turnOff(context); true } else turnOn(context)
    }

    /** False when nothing could be done (no permission at all). */
    fun turnOn(context: Context): Boolean {
        if (isOn(context)) return true
        if (allowed(context)) {
            val cr = context.contentResolver
            val e = sp(context).edit()
            ALL_KEYS.forEach { k ->
                // Android hides some of these from apps (the extra-dim pair): written, never read
                val v = runCatching { Settings.Secure.getString(cr, k) }
                e.putBoolean("unknown_$k", v.isFailure)
                val was = v.getOrNull()
                if (was == null) e.remove("prev_$k") else e.putString("prev_$k", was)
            }
            e.putBoolean("on", true).putBoolean("veil", false).apply()
            write(context)
        } else {
            if (!veilAllowed(context)) return false
            sp(context).edit().putBoolean("on", true).putBoolean("veil", true).apply()
            if (!VeilService.start(context)) { sp(context).edit().putBoolean("on", false).putBoolean("veil", false).apply(); return false }
        }
        changed(context)
        return true
    }

    fun turnOff(context: Context) {
        if (!isOn(context)) return
        if (veiled(context)) VeilService.stop(context)
        else if (allowed(context)) write(context, on = false)
        sp(context).edit().putBoolean("on", false).putBoolean("veil", false).apply()
        changed(context)
    }

    /** The options changed while on: the settings or the veil follow. */
    private fun apply(context: Context) {
        if (veiled(context)) VeilService.start(context)
        else if (allowed(context)) write(context)
    }

    /**
     * Two things to follow: the grayscale switched off in the system settings while we were
     * on, and the full permission arriving while the veil was holding the filter (then the
     * veil is dropped and the settings take over, so the screen turns gray without a tap).
     */
    fun sync(context: Context) {
        if (!isOn(context)) return
        if (veiled(context)) {
            if (allowed(context)) {
                VeilService.stop(context)
                sp(context).edit().putBoolean("on", false).putBoolean("veil", false).apply()
                turnOn(context)
            } else if (!veilAllowed(context)) turnOff(context)
            return
        }
        if (!allowed(context)) return
        val o = options(context)
        val cr = context.contentResolver
        val undone = runCatching {
            when {
                o.gray -> Settings.Secure.getString(cr, GRAY_ON) != "1"
                o.warm -> Settings.Secure.getString(cr, NIGHT_ON) != "1"
                else -> false
            }
        }.getOrDefault(false)
        if (undone) turnOff(context)
    }

    /** After a reboot the settings are still there, the veil is not: it is drawn again. */
    fun restoreAfterBoot(context: Context) {
        if (isOn(context) && veiled(context)) {
            if (veilAllowed(context)) VeilService.start(context)
            else sp(context).edit().putBoolean("on", false).putBoolean("veil", false).apply()
        }
    }

    /**
     * Each part either set, or put back as it was when the option is off.
     *
     * The order matters: Android computes the tint from the temperature only when the colour
     * correction switch changes (or at boot), not when the temperature itself is written. So
     * the temperature goes first and the grayscale last, and when the grayscale switch does not
     * move by itself it is nudged, see [nudge].
     */
    private fun write(context: Context, on: Boolean = true) {
        val o = options(context)
        val cr = context.contentResolver
        val tempBefore = read(context, NIGHT_TEMP)
        val grayBefore = read(context, GRAY_ON)
        if (on && o.warm) {
            Settings.Secure.putString(cr, NIGHT_AUTO, "0")
            Settings.Secure.putString(cr, NIGHT_TEMP, warmest().toString())
            Settings.Secure.putString(cr, NIGHT_ON, "1")
        } else NIGHT_KEYS.forEach { restore(context, it) }
        if (dimAvailable) {
            if (on && o.dim > 0) {
                Settings.Secure.putString(cr, DIM_LEVEL, o.dim.toString())
                Settings.Secure.putString(cr, DIM_ON, "1")
            } else DIM_KEYS.forEach { restore(context, it) }
        }
        if (on && o.gray) {
            Settings.Secure.putString(cr, GRAY_MODE, "0")   // 0 = monochromacy
            Settings.Secure.putString(cr, GRAY_ON, "1")
        } else GRAY_KEYS.forEach { restore(context, it) }
        if (read(context, NIGHT_TEMP) != tempBefore && read(context, GRAY_ON) == grayBefore) nudge(context)
    }

    /**
     * Makes Android compute the tint again: the colour correction switch is written to a
     * different value. Unset and "0" both mean off, so going from one to the other shows
     * nothing; while the grayscale is on it goes off and straight back on.
     */
    private fun nudge(context: Context) {
        val cr = context.contentResolver
        when (read(context, GRAY_ON)) {
            "1" -> { Settings.Secure.putString(cr, GRAY_ON, "0"); Settings.Secure.putString(cr, GRAY_ON, "1") }
            null -> Settings.Secure.putString(cr, GRAY_ON, "0")
            else -> Settings.Secure.putString(cr, GRAY_ON, null)
        }
    }

    private fun read(context: Context, key: String): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()

    /** Back to what it was; a setting we could not read is switched off, its level left alone. */
    private fun restore(context: Context, key: String) {
        val sp = sp(context)
        val cr = context.contentResolver
        if (!sp.getBoolean("unknown_$key", false)) Settings.Secure.putString(cr, key, sp.getString("prev_$key", null))
        else if (key == DIM_ON) Settings.Secure.putString(cr, key, "0")
    }

    /** The lowest colour temperature the phone accepts; anything lower is clamped to it anyway. */
    private fun warmest(): Int {
        val r = Resources.getSystem()
        val id = r.getIdentifier("config_nightDisplayColorTemperatureMin", "integer", "android")
        return if (id != 0) runCatching { r.getInteger(id) }.getOrDefault(1000) else 1000
    }

    fun changed(context: Context) {
        val ctx = context.applicationContext
        _version.value++
        NightWidget.refresh(ctx)
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, NightTileService::class.java)) }
        ctx.contentResolver.notifyChange(StateProvider.STATE, null)
    }
}
