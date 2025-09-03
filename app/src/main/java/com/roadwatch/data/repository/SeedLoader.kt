package com.roadwatch.data.repository

import android.content.Context
import com.roadwatch.data.dao.HazardDao
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardSource
import com.roadwatch.data.entities.HazardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class SeedLoader(
    private val context: Context,
    private val hazardDao: HazardDao
) {

    suspend fun loadSeedsIfEmpty(replaceExisting: Boolean = false) {
        withContext(Dispatchers.IO) {
            val seedCount = hazardDao.getSeedCount()
            if (seedCount == 0 || replaceExisting) {
                if (replaceExisting) {
                    hazardDao.deleteAllSeeds()
                }
                val seeds = parseSeedsCsv()
                hazardDao.upsertAll(seeds)
            }
        }
    }

    private fun parseSeedsCsv(): List<Hazard> {
        val seeds = mutableListOf<Hazard>()

        try {
            context.assets.open("seeds.csv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    var isFirstLine = true

                    while (reader.readLine().also { line = it } != null) {
                        if (isFirstLine) {
                            isFirstLine = false
                            continue // Skip header
                        }

                        line?.let { csvLine ->
                            val parts = csvLine.split(",")
                            if (parts.size >= 3) {
                                val type = parseHazardType(parts[0].trim())
                                val lat = parts[1].trim().toDoubleOrNull()
                                val lon = parts[2].trim().toDoubleOrNull()

                                if (type != null && lat != null && lon != null) {
                                    seeds.add(
                                        Hazard(
                                            type = type,
                                            lat = lat,
                                            lon = lon,
                                            source = HazardSource.SEED
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return seeds
    }

    private fun parseHazardType(typeString: String): HazardType? {
        return when (typeString.lowercase()) {
            "speed bump" -> HazardType.SPEED_BUMP
            "rumble strip" -> HazardType.RUMBLE_STRIP
            "pothole" -> HazardType.POTHOLE
            "debris" -> HazardType.DEBRIS
            "police" -> HazardType.POLICE
            "speed limit zone" -> HazardType.SPEED_LIMIT_ZONE
            else -> null
        }
    }
}
