package com.freedomfighter.readersnight

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunset from a place and a day, with the Almanac for Computers (1990) formula: within a
 * minute or two anywhere people live, nothing fetched. Null where the sun does not set that
 * day (far north or south).
 */
object Sun {
    private const val ZENITH = 90.833   // the sun's centre, refraction included

    /** Minutes after local midnight of the sunset on [day] (any time of that day), in [zone]. */
    fun sunsetMinutes(lat: Double, lon: Double, day: Calendar, zone: TimeZone = day.timeZone): Int? {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = n + ((18.0 - lngHour) / 24.0)
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sinD(m) + 0.020 * sinD(2 * m) + 282.634
        l = norm(l, 360.0)
        var ra = norm(atanD(0.91764 * tanD(l)), 360.0)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15.0
        val sinDec = 0.39782 * sinD(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosD(ZENITH) - sinDec * sinD(lat)) / (cosDec * cosD(lat))
        if (cosH > 1 || cosH < -1) return null
        val h = acosD(cosH) / 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        val ut = norm(tt - lngHour, 24.0)
        val noonOfDay = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val offsetMin = zone.getOffset(noonOfDay.timeInMillis) / 60000
        val local = Math.floorMod((ut * 60).toInt() + offsetMin, 24 * 60)
        return local
    }

    private fun norm(v: Double, period: Double): Double { var x = v % period; if (x < 0) x += period; return x }
    private fun sinD(d: Double) = sin(Math.toRadians(d))
    private fun cosD(d: Double) = cos(Math.toRadians(d))
    private fun tanD(d: Double) = tan(Math.toRadians(d))
    private fun atanD(x: Double) = Math.toDegrees(atan(x))
    private fun acosD(x: Double) = Math.toDegrees(acos(x))
}
