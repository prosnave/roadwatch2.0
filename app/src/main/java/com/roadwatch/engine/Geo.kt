package com.roadwatch.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small-area geometry helpers. All functions use a local equirectangular approximation, which is
 * accurate to well under a metre over the ~1 km ranges the alert engine works with.
 */
object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG = PI / 180.0

    /** East/north offset in metres of (lat,lng) relative to (originLat,originLng). */
    fun offsetMeters(originLat: Double, originLng: Double, lat: Double, lng: Double): Pair<Double, Double> {
        val dy = (lat - originLat) * DEG * EARTH_RADIUS_M
        val dx = (lng - originLng) * DEG * EARTH_RADIUS_M * cos(originLat * DEG)
        return dx to dy
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val (dx, dy) = offsetMeters(lat1, lng1, lat2, lng2)
        return sqrt(dx * dx + dy * dy)
    }

    /** Initial bearing 0..360 from point 1 to point 2. */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val (dx, dy) = offsetMeters(lat1, lng1, lat2, lng2)
        return normalize(Math.toDegrees(atan2(dx, dy)))
    }

    /** Point reached by travelling [meters] from the origin along [bearingDeg]. */
    fun project(lat: Double, lng: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val b = bearingDeg * DEG
        val dLat = (meters * cos(b)) / EARTH_RADIUS_M / DEG
        val dLng = (meters * sin(b)) / (EARTH_RADIUS_M * cos(lat * DEG)) / DEG
        return (lat + dLat) to (lng + dLng)
    }

    fun normalize(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Smallest absolute difference between two bearings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(normalize(a) - normalize(b))
        return if (d > 180.0) 360.0 - d else d
    }

    fun compass(deg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((normalize(deg) + 22.5) / 45.0).toInt() % 8]
    }
}
