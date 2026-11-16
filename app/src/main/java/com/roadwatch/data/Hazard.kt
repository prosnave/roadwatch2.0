package com.roadwatch.data

import java.time.Instant
import java.util.Locale

data class Hazard(
    val type: HazardType,
    val lat: Double,
    val lng: Double,
    /**
     * ONE_WAY: applies only to traffic travelling along [reportedHeadingDeg] (e.g. one carriageway of a
     * divided road). BIDIRECTIONAL: applies to both directions of the road axis.
     */
    val directionality: String,
    /** Course of travel when the hazard was reported. 0.0 means "unknown" (legacy seed rows). */
    val reportedHeadingDeg: Float,
    val userBearing: Float? = null,
    val active: Boolean = true,
    val source: String = "SEED", // SEED, USER, REMOTE_SYNC
    val createdAt: Instant = Instant.EPOCH,
    val speedLimitKph: Int? = null,
    val zoneLengthMeters: Int? = null,
    val zoneStartLat: Double? = null,
    val zoneStartLng: Double? = null,
    val zoneEndLat: Double? = null,
    val zoneEndLng: Double? = null,
    /** Stable identifier assigned by the repository. Blank only for transient, unsaved hazards. */
    val id: String = "",
    val schemaVersion: Int = 1,
    /** Null denotes legacy inference; false is explicitly unknown; true permits genuine due north (0). */
    val headingValid: Boolean? = null,
    val reportAccuracyM: Float? = null,
) {
    val headingKnown: Boolean
        get() = reportedHeadingDeg.isFinite() && reportedHeadingDeg >= 0f && reportedHeadingDeg < 360f &&
            (headingValid ?: (reportedHeadingDeg != 0f))

    /** Identity used for alert state and logs; falls back to a position key for unsaved hazards. */
    val key: String
        get() = id.ifBlank { String.format(Locale.US, "%s|%.6f|%.6f", type.name, lat, lng) }

    val isZone: Boolean get() = type == HazardType.SPEED_LIMIT_ZONE
    val hasZoneSegment: Boolean
        get() = zoneStartLat != null && zoneStartLng != null && zoneEndLat != null && zoneEndLng != null
}

data class SeedLoadResult(
    val loaded: Boolean,
    val count: Int,
)
