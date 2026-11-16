package com.roadwatch.data

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * CSV (de)serialisation for hazards. Pure Kotlin so it is unit-tested and shared by the on-device
 * store, the bundled seed file, and import/export. Parsing is header-driven and tolerant: column
 * order doesn't matter, `lon` is accepted for `lng`, `True`/`true` both work, and numeric columns
 * may carry a trailing ".0" (as produced by spreadsheet/pandas exports).
 */
object HazardCsv {
    const val HEADER =
        "id,type,lat,lng,active,directionality,reportedHeadingDeg,source,createdAt,speedLimitKph," +
            "zoneLengthMeters,zoneStartLat,zoneStartLng,zoneEndLat,zoneEndLng,userBearing,schemaVersion,headingValid,reportAccuracyM"

    fun parse(lines: Sequence<String>): List<UserHazard> {
        val out = ArrayList<UserHazard>()
        var cols: Map<String, Int>? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(',').map { it.trim() }
            if (cols == null) {
                val header = parts.mapIndexed { i, s -> s.lowercase() to i }.toMap()
                if ("type" in header && "lat" in header) {
                    cols = header
                    continue
                }
                // No recognisable header: assume the canonical layout.
                cols = HEADER.split(',').mapIndexed { i, s -> s.lowercase() to i }.toMap()
            }
            parseRow(parts, cols)?.let { out += it }
        }
        return out
    }

    private fun parseRow(p: List<String>, c: Map<String, Int>): UserHazard? {
        fun s(name: String): String? = c[name]?.let { p.getOrNull(it) }?.takeIf { it.isNotEmpty() }
        return try {
            val type = HazardType.fromString(s("type") ?: return null) ?: return null
            val lat = s("lat")?.toDoubleOrNull() ?: return null
            val lng = (s("lng") ?: s("lon"))?.toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            val dir = when (s("directionality")?.uppercase()) {
                "BIDIRECTIONAL" -> "BIDIRECTIONAL"
                "ONE_WAY" -> "ONE_WAY"
                else -> if (type == HazardType.SPEED_LIMIT_ZONE) "BIDIRECTIONAL" else "ONE_WAY"
            }
            UserHazard(
                id = s("id").orEmpty(),
                hazard = Hazard(
                    type = type,
                    lat = lat,
                    lng = lng,
                    active = s("active")?.lowercase()?.let { it != "false" && it != "0" } ?: true,
                    directionality = dir,
                    reportedHeadingDeg = s("reportedheadingdeg")?.toFloatOrNull() ?: 0f,
                    source = s("source") ?: "USER",
                    createdAt = s("createdat")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
                    speedLimitKph = s("speedlimitkph")?.toDoubleOrNull()?.toInt(),
                    zoneLengthMeters = s("zonelengthmeters")?.toDoubleOrNull()?.toInt(),
                    zoneStartLat = s("zonestartlat")?.toDoubleOrNull(),
                    zoneStartLng = s("zonestartlng")?.toDoubleOrNull(),
                    zoneEndLat = s("zoneendlat")?.toDoubleOrNull(),
                    zoneEndLng = s("zoneendlng")?.toDoubleOrNull(),
                    userBearing = s("userbearing")?.toFloatOrNull(),
                    schemaVersion = s("schemaversion")?.toIntOrNull() ?: 1,
                    headingValid = s("headingvalid")?.toBooleanStrictOrNull(),
                    reportAccuracyM = s("reportaccuracym")?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f },
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun format(u: UserHazard): String {
        val h = u.hazard
        return listOf(
            u.id,
            h.type.name,
            h.lat.toString(),
            h.lng.toString(),
            h.active.toString(),
            h.directionality,
            h.reportedHeadingDeg.toString(),
            h.source,
            DateTimeFormatter.ISO_INSTANT.format(h.createdAt),
            h.speedLimitKph?.toString().orEmpty(),
            h.zoneLengthMeters?.toString().orEmpty(),
            h.zoneStartLat?.toString().orEmpty(),
            h.zoneStartLng?.toString().orEmpty(),
            h.zoneEndLat?.toString().orEmpty(),
            h.zoneEndLng?.toString().orEmpty(),
            h.userBearing?.toString().orEmpty(),
            h.schemaVersion.toString(),
            h.headingValid?.toString().orEmpty(),
            h.reportAccuracyM?.toString().orEmpty(),
        ).joinToString(",")
    }

    fun formatAll(items: List<UserHazard>): String =
        buildString {
            append(HEADER).append('\n')
            items.forEach { append(format(it)).append('\n') }
        }

}
