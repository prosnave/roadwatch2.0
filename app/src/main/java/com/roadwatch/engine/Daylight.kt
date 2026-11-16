package com.roadwatch.engine

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Offline night detection (no network, no extra dependency). Driving at night is when drivers
 * can't see bumps, so the engine warns earlier.
 */
object Daylight {
    /** Solar elevation in degrees (low-precision Astronomical Almanac formula, ~0.01° accuracy). */
    fun solarElevationDeg(epochMs: Long, latDeg: Double, lngDeg: Double): Double {
        val rad = PI / 180.0
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val n = jd - 2_451_545.0
        val meanLon = (280.460 + 0.9856474 * n).mod(360.0)
        val g = ((357.528 + 0.9856003 * n).mod(360.0)) * rad
        val lambda = (meanLon + 1.915 * sin(g) + 0.020 * sin(2 * g)) * rad
        val eps = (23.439 - 0.0000004 * n) * rad
        val decl = asin(sin(eps) * sin(lambda))
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val gmstHours = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val lst = ((gmstHours * 15.0 + lngDeg).mod(360.0)) * rad
        val ha = lst - ra
        val lat = latDeg * rad
        return Math.toDegrees(asin(sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha)))
    }

    /** Headlights-on territory: sun more than ~4° below the horizon (dusk/dawn counted as dark). */
    fun isNight(epochMs: Long, latDeg: Double, lngDeg: Double): Boolean =
        solarElevationDeg(epochMs, latDeg, lngDeg) < -4.0
}
