package com.roadwatch.feature.passenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadwatch.core.location.LocationData
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardType
import com.roadwatch.data.repository.HazardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PassengerModeViewModel(
    private val hazardRepository: HazardRepository
) : ViewModel() {

    private val _currentLocation = MutableStateFlow<LocationData?>(null)

    val locationAccuracy = _currentLocation
        .map { it?.accuracy }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _selectedHazardType = MutableStateFlow<HazardType?>(null)
    val selectedHazardType: StateFlow<HazardType?> = _selectedHazardType.asStateFlow()

    private val _selectedDirection = MutableStateFlow<Direction?>(null)
    val selectedDirection: StateFlow<Direction?> = _selectedDirection.asStateFlow()

    fun updateLocation(locationData: LocationData) {
        _currentLocation.value = locationData
    }

    fun selectHazardType(type: HazardType) {
        _selectedHazardType.value = type
    }

    fun setDirection(direction: Direction) {
        _selectedDirection.value = direction
    }

    fun reportHazard() {
        viewModelScope.launch {
            val location = _currentLocation.value ?: return@launch
            val hazardType = _selectedHazardType.value ?: return@launch

            // Calculate bearing based on direction selection
            val bearing = when (_selectedDirection.value) {
                Direction.THIS_WAY -> location.bearing.takeIf { it != 0f }
                Direction.OPPOSITE -> (location.bearing + 180f) % 360f
                null -> location.bearing.takeIf { it != 0f }
            }

            val hazard = Hazard(
                type = hazardType,
                lat = location.latitude,
                lon = location.longitude,
                headingDeg = bearing
            )

            hazardRepository.addHazard(hazard)

            // Reset selection
            _selectedHazardType.value = null
            _selectedDirection.value = null
        }
    }
}
