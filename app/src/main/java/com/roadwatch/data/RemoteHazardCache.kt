package com.roadwatch.data

import android.content.Context
import android.location.Location
import android.util.Log
import com.roadwatch.network.ApiClient
import java.time.Instant
import kotlin.math.*

private fun org.json.JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name, "").takeIf { it.isNotEmpty() }

class RemoteHazardCache(private val context: Context) {
    private var lastFetchMs: Long = 0L
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var cache: List<Hazard> = emptyList()
    private val cacheFile = java.io.File(context.filesDir, "cache/remote_cache.json").apply { parentFile?.mkdirs() }

    init {
        try {
            if (cacheFile.exists()) {
                val text = cacheFile.readText()
                val arr = org.json.JSONArray(text)
                val list = mutableListOf<Hazard>()
                for (i in 0 until arr.length()) {
                    val h = arr.getJSONObject(i)
                    list += Hazard(
                        id = h.optNullableString("id"),
                        type = HazardType.valueOf(h.getString("type")),
                        lat = h.getDouble("lat"),
                        lng = h.getDouble("lng"),
                        directionality = h.getString("directionality"),
                        reportedHeadingDeg = h.optDouble("reported_heading_deg", 0.0).toFloat(),
                        active = h.getBoolean("active"),
                        source = h.optNullableString("source") ?: "REMOTE_SYNC",
                        createdAt = try { Instant.parse(h.getString("created_at")) } catch (_: Exception) { Instant.EPOCH },
                        speedLimitKph = if (h.isNull("speed_limit_kph")) null else h.getInt("speed_limit_kph"),
                        zoneLengthMeters = if (h.isNull("zone_length_meters")) null else h.getInt("zone_length_meters"),
                        zoneStartLat = if (h.isNull("zone_start_lat")) null else h.getDouble("zone_start_lat"),
                        zoneStartLng = if (h.isNull("zone_start_lng")) null else h.getDouble("zone_start_lng"),
                        zoneEndLat = if (h.isNull("zone_end_lat")) null else h.getDouble("zone_end_lat"),
                        zoneEndLng = if (h.isNull("zone_end_lng")) null else h.getDouble("zone_end_lng"),
                        updatedAt = try { Instant.parse(h.getString("updated_at")) } catch (_: Exception) { Instant.EPOCH },
                        votesCount = if (h.isNull("votes_count")) 0 else h.getInt("votes_count")
                    )
                }
                cache = list
            }
        } catch (_: Exception) {}
    }

    private fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val x = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2.0)
        val c = 2 * atan2(sqrt(x), sqrt(1 - x))
        return R * c
    }

    fun getNearby(current: Location?): List<Hazard> {
        val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(context).trim()
        if (baseUrl.isEmpty() || current == null) return cache
        val now = System.currentTimeMillis()
        val needFetch = run {
            val moved = if (lastLat != null && lastLng != null) distanceMeters(lastLat!!, lastLng!!, current.latitude, current.longitude) > 200 else true
            moved || (now - lastFetchMs > 15_000)
        }
        if (!needFetch) return cache
        lastLat = current.latitude
        lastLng = current.longitude
        lastFetchMs = now
        val radius = com.roadwatch.prefs.AppPrefs.getSyncRadiusMeters(context)
        val res = ApiClient.listHazards(baseUrl, current.latitude, current.longitude, radius, 200, null, null)
        if (res.isSuccess) {
            val list = res.getOrNull()!!.hazards.map { h ->
                Hazard(
                    id = h.id,
                    type = HazardType.valueOf(h.type.name),
                    lat = h.lat,
                    lng = h.lng,
                    directionality = h.directionality,
                    reportedHeadingDeg = h.reported_heading_deg ?: 0f,
                    userBearing = null,
                    active = h.active,
                    source = h.source,
                    createdAt = try { Instant.parse(h.created_at) } catch (_: Exception) { Instant.EPOCH },
                    speedLimitKph = h.speed_limit_kph,
                    zoneLengthMeters = h.zone_length_meters,
                    zoneStartLat = h.zone_start_lat,
                    zoneStartLng = h.zone_start_lng,
                    zoneEndLat = h.zone_end_lat,
                    zoneEndLng = h.zone_end_lng,
                    updatedAt = try { Instant.parse(h.updated_at) } catch (_: Exception) { Instant.EPOCH },
                    votesCount = h.votes_count
                )
            }
            cache = list
            try {
                val arr = org.json.JSONArray()
                list.forEach { h ->
                    val j = org.json.JSONObject()
                        .put("id", h.id)
                        .put("type", h.type.name)
                        .put("lat", h.lat)
                        .put("lng", h.lng)
                        .put("directionality", h.directionality)
                        .put("reported_heading_deg", h.reportedHeadingDeg)
                        .put("active", h.active)
                        .put("source", h.source)
                        .put("created_at", h.createdAt.toString())
                        .put("updated_at", h.updatedAt.toString())
                        .put("speed_limit_kph", h.speedLimitKph)
                        .put("zone_length_meters", h.zoneLengthMeters)
                        .put("zone_start_lat", h.zoneStartLat)
                        .put("zone_start_lng", h.zoneStartLng)
                        .put("zone_end_lat", h.zoneEndLat)
                        .put("zone_end_lng", h.zoneEndLng)
                        .put("votes_count", h.votesCount)
                    arr.put(j)
                }
                cacheFile.writeText(arr.toString())
            } catch (_: Exception) {}
        } else {
            Log.w("RemoteHazardCache", "listHazards failed: ${res.exceptionOrNull()?.message}")
        }
        return cache
    }
}
