package com.roadwatch.network

import org.json.JSONObject

enum class HazardTypeDto { SPEED_BUMP, POTHOLE, RUMBLE_STRIP, SPEED_LIMIT_ZONE }

data class HazardDto(
    val id: String,
    val type: HazardTypeDto,
    val lat: Double,
    val lng: Double,
    val directionality: String,
    val reported_heading_deg: Float?,
    val active: Boolean,
    val source: String,
    val created_at: String,
    val updated_at: String,
    val created_by_device_id: String?,
    val speed_limit_kph: Int?,
    val zone_length_meters: Int?,
    val zone_start_lat: Double?,
    val zone_start_lng: Double?,
    val zone_end_lat: Double?,
    val zone_end_lng: Double?,
    val votes_count: Int
)

data class HazardsListResponseDto(val hazards: List<HazardDto>, val cursor: String?)
data class VoteStatusDto(val votes_count: Int, val has_voted: Boolean?)
data class SyncDeletedDto(val id: String, val deleted_at: String)
data class SyncResponseDto(val hazards: List<HazardDto>, val deleted: List<SyncDeletedDto>, val next_since: String)

fun JSONObject.toHazardDto(): HazardDto = HazardDto(
    id = getString("id"),
    type = HazardTypeDto.valueOf(getString("type")),
    lat = getDouble("lat"),
    lng = getDouble("lng"),
    directionality = getString("directionality"),
    reported_heading_deg = optDouble("reported_heading_deg", Double.NaN).let { if (it.isNaN()) null else it.toFloat() },
    active = getBoolean("active"),
    source = getString("source"),
    created_at = getString("created_at"),
    updated_at = getString("updated_at"),
    created_by_device_id = optString("created_by_device_id", null),
    speed_limit_kph = if (isNull("speed_limit_kph")) null else getInt("speed_limit_kph"),
    zone_length_meters = if (isNull("zone_length_meters")) null else getInt("zone_length_meters"),
    zone_start_lat = if (isNull("zone_start_lat")) null else getDouble("zone_start_lat"),
    zone_start_lng = if (isNull("zone_start_lng")) null else getDouble("zone_start_lng"),
    zone_end_lat = if (isNull("zone_end_lat")) null else getDouble("zone_end_lat"),
    zone_end_lng = if (isNull("zone_end_lng")) null else getDouble("zone_end_lng"),
    votes_count = getInt("votes_count")
)

