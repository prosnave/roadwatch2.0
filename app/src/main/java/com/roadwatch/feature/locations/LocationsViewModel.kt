package com.roadwatch.feature.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardSource
import com.roadwatch.data.repository.HazardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LocationsViewModel(
    private val hazardRepository: HazardRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val userHazards: StateFlow<List<Hazard>> = hazardRepository.getAllActiveHazards()
        .map { hazards ->
            hazards.filter { it.source == HazardSource.USER }
                .sortedByDescending { it.createdAt }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteHazard(hazardId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                hazardRepository.deleteHazard(hazardId)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateHazard(hazard: Hazard) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                hazardRepository.updateHazard(hazard)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
