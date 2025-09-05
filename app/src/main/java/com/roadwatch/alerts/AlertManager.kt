package com.roadwatch.alerts

import android.content.Context
import android.location.Location
import android.util.Log
import com.roadwatch.data.Hazard
import com.roadwatch.data.SeedRepository
import com.roadwatch.notifications.NotificationHelper
import android.speech.tts.TextToSpeech
import com.roadwatch.prefs.AppPrefs
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Build
import java.util.Locale
import kotlin.math.*

class AlertManager(private val context: Context) {
    private val repo = SeedRepository(context)
    private var lastAlertTime = 0L
    private val lastPerHazard = mutableMapOf<String, Long>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    fun onLocationUpdate(loc: Location) {
        if (AppPrefs.isMuted(context)) return
        val hazards = repo.allHazards().filter { it.active }
        handleZones(loc, hazards)
        val target = upcomingHazard(loc, hazards.filter { it.type.name != "SPEED_LIMIT_ZONE" }) ?: return
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < MIN_GAP_MS) return
        val key = hazardKey(target)
        val lastForThis = lastPerHazard[key] ?: 0L
        val moving = loc.speed > 2.0 // m/s ≈ 7.2 km/h
        if (now - lastForThis < QUIET_MS) {
            if (!moving && now - lastForThis >= QUIET_MS) {
                // will alert below
            } else {
                return
            }
        }
        lastPerHazard[key] = now
        lastAlertTime = now

