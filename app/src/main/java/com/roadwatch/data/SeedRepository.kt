package com.roadwatch.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
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
                    var idxLng = cols.indexOf("lng")
                    if (idxLng == -1) idxLng = cols.indexOf("lon")

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
                            hazards += Hazard(
                                type = type,
                                lat = lat,
                                lng = lng,
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
            Log.e(tag, "Failed to load seeds.csv", e)
            SeedLoadResult(loaded = false, count = 0) to emptyList()
        }
    }

    fun loadUserHazards(): List<UserHazard> = HazardStore(context).list()

    fun addUserHazard(hazard: Hazard): Boolean = HazardStore(context).add(hazard)

    fun allHazards(): List<Hazard> {
        val (_, seeds) = loadSeeds()
        val users = loadUserHazards().map { it.hazard }
        return seeds + users
    }
}
