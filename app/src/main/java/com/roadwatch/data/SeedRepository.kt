package com.roadwatch.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileNotFoundException
import java.time.Instant

class SeedRepository(private val context: Context) {
    private val tag = "SeedRepository"

    fun loadSeeds(): Pair<SeedLoadResult, List<Hazard>> {
        val hazards = mutableListOf<Hazard>()
        return try {
            context.assets.open("seeds.csv").use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    val header = reader.readLine() ?: ""
                    val cols = header.split(',').map { it.trim().lowercase() }
                    val idxType = cols.indexOf("type")
                    val idxLat = cols.indexOf("lat")
                    var idxLng = cols.indexOf("lng").let { if (it == -1) cols.indexOf("lon") else it }
                    val idxDirectionality = cols.indexOf("directionality")
                    val idxSpeedKph = cols.indexOf("speedlimitkph")
                    val idxZoneLen = cols.indexOf("zonelengthmeters")

                    fun defaultDirectionality(t: HazardType): String = when (t) {
                        HazardType.SPEED_LIMIT_ZONE -> "BIDIRECTIONAL"
                        else -> "ONE_WAY"
                    }

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line!!.trim()
                        if (raw.isEmpty()) continue
                        val parts = raw.split(',')
                        try {
                            val typeStr = parts.getOrNull(idxType) ?: continue
                            val type = HazardType.fromString(typeStr) ?: continue
                            val lat = parts.getOrNull(idxLat)?.toDoubleOrNull() ?: continue
                            val lng = parts.getOrNull(idxLng)?.toDoubleOrNull() ?: continue
                            val directionality = if (idxDirectionality >= 0) parts.getOrNull(idxDirectionality).orEmpty().ifBlank { defaultDirectionality(type) } else defaultDirectionality(type)
                            val speedKph = if (idxSpeedKph >= 0) parts.getOrNull(idxSpeedKph)?.toIntOrNull() else null
                            val zoneLen = if (idxZoneLen >= 0) parts.getOrNull(idxZoneLen)?.toIntOrNull() else null
                            hazards += Hazard(
                                type = type,
                                lat = lat,
                                lng = lng,
                                directionality = directionality,
                                reportedHeadingDeg = 0.0f,
                                userBearing = 0.0f,
                                roadBearing = 0.0f,
                                speedLimitKph = speedKph,
                                zoneLengthMeters = zoneLen,
                                source = "SEED",
                                createdAt = Instant.now(),
                            )
                        } catch (e: Exception) {
                            Log.w(tag, "Skipping invalid seed row: $raw", e)
                        }
                    }
                }
            }
            SeedLoadResult(loaded = true, count = hazards.size) to hazards
        } catch (e: Exception) {
            return if (e is FileNotFoundException) {
                // Treat missing seeds.csv as a valid empty seed set
                Log.i(tag, "No seeds.csv found; starting with zero seeds")
                SeedLoadResult(loaded = true, count = 0) to emptyList()
            } else {
                Log.e(tag, "Failed to load seeds.csv", e)
                SeedLoadResult(loaded = false, count = 0) to emptyList()
            }
        }
    }

    fun loadUserHazards(): List<UserHazard> = HazardStore(context).list()

    fun addUserHazard(hazard: Hazard): Boolean = HazardStore(context).add(hazard)

    /**
     * Returns seeds + user hazards filtered for visibility:
     * - Excludes seed hazards disabled via SeedOverrides
     * - Excludes inactive user hazards
     */
    fun activeHazards(): List<Hazard> {
        val (_, seeds) = loadSeeds()
        val users = loadUserHazards().map { it.hazard }
        val visibleSeeds = seeds.filter { h -> !SeedOverrides.isDisabled(context, SeedOverrides.keyOf(h)) && h.active }
        val visibleUsers = users.filter { it.active }
        return visibleSeeds + visibleUsers
    }

    enum class AddResult { ADDED, DUPLICATE_NEARBY, ERROR }

    /**
     * Add a user hazard with de-duplication: prevent adding the same type within [thresholdMeters]
     * of any existing active hazard (seed or user).
     */
    fun addUserHazardWithDedup(hazard: Hazard, thresholdMeters: Double = 30.0): AddResult {
        return try {
            val existing = activeHazards()
            val dup = existing.any { it.type == hazard.type && distanceMeters(it.lat, it.lng, hazard.lat, hazard.lng) < thresholdMeters }
            if (dup) return AddResult.DUPLICATE_NEARBY
            if (HazardStore(context).add(hazard)) AddResult.ADDED else AddResult.ERROR
        } catch (t: Throwable) {
            AddResult.ERROR
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
}