        val title = "Hazard ahead"
        val dMeters = distanceMeters(loc.latitude, loc.longitude, target.lat, target.lng).roundToInt()
        val text = target.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } +
            " in " + nearestNiceDistance(dMeters)
        NotificationHelper.ensureChannels(context)
        if (AppPrefs.isVisualEnabled(context)) {
            NotificationHelper.showTestAlert(context, title, text)
        }
        if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(text)
    }

    private fun speakWithFocus(text: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = AppPrefs.getAudioFocusMode(context)
        val focusGain = if (mode == "EXCLUSIVE") AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        var focusGranted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afr = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .build()
            focusGranted = am.requestAudioFocus(afr) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            tts?.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) { am.abandonAudioFocusRequest(afr) }
                override fun onDone(utteranceId: String?) { am.abandonAudioFocusRequest(afr) }
            })
        }
        if (focusGranted) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rw_live")
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rw_live")
        }
    }

    private fun upcomingHazard(loc: Location, hazards: List<Hazard>): Hazard? {
        val lead = leadDistanceMeters(loc.speed * 3.6) // speed m/s → km/h
        var best: Hazard? = null
        var bestDist = Double.MAX_VALUE
        val hasBearing = loc.hasBearing()
        val headingRad = Math.toRadians(loc.bearing.toDouble())
        hazards.forEach { h ->
            val d = distanceMeters(loc.latitude, loc.longitude, h.lat, h.lng)
            if (d > lead) return@forEach
            // Dual-carriage filter: heading agreement and lateral offset
            if (hasBearing) {
                val bearingTo = bearingRadians(loc.latitude, loc.longitude, h.lat, h.lng)
                val headingDiffDeg = Math.toDegrees(angleDelta(headingRad, bearingTo).absoluteValue)
                if (headingDiffDeg > MIN_HEADING_AGREE_DEG) return@forEach
                val lateral = lateralOffsetMeters(d, headingRad, bearingTo)
                if (abs(lateral) > MAX_LATERAL_OFFSET_METERS) return@forEach
            }
            if (d < bestDist) {
                best = h
                bestDist = d
            }
        }
        return best
    }

    private fun leadDistanceMeters(speedKph: Double): Double {
        // Select curve from prefs
        return when (AppPrefs.getSpeedCurve(context)) {
            "CONSERVATIVE" -> when {
                speedKph <= 0 -> 100.0
                speedKph <= 50 -> 100 + (speedKph / 50.0) * 100
                speedKph <= 100 -> 200 + ((speedKph - 50) / 50.0) * 250
                speedKph <= 120 -> 450 + ((speedKph - 100) / 20.0) * 150
                else -> 700.0
            }
            "AGGRESSIVE" -> when {
                speedKph <= 0 -> 200.0
                speedKph <= 50 -> 200 + (speedKph / 50.0) * 200
                speedKph <= 100 -> 400 + ((speedKph - 50) / 50.0) * 400
                speedKph <= 120 -> 800 + ((speedKph - 100) / 20.0) * 300
                else -> 1200.0
            }
            else -> when {
                speedKph <= 0 -> 150.0
                speedKph <= 50 -> 150 + (speedKph / 50.0) * 150
                speedKph <= 100 -> 300 + ((speedKph - 50) / 50.0) * 300
                speedKph <= 120 -> 600 + ((speedKph - 100) / 20.0) * 200
                else -> 900.0
            }
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun hazardKey(h: Hazard): String = "${h.type}|${String.format(Locale.US, "%.6f", h.lat)}|${String.format(Locale.US, "%.6f", h.lng)}"

    private fun nearestNiceDistance(meters: Int): String {
        return when {
            meters < 50 -> "<50 m"
            meters < 200 -> "${(meters / 10) * 10} m"
            meters < 1000 -> "${(meters / 50) * 50} m"
            else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
    }

    private fun bearingRadians(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return atan2(y, x)
    }

    private fun angleDelta(a: Double, b: Double): Double {
        var d = (b - a + Math.PI) % (2 * Math.PI)
        if (d < 0) d += 2 * Math.PI
        return d - Math.PI
    }

    private fun lateralOffsetMeters(distance: Double, headingRad: Double, bearingToRad: Double): Double {
        // Cross-track error approximation
        return distance * sin(bearingToRad - headingRad)
    }

    companion object {
        private const val MIN_GAP_MS = 10_000L
        private const val MIN_HEADING_AGREE_DEG = 25.0
        private const val MAX_LATERAL_OFFSET_METERS = 15.0
        private const val QUIET_MS = 30_000L
    }

    // --- Speed limit zone support ---
    private var inZone = false
    private var lastZoneRepeat = 0L
    private var zoneEntryLat: Double? = null
    private var zoneEntryLng: Double? = null
    private var zoneExitWarned = false
    private var zoneLength: Int? = null
    private var zoneLimitKph: Int? = null

    private fun handleZones(loc: Location, hazards: List<Hazard>) {
        val zones = hazards.filter { it.type.name == "SPEED_LIMIT_ZONE" }
        if (zones.isEmpty()) return
        val nearest = zones.minByOrNull { distanceMeters(loc.latitude, loc.longitude, it.lat, it.lng) }
        val dist = if (nearest != null) distanceMeters(loc.latitude, loc.longitude, nearest.lat, nearest.lng) else Double.MAX_VALUE
        val inside = dist < 100.0 // basic radius; replace with zoneLengthMeters if provided

        val now = System.currentTimeMillis()
        if (!inZone && inside) {
            inZone = true
            lastZoneRepeat = now
            zoneEntryLat = loc.latitude
            zoneEntryLng = loc.longitude
            zoneLength = nearest?.zoneLengthMeters
            zoneLimitKph = nearest?.speedLimitKph
            zoneExitWarned = false
            val limit = zoneLimitKph
            val msg = if (limit != null) "Entering speed limit zone: limit is ${limit} km/h" else AppPrefs.getZoneEnter(context)
            NotificationHelper.ensureChannels(context)
            if (AppPrefs.isVisualEnabled(context)) NotificationHelper.showTestAlert(context, "Speed Limit", msg)
            if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
        } else if (inZone && inside) {
            if (now - lastZoneRepeat >= AppPrefs.getZoneRepeatMs(context)) {
                lastZoneRepeat = now
                val limit = zoneLimitKph
                val msg = if (limit != null) "Speed limit ${limit} km/h" else AppPrefs.getZoneEnter(context)
                if (AppPrefs.isVisualEnabled(context)) NotificationHelper.showTestAlert(context, "Speed Limit", msg)
                if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
            }
            // Pre-warn exit if we have zone length and entry
            val len = zoneLength
            val eLat = zoneEntryLat
            val eLng = zoneEntryLng
            if (!zoneExitWarned && len != null && eLat != null && eLng != null) {
                val traveled = distanceMeters(eLat, eLng, loc.latitude, loc.longitude)
                val remaining = len - traveled
                if (remaining in 1.0..200.0) {
                    val msg = "Exiting speed limit zone in ${nearestNice(remaining)}"
                    if (AppPrefs.isVisualEnabled(context)) NotificationHelper.showTestAlert(context, "Speed Limit", msg)
                    if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
                    zoneExitWarned = true
                }
            }
            // If length unknown: infer an ahead endpoint aligned with heading
            if (!zoneExitWarned && len == null && loc.hasBearing()) {
                val heading = Math.toRadians(loc.bearing.toDouble())
                val ahead = zones
                    .map { it to distanceMeters(loc.latitude, loc.longitude, it.lat, it.lng) }
                    .filter { it.second > 50.0 && it.second < 2000.0 } // between 50m and 2km
                    .filter { dPair ->
                        val b = bearingRadians(loc.latitude, loc.longitude, dPair.first.lat, dPair.first.lng)
                        Math.toDegrees(kotlin.math.abs(angleDelta(heading, b))) < 30.0
                    }
                    .minByOrNull { it.second }
                if (ahead != null) {
                    val remaining = ahead.second
                    if (remaining in 1.0..200.0) {
                        val msg = "Exiting speed limit zone in ${nearestNice(remaining)}"
                        if (AppPrefs.isVisualEnabled(context)) NotificationHelper.showTestAlert(context, "Speed Limit", msg)
                        if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
                        zoneExitWarned = true
                    }
                }
            }
        } else if (inZone && !inside) {
            inZone = false
            val msg = if (zoneLimitKph != null) "Exiting speed limit zone" else AppPrefs.getZoneExit(context)
            if (AppPrefs.isVisualEnabled(context)) NotificationHelper.showTestAlert(context, "Speed Limit", msg)
            if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
            zoneEntryLat = null; zoneEntryLng = null; zoneLength = null; zoneLimitKph = null; zoneExitWarned = false
        }
    }

    private fun nearestNice(m: Double): String {
        val meters = m.toInt()
        return when {
            meters < 50 -> "<50 m"
            meters < 200 -> "${(meters / 10) * 10} m"
            else -> "${meters} m"
        }
    }
}
