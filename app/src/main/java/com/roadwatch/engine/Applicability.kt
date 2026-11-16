package com.roadwatch.engine

import com.roadwatch.data.Hazard
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Decides whether a reported hazard is relevant to a vehicle with a given position and course.
 *
 * This is the "don't cry wolf" core. A hazard applies only if ALL of these hold:
 *  1. Direction: the vehicle's course matches the course it was reported at (ONE_WAY), or the road
 *     axis it was reported on (BIDIRECTIONAL, i.e. either direction along that axis). Cross streets
 *     and the opposite carriageway of a divided road fail this.
 *  2. Position: it is ahead of the vehicle (along-track > 0) and close to the vehicle's path
 *     (cross-track within a tolerance that widens with distance and GPS error).
 */
object Applicability {
    enum class Reason { OK, BEHIND, TOO_FAR, OFF_PATH, WRONG_DIRECTION, UNKNOWN_DIRECTION }

    data class Result(
        val reason: Reason,
        /** Metres ahead along the course (negative = behind). */
        val alongM: Double,
        /** Metres to the right (+) or left (-) of the course line. */
        val crossM: Double,
    ) {
        val ok: Boolean get() = reason == Reason.OK
    }

    fun check(
        fix: Fix,
        courseDeg: Double,
        h: Hazard,
        maxAlongM: Double,
        courseTolDeg: Double,
    ): Result {
        val (dx, dy) = Geo.offsetMeters(fix.lat, fix.lng, h.lat, h.lng)
        val c = Math.toRadians(courseDeg)
        val along = dx * sin(c) + dy * cos(c)
        val cross = dx * cos(c) - dy * sin(c)

        if (along < 0.0) return Result(Reason.BEHIND, along, cross)
        if (along > maxAlongM) return Result(Reason.TOO_FAR, along, cross)
        if (!h.headingKnown) return Result(Reason.UNKNOWN_DIRECTION, along, cross)

        // GPS course is noisier at low speed, so be a bit more forgiving there.
        val tol = courseTolDeg + if (fix.speedMps < 5f) 15.0 else 0.0
        if (!directionMatches(courseDeg, h, tol)) return Result(Reason.WRONG_DIRECTION, along, cross)

        if (abs(cross) > crossTolerance(along, fix.accuracyM)) return Result(Reason.OFF_PATH, along, cross)
        return Result(Reason.OK, along, cross)
    }

    fun directionMatches(courseDeg: Double, h: Hazard, tolDeg: Double): Boolean {
        // Do not guess the carriageway from GPS proximity when a legacy report has no course.
        if (!h.headingKnown) return false
        val reported = h.reportedHeadingDeg.toDouble()
        val same = Geo.angleDiff(courseDeg, reported) <= tolDeg
        if (h.directionality.equals("BIDIRECTIONAL", ignoreCase = true)) {
            return same || Geo.angleDiff(courseDeg, reported + 180.0) <= tolDeg
        }
        return same
    }

    /**
     * Allowed lateral offset. Grows with distance (roads curve, so a hazard 300 m ahead can sit
     * well off a straight-line projection) and with GPS inaccuracy, within sane bounds.
     */
    fun crossTolerance(alongM: Double, accuracyM: Float): Double =
        (10.0 + 0.08 * alongM + 0.5 * min(accuracyM.toDouble(), 20.0)).coerceIn(12.0, 35.0)
}
