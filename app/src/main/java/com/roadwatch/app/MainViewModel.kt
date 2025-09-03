package com.roadwatch.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadwatch.data.repository.HazardRepository
import kotlinx.coroutines.launch

class MainViewModel(
    private val hazardRepository: HazardRepository
) : ViewModel() {

    fun initializeApp() {
        viewModelScope.launch {
            // Load seed data on first launch
            hazardRepository.reloadSeeds(replaceExisting = false)
        }
    }
}
