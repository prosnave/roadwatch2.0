package com.roadwatch.data

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

class HazardStore(private val context: Context) {
    private val tag = "HazardStore"
    private val file: File by lazy { File(context.filesDir, "user_hazards.csv") }

    init {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("id,type,lat,lng,active,bearingSide,directionality,source,createdAt\n")
        }
    }

    fun add(h: Hazard): Boolean {
        return try {
            val id = System.currentTimeMillis().toString()
            val createdAt = DateTimeFormatter.ISO_INSTANT.format(h.createdAt)
            val line = listOf(
                id,
                h.type.name,
                h.lat.toString(),
                h.lng.toString(),
                h.active.toString(),
                h.bearingSide,
                h.directionality,
                h.source,
                createdAt
            ).joinToString(",")
            file.appendText(line + "\n")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to write user hazard", e)
            false
        }
    }

    fun list(): MutableList<UserHazard> {
        val result = mutableListOf<UserHazard>()
        if (!file.exists()) return result
        file.forEachLine { raw ->
            if (raw.startsWith("id,")) return@forEachLine
            val parts = raw.split(',')
            if (parts.size < 9) return@forEachLine
            try {
                result += UserHazard(
                    id = parts[0],
                    hazard = Hazard(
                        type = HazardType.valueOf(parts[1]),
                        lat = parts[2].toDouble(),
                        lng = parts[3].toDouble(),
                        active = parts[4].toBooleanStrictOrNull() ?: true,
                        bearingSide = parts[5],
                        directionality = parts[6],
                        source = parts[7],
                        createdAt = Instant.parse(parts[8])
                    )
                )
            } catch (_: Exception) {
            }
        }
        return result
    }

    fun delete(id: String): Boolean {
        if (!file.exists()) return false
        val remaining = list().filter { it.id != id }
        return writeAll(remaining)
    }

    fun toggleActive(id: String): Boolean {
        val items = list()
        val updated = items.map { u ->
            if (u.id == id) u.copy(hazard = u.hazard.copy(active = !u.hazard.active)) else u
        }
        return writeAll(updated)
    }

    private fun writeAll(items: List<UserHazard>): Boolean {
        return try {
            file.writeText("id,type,lat,lng,active,bearingSide,directionality,source,createdAt\n")
            items.forEach { u ->
                val h = u.hazard
                val createdAt = DateTimeFormatter.ISO_INSTANT.format(h.createdAt)
                val line = listOf(
                    u.id,
                    h.type.name,
                    h.lat.toString(),
                    h.lng.toString(),
                    h.active.toString(),
                    h.bearingSide,
                    h.directionality,
                    h.source,
                    createdAt
                ).joinToString(",")
                file.appendText(line + "\n")
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to rewrite hazards", e)
            false
        }
    }
}

data class UserHazard(
    val id: String,
    val hazard: Hazard,
)

