package com.roadwatch.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maintains a smoothed course-over-ground and a short breadcrumb trail.
 *
 * Why not just use Location.getBearing()? Receivers report garbage bearings at low speed and
 * sometimes none at all. We prefer the receiver bearing when it's trustworthy, otherwise derive the
 * course from where we actually travelled. The trail is also used to place a driver's hazard
 * report where the hazard really was (a few seconds before the button press).
 */
class CourseTracker(
    private val trailSeconds: Int = 90,
    private val minSpeedForBearingMps: Double = 2.0,
    private val smoothing: Double = 0.6,
) {
    private data class Crumb(val timeMs: Long, val lat: Double, val lng: Double, var heading: Double? = null)
    data class ReportPosition(val lat: Double, val lng: Double, val headingDeg: Double?)

    private val trail = ArrayDeque<Crumb>()
    private var sx = 0.0
    private var sy = 0.0
    private var hasCourse = false
    private var lastCourseAt = Long.MIN_VALUE / 2
    private var latestTime = 0L

    /** Latest smoothed course, or null if we've never had a trustworthy one. */
    val courseDeg: Double?
        get() = if (hasCourse && latestTime - lastCourseAt <= 15_000L) Geo.normalize(Math.toDegrees(atan2(sx, sy))) else null

    fun reset() {
        trail.clear()
        hasCourse = false
        sx = 0.0
        sy = 0.0
    }

    /** Feeds a fix and returns the updated course (null if still unknown). */
    fun update(fix: Fix): Double? {
        latestTime = fix.timeMs
        trail.addLast(Crumb(fix.timeMs, fix.lat, fix.lng))
        while (trail.size > 1 && fix.timeMs - trail.first().timeMs > trailSeconds * 1000L) trail.removeFirst()

        val raw = rawCourse(fix)
        if (raw != null) {
            lastCourseAt = fix.timeMs
            val r = Math.toRadians(raw)
            val nx = sin(r)
            val ny = cos(r)
            if (!hasCourse) {
                sx = nx; sy = ny; hasCourse = true
            } else {
                val prev = Math.toDegrees(atan2(sx, sy))
                // Large jumps are real turns; follow them immediately instead of averaging through them.
                if (Geo.angleDiff(prev, raw) > 50.0) {
                    sx = nx; sy = ny
                } else {
                    sx = smoothing * nx + (1 - smoothing) * sx
                    sy = smoothing * ny + (1 - smoothing) * sy
                }
            }
        }
        trail.last().heading = courseDeg
        return courseDeg
    }

    private fun rawCourse(fix: Fix): Double? {
        val b = fix.bearingDeg
        val bAcc = fix.bearingAccuracyDeg
        if (b != null && fix.speedMps >= minSpeedForBearingMps && (bAcc == null || bAcc <= 25f)) {
            return Geo.normalize(b.toDouble())
        }
        if (fix.speedMps < 1.0f) return null
        // Fall back to the direction we actually moved over the last ~15+ m.
        for (i in trail.size - 2 downTo 0) {
            val c = trail[i]
            if (fix.timeMs - c.timeMs > 10_000L) break
            val d = Geo.distanceMeters(c.lat, c.lng, fix.lat, fix.lng)
            if (d >= 15.0) return Geo.bearingDeg(c.lat, c.lng, fix.lat, fix.lng)
        }
        return null
    }

    /**
     * Position [meters] back along the path we just drove, or null if the trail is too short.
     * Linear interpolation between breadcrumbs; the path (not the straight line) is what counts so
     * reports after a bend still land on the road.
     */
    fun positionBehind(meters: Double): Pair<Double, Double>? =
        reportPositionBehind(meters)?.let { it.lat to it.lng }

    fun reportPositionBehind(meters: Double): ReportPosition? {
        if (trail.size < 2 || meters <= 0.0) return trail.lastOrNull()?.let { ReportPosition(it.lat, it.lng, it.heading) }
        var remaining = meters
        for (i in trail.size - 1 downTo 1) {
            val a = trail[i]
            val b = trail[i - 1]
            val seg = Geo.distanceMeters(a.lat, a.lng, b.lat, b.lng)
            if (seg >= remaining && seg > 0.0) {
                val f = remaining / seg
                return ReportPosition(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f, b.heading ?: a.heading)
            }
            remaining -= seg
        }
        return null
    }
}
