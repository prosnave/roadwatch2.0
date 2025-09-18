package com.roadwatch.alerts

import android.content.Context
import android.location.Location
import com.roadwatch.data.Hazard
import com.roadwatch.data.SeedRepository
import com.roadwatch.data.RemoteHazardCache
import android.speech.tts.TextToSpeech
import com.roadwatch.prefs.AppPrefs
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

class AlertManager(private val context: Context) {
    private val repo = SeedRepository(context)
    private val remote = RemoteHazardCache(context)
    private var lastAlertTime = 0L
    private val lastPerHazard = mutableMapOf<String, Long>()
    private val preQuietUntil = mutableMapOf<String, Long>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                updateTtsAudioAttributes(false)
            }
        }
    }

    fun onLocationUpdate(loc: Location) {
        if (AppPrefs.isMuted(context)) return
        // Prefer remote hazards when server configured; fallback to local seed/user hazards
        val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(context).trim()
        val hazards = if (base.isNotEmpty()) remote.getNearby(loc) else repo.activeHazards()
        handleZones(loc, hazards)
        // Low-motion throttling: skip hazard callouts at very low speed
        if (loc.speed < 1.5f) return // ~5.4 kph

        val nonZones = hazards.filter { it.type.name != "SPEED_LIMIT_ZONE" }
        val allForNext = hazards // include zones for next-hazard evaluation
        val speedKph = loc.speed * 3.6
        val hasBearing = loc.hasBearing()
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < MIN_GAP_MS) return

        val lead = leadDistanceMeters(speedKph)

        // Clustered alerts at higher speeds with bearing (gated by prefs)
        val clusterEnabled = AppPrefs.isClusterEnabled(context)
        val clusterSpeedKph = AppPrefs.getClusterSpeedThreshold(context)
        if (clusterEnabled && speedKph >= clusterSpeedKph && hasBearing) {
            val ahead = aheadHazards(loc, nonZones, lead)
            if (ahead.isNotEmpty()) {
                val clusters = buildClusters(ahead, lead)
                if (clusters.isNotEmpty()) {
                    val first = clusters.first()
                    val startDist = first.first().dist.roundToInt()

                    // Skip if any member was alerted very recently
                    val suppress = first.any { a -> (now - (lastPerHazard[hazardKey(a.h)] ?: 0L)) < QUIET_MS }
                    if (!suppress) {
                        // Mark members and set global cooldown
                        first.forEach { a -> lastPerHazard[hazardKey(a.h)] = now }
                        lastAlertTime = now

                        val (label, showType) = clusterLabel(first)
                        val text = if (first.size == 1) {
                            label + " in " + nearestNiceDistance(startDist)
                        } else {
                            val prefix = if (showType) "$label cluster" else "Multiple hazards"
                            prefix + " in " + nearestNiceDistance(startDist)
                        }

                        var followUp: String? = null
                        if (clusters.size >= 2) {
                            val second = clusters[1]
                            val secondDist = second.first().dist
                            if (secondDist > lead && secondDist <= (2 * lead)) {
                                followUp = "Then another cluster ${nearestNiceDistance(secondDist.roundToInt())} away"
                            }
                        }

                        // Status-bar notifications removed; rely on TTS (and optional in-app cues)
                        // Haptics on alert
                        if (AppPrefs.isHapticsEnabled(context)) {
                            try { com.roadwatch.core.util.Haptics.tap(context) } catch (_: Exception) {}
                        }
                        if (ttsReady && AppPrefs.isAudioEnabled(context)) {
                            val f = followUp
                            if (f != null) speakDouble(text, f) else speakWithFocus(text)
                        }
                        sendOverlay(text)
                        sendUiStateUpdate("HAZARD_APPROACHING", null)
                        try {
                            AppPrefs.setLastHazardDirection(context, first.first().h.directionality)
                        } catch (_: Exception) {}
                        return
                    }
                }
            }
        }

        // Fallback: single hazard selection
        val target = upcomingHazard(loc, nonZones) ?: return
        val key = hazardKey(target)
        if ((preQuietUntil[key] ?: 0L) > now) return
        val lastForThis = lastPerHazard[key] ?: 0L
        val moving = loc.speed > 2.0 // m/s ≈ 7.2 km/h
        if (now - lastForThis < QUIET_MS) {
            if (!moving && now - lastForThis >= QUIET_MS) {
                // allow below
            } else {
                return
            }
        }
        lastPerHazard[key] = now
        lastAlertTime = now

        val dMeters = distanceMeters(loc.latitude, loc.longitude, target.lat, target.lng).roundToInt()
        val friendly = target.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        val text = friendly + " in " + nearestNiceDistance(dMeters)

        var followUp: String? = null
        try {
            val next = nextHazardAfter(loc, allForNext, target)
            if (next != null) {
                val (nLat, nLng) = targetPoint(next)
                val nextDist = distanceMeters(loc.latitude, loc.longitude, nLat, nLng)
                if (nextDist <= lead) {
                    val nextFriendly = next.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                    val nice = nearestNiceDistance(nextDist.roundToInt())
                    followUp = "Next, ${nextFriendly} ${nice} away"
                    // Suppress a full repeat for this next hazard briefly at slow speeds
                    if (speedKph < 40.0) {
                        preQuietUntil[hazardKey(next)] = now + NEXT_QUIET_SLOW_MS
                    }
                }
            }
        } catch (_: Exception) {}

        // Status-bar notifications removed; rely on TTS (and optional haptics)
        if (AppPrefs.isHapticsEnabled(context)) {
            try { com.roadwatch.core.util.Haptics.tap(context) } catch (_: Exception) {}
        }
        if (ttsReady && AppPrefs.isAudioEnabled(context)) {
            val f = followUp
            if (f != null) speakDouble(text, f) else speakWithFocus(text)
        }
        sendOverlay(text)
        sendUiStateUpdate("HAZARD_APPROACHING", null)
        try {
            AppPrefs.setLastHazardDirection(context, target.directionality)
        } catch (_: Exception) {}
    }

    private fun speakWithFocus(text: String) {
        speakUtterances(listOf(text))
    }

    private fun speakDouble(first: String, second: String) {
        speakUtterances(listOf(first, second))
    }

    private fun speakUtterances(lines: List<String>) {
        if (lines.isEmpty()) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val route = prepareAudioRoute(am)
        if (!ttsReady) {
            route.cleanup?.invoke()
            return
        }

        ensurePlaybackVolume(route.stream)
        val mode = AppPrefs.getAudioFocusMode(context)
        val focusGain = if (mode == "EXCLUSIVE") AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val usage = if (route.stream == AudioManager.STREAM_VOICE_CALL) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afr = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(route.stream)
                        .build()
                )
                .build()
            am.requestAudioFocus(afr)
            val remaining = AtomicInteger(lines.size)
            val cleaned = AtomicBoolean(false)
            fun finish() {
                if (cleaned.compareAndSet(false, true)) {
                    try { am.abandonAudioFocusRequest(afr) } catch (_: Exception) {}
                    route.cleanup?.invoke()
                }
            }
            tts?.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener() {
                @Deprecated("Deprecated in UtteranceProgressListener", level = DeprecationLevel.HIDDEN)
                override fun onStart(utteranceId: String?) {}
                @Deprecated("Deprecated in UtteranceProgressListener", level = DeprecationLevel.HIDDEN)
                override fun onError(utteranceId: String?) { finish() }
                @Deprecated("Deprecated in UtteranceProgressListener", level = DeprecationLevel.HIDDEN)
                override fun onDone(utteranceId: String?) {
                    if (remaining.decrementAndGet() <= 0) finish()
                }
            })
            speakTexts(lines, route.stream)
        } else {
            val remaining = AtomicInteger(lines.size)
            val cleaned = AtomicBoolean(false)
            fun finish() {
                if (cleaned.compareAndSet(false, true)) {
                    route.cleanup?.invoke()
                }
            }
            tts?.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener() {
                @Deprecated("Deprecated in UtteranceProgressListener", level = DeprecationLevel.HIDDEN)
                override fun onStart(utteranceId: String?) {}
                @Deprecated("Deprecated in UtteranceProgressListener", level = DeprecationLevel.HIDDEN)
                override fun onError(utteranceId: String?) { finish() }
                @Deprecated("Deprecated in UtteranceProgressListener", level = DeprecationLevel.HIDDEN)
                override fun onDone(utteranceId: String?) {
                    if (remaining.decrementAndGet() <= 0) finish()
                }
            })
            speakTexts(lines, route.stream)
        }
    }

    private fun ensurePlaybackVolume(stream: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(stream)
            if (max <= 0) return
            val current = am.getStreamVolume(stream)
            if (current < max) {
                am.setStreamVolume(stream, max, 0)
            }
        } catch (_: Exception) {}
    }

    private fun speakTexts(lines: List<String>, stream: Int) {
        val baseId = System.nanoTime().toString()
        lines.forEachIndexed { index, line ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = "$baseId-$index"
            speakText(line, queueMode, stream, utteranceId)
        }
    }

    private fun speakText(text: String, queueMode: Int, stream: Int, utteranceId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
            }
            tts?.speak(text, queueMode, params, utteranceId)
        } else {
            val params = java.util.HashMap<String, String>().apply {
                put(TextToSpeech.Engine.KEY_PARAM_STREAM, stream.toString())
                put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            @Suppress("DEPRECATION")
            tts?.speak(text, queueMode, params)
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareAudioRoute(am: AudioManager): AudioRoute {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val TYPE_BLUETOOTH_CAR_AUDIO = 26 // Added in API 34
            val hasA2dp = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == TYPE_BLUETOOTH_CAR_AUDIO }
            if (hasA2dp) {
                updateTtsAudioAttributes(false)
                try { am.setSpeakerphoneOn(false) } catch (_: Exception) {}
                return AudioRoute(AudioManager.STREAM_MUSIC, null)
            }
            val hasSco = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (hasSco && am.isBluetoothScoAvailableOffCall) {
                val prevMode = am.mode
                val prevSpeaker = am.isSpeakerphoneOn
                val started = try {
                    if (!am.isBluetoothScoOn) {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        am.setSpeakerphoneOn(false)
                        am.startBluetoothSco()
                        am.setBluetoothScoOn(true)
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    try { am.mode = prevMode } catch (_: Exception) {}
                    try { am.isSpeakerphoneOn = prevSpeaker } catch (_: Exception) {}
                    false
                }
                updateTtsAudioAttributes(true)
                val cleanup = if (started) {
                    {
                        try { am.setBluetoothScoOn(false) } catch (_: Exception) {}
                        try { am.stopBluetoothSco() } catch (_: Exception) {}
                        try { am.mode = prevMode } catch (_: Exception) {}
                        try { am.setSpeakerphoneOn(prevSpeaker) } catch (_: Exception) {}
                        updateTtsAudioAttributes(false)
                    }
                } else null
                return AudioRoute(AudioManager.STREAM_VOICE_CALL, cleanup)
            }
        } else {
            @Suppress("DEPRECATION")
            val a2dpOn = am.isBluetoothA2dpOn
            if (a2dpOn) {
                updateTtsAudioAttributes(false)
                return AudioRoute(AudioManager.STREAM_MUSIC, null)
            }
            @Suppress("DEPRECATION")
            val scoOn = am.isBluetoothScoOn
            if (scoOn && am.isBluetoothScoAvailableOffCall) {
                updateTtsAudioAttributes(true)
                return AudioRoute(AudioManager.STREAM_VOICE_CALL, null)
            }
        }
        updateTtsAudioAttributes(false)
        return AudioRoute(AudioManager.STREAM_MUSIC, null)
    }

    private fun updateTtsAudioAttributes(useSco: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val usage = if (useSco) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            val stream = if (useSco) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            val attrs = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(stream)
                .build()
            tts?.setAudioAttributes(attrs)
        } catch (_: Exception) {}
    }

    private data class AudioRoute(
        val stream: Int,
        val cleanup: (() -> Unit)?
    )

    private fun sendOverlay(text: String) {
        try {
            val intent = android.content.Intent("com.roadwatch.ALERT_OVERLAY").apply { putExtra("text", text) }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun sendUiStateUpdate(state: String, speedLimit: Int?) {
        try {
            val intent = android.content.Intent("com.roadwatch.UI_STATE_UPDATE").apply {
                putExtra("state", state)
                speedLimit?.let { putExtra("speed_limit", it) }
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun upcomingHazard(loc: Location, hazards: List<Hazard>): Hazard? {
        val lead = leadDistanceMeters(loc.speed * 3.6) // speed m/s → km/h
        var best: Hazard? = null
        var bestAlong = Double.MAX_VALUE
        val hasBearing = loc.hasBearing()
        val headingRad = Math.toRadians(loc.bearing.toDouble())
        if (!hasBearing) return null
        hazards.forEach { h ->
            if (h.directionality == "OPPOSITE") return@forEach
            val d = distanceMeters(loc.latitude, loc.longitude, h.lat, h.lng)
            if (d > lead) return@forEach
            // Dual-carriage filter: heading agreement and lateral offset
            if (hasBearing) {
                val bearingTo = bearingRadians(loc.latitude, loc.longitude, h.lat, h.lng)
                val headingDiffDeg = Math.toDegrees(angleDelta(headingRad, bearingTo).absoluteValue)
                if (h.directionality == "ONE_WAY" && headingDiffDeg > ONE_WAY_MAX_HEADING_DEG) return@forEach
                if (headingDiffDeg > MIN_HEADING_AGREE_DEG) return@forEach
                val lateral = lateralOffsetMeters(d, headingRad, bearingTo)
                if (abs(lateral) > MAX_LATERAL_OFFSET_METERS) return@forEach
                // Ensure hazard is actually ahead, not behind
                val along = d * kotlin.math.cos(angleDelta(headingRad, bearingTo))
                if (along <= 0.0) return@forEach
                if (along < bestAlong) {
                    best = h
                    bestAlong = along
                }
                return@forEach
            }
            // No bearing; fall back to nearest (rare)
            if (d < bestAlong) { best = h; bestAlong = d }
        }
        return best
    }

    private fun nextHazardAfter(loc: Location, hazards: List<Hazard>, current: Hazard): Hazard? {
        val lead = leadDistanceMeters(loc.speed * 3.6)
        val currentDist = distanceMeters(loc.latitude, loc.longitude, current.lat, current.lng)
        var best: Hazard? = null
        var bestAlong = Double.MAX_VALUE
        val hasBearing = loc.hasBearing()
        val headingRad = Math.toRadians(loc.bearing.toDouble())
        if (!hasBearing) return null
        hazards.forEach { h ->
            if (h.directionality == "OPPOSITE") return@forEach
            if (h === current) return@forEach
            val (tLat, tLng) = targetPoint(h)
            val d = distanceMeters(loc.latitude, loc.longitude, tLat, tLng)
            if (d <= currentDist + 15.0) return@forEach // must be after current and not essentially the same point
            if (d > lead) return@forEach
            if (hasBearing) {
                val bearingTo = bearingRadians(loc.latitude, loc.longitude, tLat, tLng)
                val headingDiffDeg = Math.toDegrees(angleDelta(headingRad, bearingTo).absoluteValue)
                if (h.directionality == "ONE_WAY" && headingDiffDeg > ONE_WAY_MAX_HEADING_DEG) return@forEach
                if (headingDiffDeg > MIN_HEADING_AGREE_DEG) return@forEach
                val lateral = lateralOffsetMeters(d, headingRad, bearingTo)
                if (abs(lateral) > MAX_LATERAL_OFFSET_METERS) return@forEach
                val along = d * kotlin.math.cos(angleDelta(headingRad, bearingTo))
                if (along <= 0.0) return@forEach
                if (along < bestAlong) { best = h; bestAlong = along }
                return@forEach
            }
            if (d < bestAlong) { best = h; bestAlong = d }
        }
        return best
    }

    private fun targetPoint(h: Hazard): Pair<Double, Double> {
        return if (h.type.name == "SPEED_LIMIT_ZONE" && h.zoneStartLat != null && h.zoneStartLng != null) {
            h.zoneStartLat to h.zoneStartLng
        } else h.lat to h.lng
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

    // ---- Clustering helpers ----
    private data class Ahead(val h: Hazard, val dist: Double, val along: Double)

    private fun aheadHazards(loc: Location, hazards: List<Hazard>, lead: Double): List<Ahead> {
        val hasBearing = loc.hasBearing()
        val headingRad = Math.toRadians(loc.bearing.toDouble())
        val list = mutableListOf<Ahead>()
        hazards.forEach { h ->
            if (h.directionality == "OPPOSITE") return@forEach
            val d = distanceMeters(loc.latitude, loc.longitude, h.lat, h.lng)
            if (d > lead) return@forEach
            if (hasBearing) {
                val bTo = bearingRadians(loc.latitude, loc.longitude, h.lat, h.lng)
                val headingDiffDeg = Math.toDegrees(angleDelta(headingRad, bTo).absoluteValue)
                if (h.directionality == "ONE_WAY" && headingDiffDeg > ONE_WAY_MAX_HEADING_DEG) return@forEach
                if (headingDiffDeg > MIN_HEADING_AGREE_DEG) return@forEach
                val lateral = lateralOffsetMeters(d, headingRad, bTo)
                if (abs(lateral) > MAX_LATERAL_OFFSET_METERS) return@forEach
                val along = d * cos(angleDelta(headingRad, bTo))
                if (along < 0) return@forEach // behind
                list += Ahead(h, d, along)
            } else {
                list += Ahead(h, d, d)
            }
        }
        return list.sortedBy { it.along }
    }

    private fun buildClusters(ahead: List<Ahead>, lead: Double): List<List<Ahead>> {
        if (ahead.isEmpty()) return emptyList()
        val gap = max(30.0, min(0.3 * lead, 150.0))
        val clusters = mutableListOf<MutableList<Ahead>>()
        var current = mutableListOf<Ahead>()
        current.add(ahead.first())
        for (i in 1 until ahead.size) {
            val prev = ahead[i - 1]
            val cur = ahead[i]
            val delta = cur.along - prev.along
            if (delta <= gap) {
                current.add(cur)
            } else {
                clusters.add(current)
                current = mutableListOf(cur)
            }
        }
        clusters.add(current)
        return clusters
    }

    private fun clusterLabel(cluster: List<Ahead>): Pair<String, Boolean> {
        val types = cluster.map { it.h.type }
        val hasBump = types.contains(com.roadwatch.data.HazardType.SPEED_BUMP)
        val hasRumble = types.contains(com.roadwatch.data.HazardType.RUMBLE_STRIP)
        val top = when {
            hasBump -> "Speed bump"
            hasRumble -> "Rumble strip"
            else -> cluster.first().h.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
        val showType = cluster.size > 1 && (hasBump || hasRumble)
        return if (cluster.size == 1) top to true else top to showType
    }

    companion object {
        private const val MIN_GAP_MS = 10_000L
        private const val MIN_HEADING_AGREE_DEG = 15.0
        private const val MAX_LATERAL_OFFSET_METERS = 7.0
        private const val QUIET_MS = 30_000L
        private const val ONE_WAY_MAX_HEADING_DEG = 10.0
        private const val NEXT_QUIET_SLOW_MS = 20_000L
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
        if (zones.isEmpty()) {
            if (inZone) {
                inZone = false
                sendUiStateUpdate("NORMAL", null)
                val msg = if (zoneLimitKph != null) "Exiting speed limit zone" else AppPrefs.getZoneExit(context)
                if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
                sendOverlay(msg)
                zoneEntryLat = null; zoneEntryLng = null; zoneLength = null; zoneLimitKph = null; zoneExitWarned = false
            }
            return
        }
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
            if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
            sendOverlay(msg)
        } else if (inZone && inside) {
            if (now - lastZoneRepeat >= AppPrefs.getZoneRepeatMs(context)) {
                lastZoneRepeat = now
                val limit = zoneLimitKph
                val msg = if (limit != null) "Speed limit ${limit} km/h" else AppPrefs.getZoneEnter(context)
                if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
                sendOverlay(msg)
            }

            // Check speed and update UI state
            val limit = zoneLimitKph
            if (limit != null) {
                val speedKph = loc.speed * 3.6
                val overspeed = speedKph - limit
                val state = when {
                    overspeed > 10 -> "CRITICAL"
                    overspeed > 0 -> "WARNING"
                    else -> "NORMAL"
                }
                sendUiStateUpdate(state, limit)
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
                        if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
                        sendOverlay(msg)
                        zoneExitWarned = true
                    }
                }
            }
        } else if (inZone && !inside) {
            inZone = false
            sendUiStateUpdate("NORMAL", null)
            val msg = if (zoneLimitKph != null) "Exiting speed limit zone" else AppPrefs.getZoneExit(context)
            if (ttsReady && AppPrefs.isAudioEnabled(context)) speakWithFocus(msg)
            sendOverlay(msg)
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
