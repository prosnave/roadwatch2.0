package com.roadwatch.data

import java.time.Instant

data class Hazard(
    val type: HazardType,
    val lat: Double,
    val lng: Double,
    val bearingSide: String = "UNKNOWN", // LEFT, RIGHT, CENTER, UNKNOWN
    val directionality: String = "UNKNOWN", // ONE_WAY, BIDIRECTIONAL, UNKNOWN
    val active: Boolean = true,
    val source: String = "SEED", // SEED, USER, REMOTE_SYNC
    val createdAt: Instant = Instant.EPOCH,
    val speedLimitKph: Int? = null,
    val zoneLengthMeters: Int? = null,
)

data class SeedLoadResult(
    val loaded: Boolean,
    val count: Int,
)

