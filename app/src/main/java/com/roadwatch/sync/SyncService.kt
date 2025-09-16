package com.roadwatch.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.roadwatch.network.ApiClient
import org.json.JSONArray
import java.io.File
import java.time.Instant

class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread { runSync(); stopSelf() }.start()
        return START_NOT_STICKY
    }

    private fun runSync() {
        try {
            val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(this).trim()
            if (base.isEmpty()) return
            val last = com.roadwatch.prefs.AppPrefs.getLastLocation(this) ?: return
            val token = com.roadwatch.prefs.AppPrefs.getDeviceToken(this)
            val radius = com.roadwatch.prefs.AppPrefs.getSyncRadiusMeters(this)
            val res = ApiClient.listHazards(base, last.first, last.second, radius, 200, null, token)
            if (res.isSuccess) {
                val list = res.getOrNull()!!.hazards
                val arr = JSONArray()
                list.forEach { h ->
                    val j = org.json.JSONObject()
                        .put("id", h.id)
                        .put("type", h.type.name)
                        .put("lat", h.lat)
                        .put("lng", h.lng)
                        .put("directionality", h.directionality)
                        .put("reported_heading_deg", h.reported_heading_deg)
                        .put("active", h.active)
                        .put("source", h.source)
                        .put("created_at", h.created_at)
                        .put("updated_at", h.updated_at)
                        .put("speed_limit_kph", h.speed_limit_kph)
                        .put("zone_length_meters", h.zone_length_meters)
                        .put("zone_start_lat", h.zone_start_lat)
                        .put("zone_start_lng", h.zone_start_lng)
                        .put("zone_end_lat", h.zone_end_lat)
                        .put("zone_end_lng", h.zone_end_lng)
                        .put("votes_count", h.votes_count)
                    arr.put(j)
                }
                val dir = File(filesDir, "cache").apply { mkdirs() }
                File(dir, "remote_cache.json").writeText(arr.toString())
            }
        } catch (t: Throwable) {
            Log.w("SyncService", "sync failed", t)
        }
    }
}

