package com.roadwatch.data

enum class HazardType {
    SPEED_BUMP,
    POTHOLE,
    RUMBLE_STRIP,
    SPEED_LIMIT_ZONE;

    companion object {
        fun fromString(raw: String): HazardType? {
            val key = raw.trim().uppercase().replace(' ', '_')
            return entries.find { it.name == key }
        }
    }
}

