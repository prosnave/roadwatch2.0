package com.roadwatch.data

import java.time.Instant

data class Hazard(
    val type: HazardType,
    val lat: Double,
    val lng: Double,
    val directionality: String, // ONE_WAY, BIDIRECTIONAL, UNKNOWN
    val reportedHeadingDeg: Float, // heading at time of report
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
    val id: String? = null,
    val updatedAt: Instant = Instant.EPOCH,
    val votesCount: Int = 0,
)

data class SeedLoadResult(
    val loaded: Boolean,
    val count: Int,
)
