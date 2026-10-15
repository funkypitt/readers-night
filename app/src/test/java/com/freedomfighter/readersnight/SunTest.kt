package com.freedomfighter.readersnight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

class SunTest {
    private fun day(y: Int, m: Int, d: Int, zone: String) = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { set(y, m - 1, d, 12, 0, 0) }

    @Test fun lausanneMidOctober() {
        // 15 October 2026, Lausanne (46.52 N, 6.63 E), CEST: sunset 18:47 (NOAA)
        val m = Sun.sunsetMinutes(46.52, 6.63, day(2026, 10, 15, "Europe/Zurich"))!!
        assertTrue("$m", abs(m - (18 * 60 + 47)) <= 3)
    }

    @Test fun lausanneMidsummer() {
        // 21 June, 21:30 CEST (NOAA)
        val m = Sun.sunsetMinutes(46.52, 6.63, day(2026, 6, 21, "Europe/Zurich"))!!
        assertTrue("$m", abs(m - (21 * 60 + 30)) <= 3)
    }

    @Test fun newYorkWinter() {
        // 21 December, New York (40.71 N, 74.01 W), EST: 16:31 (NOAA)
        val m = Sun.sunsetMinutes(40.71, -74.01, day(2026, 12, 21, "America/New_York"))!!
        assertTrue("$m", abs(m - (16 * 60 + 31)) <= 3)
    }

    @Test fun sydneySummer() {
        // 21 December, Sydney (33.87 S, 151.21 E), AEDT: 20:05 (NOAA)
        val m = Sun.sunsetMinutes(-33.87, 151.21, day(2026, 12, 21, "Australia/Sydney"))!!
        assertTrue("$m", abs(m - (20 * 60 + 5)) <= 3)
    }

    @Test fun polarNight() {
        // Tromsø in late December: no sunset
        assertNull(Sun.sunsetMinutes(69.65, 18.96, day(2026, 12, 21, "Europe/Oslo")))
    }

    @Test fun equator() {
        // Singapore, 21 March: 19:15 (NOAA)
        val m = Sun.sunsetMinutes(1.35, 103.82, day(2026, 3, 21, "Asia/Singapore"))!!
        assertTrue("$m", abs(m - (19 * 60 + 15)) <= 3)
    }
}
