package com.roadwatch.domain.usecases

import com.google.android.gms.maps.model.LatLng
import com.roadwatch.core.location.LocationData
import com.roadwatch.core.tts.TextToSpeechManager
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardType
import com.roadwatch.data.repository.HazardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class ProximityAlertEngine @Inject constructor(
    private val hazardRepository: HazardRepository,
    private val textToSpeechManager: TextToSpeechManager
) {

    private val scope = CoroutineScope(Dispatchers.Default)

    // Track recently alerted hazards to prevent spam (permanent suppression window)
    private val recentlyAlertedHazards = mutableMapOf<Long, Long>()
    private val SUPPRESSION_DURATION_MS = 5 * 60 * 1000L // 5 minutes

    // Hysteresis & rate limiting
    private val hazardState = mutableMapOf<Long, HazardState>()
    private val HYSTERESIS_CLEAR_METERS = 30 // meters to clear an active alert (add-on to warnDistance)
    private val MIN_TTS_INTERVAL_MS = 2_000L // global minimum between TTS announcements
    private var lastTtsTimestamp = 0L

    // Distance beyond which we consider the user safely past the hazard (avoid re-alert)
    private val BEYOND_DISTANCE_M = 100.0

    private val _activeAlerts = MutableStateFlow<List<AlertData>>(emptyList())
    val activeAlerts: StateFlow<List<AlertData>> = _activeAlerts.asStateFlow()
+
+    private data class HazardState(
+        var lastDistance: Int = Int.MAX_VALUE,
+        var lastSeenAt: Long = 0L,
+        var alerted: Boolean = false,
+        var lastAlertAt: Long = 0L
+    )
 
     init {
         scope.launch {
             // Clean up old alerts periodically
             while (true) {
                 kotlinx.coroutines.delay(60_000) // Every minute
                 cleanupOldAlerts()
             }
         }
     }
 
     fun processLocationUpdate(locationData: LocationData) {
-        scope.launch {
-            val alertDistance = textToSpeechManager.alertDistance.first()
-            val warnDistance = calculateWarnDistance(locationData.speedKmh, alertDistance)
-
-            // Build look-ahead corridor
-            val corridor = buildLookAheadCorridor(locationData, warnDistance)
-
-            // Query hazards in bounds
-            val nearbyHazards = hazardRepository.getHazardsInBounds(
-                minLat = corridor.southWest.latitude,
-                maxLat = corridor.northEast.latitude,
-                minLon = corridor.southWest.longitude,
-                maxLon = corridor.northEast.longitude
-            ).first()
-
-            // Filter hazards in corridor and check direction
-            val relevantHazards = nearbyHazards.filter { hazard ->
-                isHazardInCorridor(hazard, corridor, locationData) &&
-                !isRecentlyAlerted(hazard.id) &&
-                isCorrectDirection(hazard, locationData.bearing)
-            }
-
-            // Sort by distance and take closest
-            val sortedHazards = relevantHazards.sortedBy { hazard ->
-                calculateDistance(locationData.latitude, locationData.longitude,
-                                hazard.lat, hazard.lon)
-            }
-
-            // Process alerts
-            val newAlerts = mutableListOf<AlertData>()
-            for (hazard in sortedHazards) {
-                val distance = calculateDistance(locationData.latitude, locationData.longitude,
-                                              hazard.lat, hazard.lon).toInt()
-
-                if (distance <= warnDistance) {
-                    val alertMessage = createAlertMessage(hazard, distance)
-                    val alertData = AlertData(hazard.id, hazard.type, distance, alertMessage)
-
-                    // Speak alert
-                    textToSpeechManager.speakAlert(alertMessage, hazard.id)
-
-                    // Mark as alerted
-                    recentlyAlertedHazards[hazard.id] = System.currentTimeMillis()
-
-                    newAlerts.add(alertData)
-
-                    // Only alert the closest hazard to avoid spam
-                    break
-                }
-            }
-
-            _activeAlerts.value = newAlerts
-        }
+        scope.launch {
+            val alertDistance = textToSpeechManager.alertDistance.first()
+            val warnDistance = calculateWarnDistance(locationData.speedKmh, alertDistance)
+
+            // Build look-ahead corridor
+            val corridor = buildLookAheadCorridor(locationData, warnDistance)
+
+            // Query hazards in bounds
+            val nearbyHazards = hazardRepository.getHazardsInBounds(
+                minLat = corridor.southWest.latitude,
+                maxLat = corridor.northEast.latitude,
+                minLon = corridor.southWest.longitude,
+                maxLon = corridor.northEast.longitude
+            ).first()
+
+            // Filter hazards in corridor and check direction
+            val relevantHazards = nearbyHazards.filter { hazard ->
+                isHazardInCorridor(hazard, corridor, locationData) &&
+                        isCorrectDirection(hazard, locationData.bearing)
+            }
+
+            // Sort by distance (closest first)
+            val sortedHazards = relevantHazards.sortedBy { hazard ->
+                calculateDistance(locationData.latitude, locationData.longitude,
+                    hazard.lat, hazard.lon)
+            }
+
+            val newAlerts = mutableListOf<AlertData>()
+
+            // Iterate hazards and apply hysteresis + suppression + rate-limiting
+            for (hazard in sortedHazards) {
+                val distance = calculateDistance(locationData.latitude, locationData.longitude,
+                    hazard.lat, hazard.lon).toInt()
+
+                val now = System.currentTimeMillis()
+                val state = hazardState.getOrPut(hazard.id) { HazardState() }
+                state.lastDistance = distance
+                state.lastSeenAt = now
+
+                // If already alerted for this hazard, only clear it once user is sufficiently past it
+                if (state.alerted) {
+                    if (distance > warnDistance + HYSTERESIS_CLEAR_METERS || distance > BEYOND_DISTANCE_M) {
+                        // Clear alerted state so it can be alerted again later (if re-encountered)
+                        state.alerted = false
+                        // Also update recentlyAlerted to allow re-alert after suppression window
+                        recentlyAlertedHazards.remove(hazard.id)
+                    } else {
+                        // keep active alert shown
+                        newAlerts.add(AlertData(hazard.id, hazard.type, distance, createAlertMessage(hazard, distance)))
+                        // Skip other hazards to avoid spamming multiple alerts at once
+                        break
+                    }
+                } else {
+                    // Not currently alerted: decide whether to trigger alert
+                    val suppressed = isRecentlyAlerted(hazard.id)
+                    val canTts = now - lastTtsTimestamp >= MIN_TTS_INTERVAL_MS
+
+                    if (!suppressed && distance <= warnDistance && canTts) {
+                        val alertMessage = createAlertMessage(hazard, distance)
+                        val alertData = AlertData(hazard.id, hazard.type, distance, alertMessage)
+
+                        // Speak alert (TTS manager handles vibration as well)
+                        try {
+                            textToSpeechManager.speakAlert(alertMessage, hazard.id)
+                            lastTtsTimestamp = now
+                        } catch (_: Exception) {
+                            // Ignore TTS failures to avoid crash loops
+                        }
+
+                        // Mark state and global suppression
+                        state.alerted = true
+                        state.lastAlertAt = now
+                        recentlyAlertedHazards[hazard.id] = now
+
+                        newAlerts.add(alertData)
+                        // Only alert the closest hazard to avoid multiple simultaneous alerts
+                        break
+                    }
+                }
+            }
+
+            // Remove hazard states that haven't been seen for a while to keep memory bounded
+            hazardState.entries.removeIf { (_, s) ->
+                now - s.lastSeenAt > 10 * 60_000L // 10 minutes
+            }
+
+            _activeAlerts.value = newAlerts
+        }
     }
 
     private fun buildLookAheadCorridor(location: LocationData, warnDistance: Int): LatLngBounds {
         val bearingRad = Math.toRadians(location.bearing.toDouble())
         val corridorWidth = 15.0 // 15 meters width
 
         // Calculate end point of corridor
         val endLat = location.latitude + (warnDistance / 111320.0) * cos(bearingRad)
         val endLon = location.longitude + (warnDistance / 111320.0) * sin(bearingRad) / cos(Math.toRadians(location.latitude))
 
         // Calculate perpendicular vector for width
         val perpBearing = bearingRad + Math.PI / 2
         val halfWidthLat = (corridorWidth / 2 / 111320.0) * cos(perpBearing)
         val halfWidthLon = (corridorWidth / 2 / 111320.0) * sin(perpBearing) / cos(Math.toRadians(location.latitude))
 
         // Calculate bounds
         val southWest = LatLng(
             minOf(location.latitude, endLat) - abs(halfWidthLat),
             minOf(location.longitude, endLon) - abs(halfWidthLon)
         )
         val northEast = LatLng(
             maxOf(location.latitude, endLat) + abs(halfWidthLat),
             maxOf(location.longitude, endLon) + abs(halfWidthLon)
         )
 
         return LatLngBounds(southWest, northEast)
     }
 
     private fun isHazardInCorridor(hazard: Hazard, corridor: LatLngBounds, location: LocationData): Boolean {
         val hazardPoint = hazard.position
 
         // Check if point is within bounds
         if (hazardPoint.latitude < corridor.southWest.latitude ||
             hazardPoint.latitude > corridor.northEast.latitude ||
             hazardPoint.longitude < corridor.southWest.longitude ||
             hazardPoint.longitude > corridor.northEast.longitude) {
             return false
         }
 
         // Calculate distance to path
         val distanceToPath = calculateDistanceToPath(hazardPoint, location)
         return distanceToPath <= 15.0 // 15 meters from path
     }
 
     private fun calculateDistanceToPath(point: LatLng, location: LocationData): Double {
         // Simplified distance to path calculation
         // In a real implementation, you'd use proper geometry
         return calculateDistance(point.latitude, point.longitude,
                                location.latitude, location.longitude)
     }
 
     private fun isCorrectDirection(hazard: Hazard, userBearing: Float): Boolean {
         hazard.headingDeg?.let { hazardBearing ->
             val bearingDiff = abs(userBearing - hazardBearing)
             val normalizedDiff = minOf(bearingDiff, 360f - bearingDiff)
             return normalizedDiff <= hazard.bearingToleranceDeg
         }
         return true // No direction specified, assume correct
     }
 
     private fun isRecentlyAlerted(hazardId: Long): Boolean {
         val lastAlertTime = recentlyAlertedHazards[hazardId] ?: return false
         return System.currentTimeMillis() - lastAlertTime < SUPPRESSION_DURATION_MS
     }
 
     private fun calculateWarnDistance(speedKmh: Float, alertMultiplier: Float): Int {
         // Base distance: speed * 1.2 seconds converted to meters
         val baseDistance = (speedKmh / 3.6f) * 1.2f // Convert to m/s then multiply by time
         val adjustedDistance = baseDistance * alertMultiplier
         return adjustedDistance.toInt().coerceIn(120, 600) // Min 120m, max 600m
     }
 
     private fun createAlertMessage(hazard: Hazard, distance: Int): String {
         return when (hazard.type) {
             HazardType.SPEED_BUMP -> "Speed bump ahead in $distance meters."
             HazardType.RUMBLE_STRIP -> "Rumble strip ahead in $distance meters."
             HazardType.POTHOLE -> "Pothole ahead in $distance meters."
             HazardType.DEBRIS -> "Debris on road ahead in $distance meters."
             HazardType.POLICE -> "Police ahead in $distance meters."
             HazardType.SPEED_LIMIT_ZONE -> {
                 hazard.speedLimitKph?.let { limit ->
                     "Entering $limit kilometer per hour zone."
                 } ?: "Speed limit zone ahead in $distance meters."
             }
         }
     }
 
     private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
         val earthRadius = 6371000.0 // meters
         val dLat = Math.toRadians(lat2 - lat1)
         val dLon = Math.toRadians(lon2 - lon1)
         val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
         val c = 2 * atan2(sqrt(a), sqrt(1 - a))
         return earthRadius * c
     }
 
     private fun cleanupOldAlerts() {
         val currentTime = System.currentTimeMillis()
         recentlyAlertedHazards.entries.removeIf { (_, timestamp) ->
             currentTime - timestamp > SUPPRESSION_DURATION_MS
         }
     }
 
     fun clearAlert(hazardId: Long) {
         recentlyAlertedHazards.remove(hazardId)
         _activeAlerts.value = _activeAlerts.value.filter { it.hazardId != hazardId }
     }
 
     data class LatLngBounds(
         val southWest: LatLng,
         val northEast: LatLng
     )
 
     data class AlertData(
         val hazardId: Long,
         val hazardType: HazardType,
         val distance: Int,
         val message: String
     )
 }

    init {
        scope.launch {
            // Clean up old alerts periodically
            while (true) {
                kotlinx.coroutines.delay(60_000) // Every minute
                cleanupOldAlerts()
            }
        }
    }

    fun processLocationUpdate(locationData: LocationData) {
        scope.launch {
            val alertDistance = textToSpeechManager.alertDistance.first()
            val warnDistance = calculateWarnDistance(locationData.speedKmh, alertDistance)

            // Build look-ahead corridor
            val corridor = buildLookAheadCorridor(locationData, warnDistance)

            // Query hazards in bounds
            val nearbyHazards = hazardRepository.getHazardsInBounds(
                minLat = corridor.southWest.latitude,
                maxLat = corridor.northEast.latitude,
                minLon = corridor.southWest.longitude,
                maxLon = corridor.northEast.longitude
            ).first()

            // Filter hazards in corridor and check direction
            val relevantHazards = nearbyHazards.filter { hazard ->
                isHazardInCorridor(hazard, corridor, locationData) &&
                !isRecentlyAlerted(hazard.id) &&
                isCorrectDirection(hazard, locationData.bearing)
            }

            // Sort by distance and take closest
            val sortedHazards = relevantHazards.sortedBy { hazard ->
                calculateDistance(locationData.latitude, locationData.longitude,
                                hazard.lat, hazard.lon)
            }

            // Process alerts
            val newAlerts = mutableListOf<AlertData>()
            for (hazard in sortedHazards) {
                val distance = calculateDistance(locationData.latitude, locationData.longitude,
                                              hazard.lat, hazard.lon).toInt()

                if (distance <= warnDistance) {
                    val alertMessage = createAlertMessage(hazard, distance)
                    val alertData = AlertData(hazard.id, hazard.type, distance, alertMessage)

                    // Speak alert
                    textToSpeechManager.speakAlert(alertMessage, hazard.id)

                    // Mark as alerted
                    recentlyAlertedHazards[hazard.id] = System.currentTimeMillis()

                    newAlerts.add(alertData)

                    // Only alert the closest hazard to avoid spam
                    break
                }
            }

            _activeAlerts.value = newAlerts
        }
    }

    private fun buildLookAheadCorridor(location: LocationData, warnDistance: Int): LatLngBounds {
        val bearingRad = Math.toRadians(location.bearing.toDouble())
        val corridorWidth = 15.0 // 15 meters width

        // Calculate end point of corridor
        val endLat = location.latitude + (warnDistance / 111320.0) * cos(bearingRad)
        val endLon = location.longitude + (warnDistance / 111320.0) * sin(bearingRad) / cos(Math.toRadians(location.latitude))

        // Calculate perpendicular vector for width
        val perpBearing = bearingRad + Math.PI / 2
        val halfWidthLat = (corridorWidth / 2 / 111320.0) * cos(perpBearing)
        val halfWidthLon = (corridorWidth / 2 / 111320.0) * sin(perpBearing) / cos(Math.toRadians(location.latitude))

        // Calculate bounds
        val southWest = LatLng(
            minOf(location.latitude, endLat) - abs(halfWidthLat),
            minOf(location.longitude, endLon) - abs(halfWidthLon)
        )
        val northEast = LatLng(
            maxOf(location.latitude, endLat) + abs(halfWidthLat),
            maxOf(location.longitude, endLon) + abs(halfWidthLon)
        )

        return LatLngBounds(southWest, northEast)
    }

    private fun isHazardInCorridor(hazard: Hazard, corridor: LatLngBounds, location: LocationData): Boolean {
        val hazardPoint = hazard.position

        // Check if point is within bounds
        if (hazardPoint.latitude < corridor.southWest.latitude ||
            hazardPoint.latitude > corridor.northEast.latitude ||
            hazardPoint.longitude < corridor.southWest.longitude ||
            hazardPoint.longitude > corridor.northEast.longitude) {
            return false
        }

        // Calculate distance to path
        val distanceToPath = calculateDistanceToPath(hazardPoint, location)
        return distanceToPath <= 15.0 // 15 meters from path
    }

    private fun calculateDistanceToPath(point: LatLng, location: LocationData): Double {
        // Simplified distance to path calculation
        // In a real implementation, you'd use proper geometry
        return calculateDistance(point.latitude, point.longitude,
                               location.latitude, location.longitude)
    }

    private fun isCorrectDirection(hazard: Hazard, userBearing: Float): Boolean {
        hazard.headingDeg?.let { hazardBearing ->
            val bearingDiff = abs(userBearing - hazardBearing)
            val normalizedDiff = minOf(bearingDiff, 360f - bearingDiff)
            return normalizedDiff <= hazard.bearingToleranceDeg
        }
        return true // No direction specified, assume correct
    }

    private fun isRecentlyAlerted(hazardId: Long): Boolean {
        val lastAlertTime = recentlyAlertedHazards[hazardId] ?: return false
        return System.currentTimeMillis() - lastAlertTime < SUPPRESSION_DURATION_MS
    }

    private fun calculateWarnDistance(speedKmh: Float, alertMultiplier: Float): Int {
        // Base distance: speed * 1.2 seconds converted to meters
        val baseDistance = (speedKmh / 3.6f) * 1.2f // Convert to m/s then multiply by time
        val adjustedDistance = baseDistance * alertMultiplier
        return adjustedDistance.toInt().coerceIn(120, 600) // Min 120m, max 600m
    }

    private fun createAlertMessage(hazard: Hazard, distance: Int): String {
        return when (hazard.type) {
            HazardType.SPEED_BUMP -> "Speed bump ahead in $distance meters."
            HazardType.RUMBLE_STRIP -> "Rumble strip ahead in $distance meters."
            HazardType.POTHOLE -> "Pothole ahead in $distance meters."
            HazardType.DEBRIS -> "Debris on road ahead in $distance meters."
            HazardType.POLICE -> "Police ahead in $distance meters."
            HazardType.SPEED_LIMIT_ZONE -> {
                hazard.speedLimitKph?.let { limit ->
                    "Entering $limit kilometer per hour zone."
                } ?: "Speed limit zone ahead in $distance meters."
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun cleanupOldAlerts() {
        val currentTime = System.currentTimeMillis()
        recentlyAlertedHazards.entries.removeIf { (_, timestamp) ->
            currentTime - timestamp > SUPPRESSION_DURATION_MS
        }
    }

    fun clearAlert(hazardId: Long) {
        recentlyAlertedHazards.remove(hazardId)
        _activeAlerts.value = _activeAlerts.value.filter { it.hazardId != hazardId }
    }

    data class LatLngBounds(
        val southWest: LatLng,
        val northEast: LatLng
    )

    data class AlertData(
        val hazardId: Long,
        val hazardType: HazardType,
        val distance: Int,
        val message: String
    )
}
