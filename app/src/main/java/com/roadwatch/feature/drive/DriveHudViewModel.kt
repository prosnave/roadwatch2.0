package com.roadwatch.feature.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadwatch.core.location.LocationData
import com.roadwatch.core.tts.TextToSpeechManager
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardType
import com.roadwatch.data.repository.HazardRepository
import com.roadwatch.domain.usecases.ProximityAlertEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DriveHudViewModel(
    private val hazardRepository: HazardRepository,
    private val proximityAlertEngine: ProximityAlertEngine,
    private val textToSpeechManager: TextToSpeechManager
) : ViewModel() {

    private val _currentLocation = MutableStateFlow<LocationData?>(null)
    val currentLocation: StateFlow<LocationData?> = _currentLocation.asStateFlow()

    private val _nearbyHazards = MutableStateFlow<List<Hazard>>(emptyList())
    val nearbyHazards: StateFlow<List<Hazard>> = _nearbyHazards.asStateFlow()

    val activeAlerts: StateFlow<List<ProximityAlertEngine.AlertData>> = proximityAlertEngine.activeAlerts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    init {
        // Load nearby hazards when location changes
        viewModelScope.launch {
            currentLocation.collect { location ->
                location?.let { updateNearbyHazards(it) }
            }
        }

        // Process location updates for alerts
        viewModelScope.launch {
            currentLocation.collect { location ->
                location?.let { proximityAlertEngine.processLocationUpdate(it) }
            }
        }
    }

    fun updateLocation(locationData: LocationData) {
        _currentLocation.value = locationData
    }

    private suspend fun updateNearbyHazards(location: LocationData) {
        // Load hazards within a reasonable radius (e.g., 2km)
        val radiusKm = 2.0
        val latOffset = radiusKm / 111.0 // 1 degree ≈ 111km
        val lonOffset = radiusKm / (111.0 * kotlin.math.cos(Math.toRadians(location.latitude)))

        val minLat = location.latitude - latOffset
        val maxLat = location.latitude + latOffset
        val minLon = location.longitude - lonOffset
        val maxLon = location.longitude + lonOffset

        hazardRepository.getHazardsInBounds(minLat, maxLat, minLon, maxLon)
            .collect { hazards ->
                _nearbyHazards.value = hazards
            }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        // Note: In a real implementation, you'd want to disable TTS when muted
    }

    fun reportCurrentLocationHazard() {
        viewModelScope.launch {
            val location = currentLocation.value ?: return@launch

            val hazard = Hazard(
                type = HazardType.SPEED_BUMP, // Default to speed bump for quick reporting
                lat = location.latitude,
                lon = location.longitude,
                headingDeg = location.bearing.takeIf { it != 0f }
            )

            hazardRepository.addHazard(hazard)
            // Refresh nearby hazards to include the new one
            updateNearbyHazards(location)
        }
    }

    fun clearAlert(hazardId: Long) {
        proximityAlertEngine.clearAlert(hazardId)
    }
}
