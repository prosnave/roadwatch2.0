package com.roadwatch.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.android.gms.maps.model.LatLng

@Entity(tableName = "hazards")
data class Hazard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: HazardType,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float? = null,
    val bearingToleranceDeg: Float = 30f,
    val alertRadiusM: Int = 120,
    // Speed limit zones only
    val speedLimitKph: Int? = null,
    val zoneEndLat: Double? = null,
    val zoneEndLon: Double? = null,
    val active: Boolean = true,
    val source: HazardSource = HazardSource.SEED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val position: LatLng
        get() = LatLng(lat, lon)

    val zoneEndPosition: LatLng?
        get() = if (zoneEndLat != null && zoneEndLon != null) LatLng(zoneEndLat, zoneEndLon) else null
}

enum class HazardType {
    SPEED_BUMP,
    RUMBLE_STRIP,
    POTHOLE,
    DEBRIS,
    POLICE,
    SPEED_LIMIT_ZONE
}

enum class HazardSource {
    SEED,
    USER,
    IMPORT
}
