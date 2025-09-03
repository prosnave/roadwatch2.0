package com.roadwatch.feature.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * HomeViewModel now persists and exposes the Drive Mode active state using SharedPreferences.
 * Uses AndroidViewModel to obtain application context for simple persistence.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("roadwatch_prefs", Context.MODE_PRIVATE)

    private val _isDriveModeActive = MutableStateFlow(prefs.getBoolean("drive_mode_active", false))
    val isDriveModeActive: StateFlow<Boolean> = _isDriveModeActive.asStateFlow()

    fun setDriveModeActive(active: Boolean) {
        _isDriveModeActive.value = active
        prefs.edit().putBoolean("drive_mode_active", active).apply()
    }

    fun startDriveMode() {
        viewModelScope.launch {
            setDriveModeActive(true)
        }
    }

    fun stopDriveMode() {
        viewModelScope.launch {
            setDriveModeActive(false)
        }
    }
}
