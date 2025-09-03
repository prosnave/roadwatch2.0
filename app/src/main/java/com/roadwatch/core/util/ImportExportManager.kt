package com.roadwatch.core.util

import android.content.Context
import android.net.Uri
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardSource
import com.roadwatch.data.entities.HazardType
import com.roadwatch.data.repository.HazardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImportExportManager(
    private val context: Context,
    private val hazardRepository: HazardRepository
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    suspend fun exportHazards(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val hazardList = mutableListOf<Hazard>()

            hazardRepository.exportHazards().collect { hazards ->
                hazardList.addAll(hazards)
            }

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    // Write CSV header
                    writer.write("type,lat,lon,heading,bearing_tolerance,alert_radius,speed_limit,zone_end_lat,zone_end_lon,source,created_at,updated_at\n")

                    // Write hazard data
                    hazardList.forEach { hazard ->
                        val line = buildCsvLine(hazard)
                        writer.write(line)
                        writer.newLine()
                    }
                }
            }

            ExportResult.Success(hazardList.size)
        } catch (e: Exception) {
            ExportResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun importHazards(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val hazards = mutableListOf<Hazard>()
            var skipped = 0
            var invalidLines = 0

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    var isFirstLine = true

                    while (reader.readLine().also { line = it } != null) {
                        if (isFirstLine) {
                            isFirstLine = false
                            continue // Skip header
                        }

                        line?.let { csvLine ->
                            try {
                                val hazard = parseCsvLine(csvLine)
                                if (hazard != null) {
                                    hazards.add(hazard)
                                } else {
                                    invalidLines++
                                }
                            } catch (e: Exception) {
                                invalidLines++
                            }
                        }
                    }
                }
            }

            if (hazards.isNotEmpty()) {
                val result = hazardRepository.importHazards(hazards)
                ImportResult.Success(result.added, result.updated, result.skipped + invalidLines)
            } else {
                ImportResult.Success(0, 0, invalidLines)
            }
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun buildCsvLine(hazard: Hazard): String {
        return listOf(
            hazard.type.name,
            hazard.lat.toString(),
            hazard.lon.toString(),
            hazard.headingDeg?.toString() ?: "",
            hazard.bearingToleranceDeg.toString(),
            hazard.alertRadiusM.toString(),
            hazard.speedLimitKph?.toString() ?: "",
            hazard.zoneEndLat?.toString() ?: "",
            hazard.zoneEndLon?.toString() ?: "",
            hazard.source.name,
            dateFormat.format(Date(hazard.createdAt)),
            dateFormat.format(Date(hazard.updatedAt))
        ).joinToString(",")
    }

    private fun parseCsvLine(csvLine: String): Hazard? {
        val parts = csvLine.split(",")
        if (parts.size < 3) return null

        val type = parseHazardType(parts[0].trim())
        val lat = parts[1].trim().toDoubleOrNull()
        val lon = parts[2].trim().toDoubleOrNull()

        if (type == null || lat == null || lon == null) return null

        return Hazard(
            type = type,
            lat = lat,
            lon = lon,
            headingDeg = parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.toFloatOrNull(),
            bearingToleranceDeg = parts.getOrNull(4)?.toFloatOrNull() ?: 30f,
            alertRadiusM = parts.getOrNull(5)?.toIntOrNull() ?: 120,
            speedLimitKph = parts.getOrNull(6)?.takeIf { it.isNotBlank() }?.toIntOrNull(),
            zoneEndLat = parts.getOrNull(7)?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
            zoneEndLon = parts.getOrNull(8)?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
            source = parseHazardSource(parts.getOrNull(9)?.trim() ?: "IMPORT"),
            createdAt = parts.getOrNull(10)?.let { parseDate(it) } ?: System.currentTimeMillis(),
            updatedAt = parts.getOrNull(11)?.let { parseDate(it) } ?: System.currentTimeMillis()
        )
    }

    private fun parseHazardType(typeString: String): HazardType? {
        return try {
            HazardType.valueOf(typeString.uppercase())
        } catch (e: IllegalArgumentException) {
            // Try to map common variations
            when (typeString.lowercase()) {
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

    private fun parseHazardSource(sourceString: String): HazardSource {
        return try {
            HazardSource.valueOf(sourceString.uppercase())
        } catch (e: IllegalArgumentException) {
            HazardSource.IMPORT
        }
    }

    private fun parseDate(dateString: String): Long {
        return try {
            dateFormat.parse(dateString)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    sealed class ExportResult {
        data class Success(val exportedCount: Int) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    sealed class ImportResult {
        data class Success(val added: Int, val updated: Int, val skipped: Int) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }
}
