package com.roadwatch.engine

import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import java.util.Locale
import kotlin.math.roundToInt

/** Wording for spoken and visual alerts (English only for now; extract to string resources when localising). */
object AlertText {
    fun name(type: HazardType, plural: Boolean = false): String = when (type) {
        HazardType.SPEED_BUMP -> if (plural) "speed bumps" else "speed bump"
        HazardType.RUMBLE_STRIP -> if (plural) "rumble strips" else "rumble strip"
        HazardType.POTHOLE -> if (plural) "potholes" else "pothole"
        HazardType.SPEED_LIMIT_ZONE -> if (plural) "speed limit zones" else "speed limit zone"
    }

    fun capitalized(s: String): String = s.replaceFirstChar { it.uppercase() }

    /** "150 meters" — rounded to what a driver can judge by eye. */
    fun spokenDistance(meters: Int): String = when {
        meters < 100 -> "${((meters + 5) / 10) * 10} meters"
        meters < 1000 -> "${((meters + 25) / 50) * 50} meters"
        else -> String.format(Locale.US, "%.1f kilometers", meters / 1000.0)
    }

    fun shortDistance(meters: Int): String = when {
        meters < 100 -> "${((meters + 5) / 10) * 10} m"
        meters < 1000 -> "${((meters + 25) / 50) * 50} m"
        else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
    }

    data class Text(val speech: String, val title: String, val subtitle: String)

    fun hazards(hazards: List<Hazard>, distanceM: Int, speedKph: Int): Text {
        val first = hazards.first()
        val sameType = hazards.all { it.type == first.type }
        val where = if (distanceM >= 60) "in ${spokenDistance(distanceM)}" else "ahead"
        val slowDown = if (speedKph >= 50 && hazards.any { it.type == HazardType.SPEED_BUMP }) " Slow down." else ""

        return if (hazards.size == 1) {
            Text(
                speech = capitalized("${name(first.type)} $where.$slowDown"),
                title = capitalized(name(first.type)),
                subtitle = shortDistance(distanceM) + " ahead",
            )
        } else if (sameType) {
            Text(
                speech = "${hazards.size} ${name(first.type, plural = true)} ahead, first $where.$slowDown",
                title = "${hazards.size} ${name(first.type, plural = true)}",
                subtitle = "first " + shortDistance(distanceM) + " ahead",
            )
        } else {
            Text(
                speech = "${hazards.size} hazards ahead, first a ${name(first.type)} $where.$slowDown",
                title = "${hazards.size} hazards ahead",
                subtitle = "first " + shortDistance(distanceM) + " ahead",
            )
        }
    }

    fun zoneAhead(zone: Hazard): Text {
        val limit = zone.speedLimitKph
        return if (limit != null) {
            Text("Speed limit zone ahead. $limit kilometers per hour.", "Speed limit $limit", "zone ahead")
        } else {
            Text("Speed limit zone ahead.", "Speed limit zone", "zone ahead")
        }
    }

    fun zoneEnd(): Text = Text("Speed limit zone ends.", "Zone ends", "")

    fun zoneReminder(zone: Hazard): Text {
        val limit = zone.speedLimitKph
        return if (limit != null) Text("Speed limit $limit.", "Speed limit $limit", "in zone")
        else Text("Speed limit zone.", "Speed limit zone", "in zone")
    }

    fun kph(mps: Float): Int = (mps * 3.6f).roundToInt().coerceAtLeast(0)
}
