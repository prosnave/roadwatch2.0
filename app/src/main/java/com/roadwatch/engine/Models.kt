package com.roadwatch.engine

import com.roadwatch.data.Hazard

/** One GPS sample, decoupled from android.location.Location so the engine runs on a plain JVM. */
data class Fix(
    val timeMs: Long,
    val lat: Double,
    val lng: Double,
    val speedMps: Float,
    /** Course over ground, or null when the receiver has no valid bearing. */
    val bearingDeg: Float?,
    val accuracyM: Float,
    val bearingAccuracyDeg: Float? = null,
)

/** Environmental context supplied by the caller for each fix. */
data class DriveContext(
    val night: Boolean = false,
    val extraCaution: Boolean = false,
)

/**
 * All tunables in one place. Defaults are starting points for field validation; the Android layer
 * overrides [leadSeconds] and friends from user preferences.
 */
data class EngineConfig(
    /** Seconds of warning before reaching a hazard at the current speed. */
    val leadSeconds: Double = 10.0,
    /** Additional time for chime, synthesis and Bluetooth delivery, before the reaction window. */
    val audioAllowanceSeconds: Double = 2.0,
    val minLeadM: Double = 60.0,
    val maxLeadM: Double = 650.0,
    /** Below this speed GPS course is unreliable and alerts are pointless (walking, jam). */
    val minSpeedMps: Double = 2.5,
    val maxAccuracyM: Float = 50f,
    /** Max difference between travel course and the course a hazard was reported at. */
    val courseTolDeg: Double = 40.0,
    /** If the alert would land with less than this time to impact, don't bother speaking. */
    val minTimeToHazardSec: Double = 1.5,
    /** Minimum gap between two spoken alerts. Pending hazards are delayed, never dropped. */
    val minGapMs: Long = 3_000L,
    /** Hazards closer than this along the road are announced together as one cluster. */
    val clusterSpanM: Double = 100.0,
    /** After alerting, hazard becomes eligible again only once this far away (return trip / U-turn). */
    val rearmDistanceM: Double = 600.0,
    val nightLeadFactor: Double = 1.25,
    val cautionLeadFactor: Double = 1.3,
    val zoneRepeatMs: Long = 120_000L,
)

enum class Severity { INFO, CAUTION, WARNING }

data class AlertEvent(
    val id: Long,
    val timeMs: Long,
    val kind: Kind,
    val hazards: List<Hazard>,
    /** Distance along the road to the first hazard when the alert fired. */
    val distanceM: Int,
    val speedKph: Int,
    val leadM: Int,
    val severity: Severity,
    /** What the TTS says. */
    val speech: String,
    /** Short headline for visual surfaces. */
    val title: String,
    /** Detail line for visual surfaces; updated live by the UI as the distance shrinks. */
    val subtitle: String,
) {
    enum class Kind { HAZARD, ZONE_ENTER, ZONE_EXIT, ZONE_REMINDER }

    val primary: Hazard get() = hazards.first()
}

/** A hazard that applies to the current course, for HUD display and overlay countdown. */
data class Upcoming(
    val hazard: Hazard,
    val key: String,
    val alongM: Double,
    val crossM: Double,
)

/** Machine-readable decision log, written to the trip file and used by the offline replay tool. */
data class TraceEvent(
    val kind: String,
    val timeMs: Long,
    val hazardKey: String?,
    val detail: String,
)

data class EngineOutput(
    val alerts: List<AlertEvent>,
    val upcoming: List<Upcoming>,
    val trace: List<TraceEvent>,
    val courseDeg: Double?,
) {
    companion object {
        val EMPTY = EngineOutput(emptyList(), emptyList(), emptyList(), null)
    }
}
