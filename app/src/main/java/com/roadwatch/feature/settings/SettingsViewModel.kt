package com.roadwatch.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadwatch.core.tts.TextToSpeechManager
import com.roadwatch.core.util.ImportExportManager
import com.roadwatch.data.repository.HazardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val textToSpeechManager: TextToSpeechManager,
    private val hazardRepository: HazardRepository,
    private val importExportManager: ImportExportManager
) : ViewModel() {

    val voiceEnabled = textToSpeechManager.voiceEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val vibrationEnabled = textToSpeechManager.vibrationEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val alertDistance = textToSpeechManager.alertDistance
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 1.0f
        )

    private val _exportResult = MutableStateFlow<ImportExportManager.ExportResult?>(null)
    val exportResult: StateFlow<ImportExportManager.ExportResult?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<ImportExportManager.ImportResult?>(null)
    val importResult: StateFlow<ImportExportManager.ImportResult?> = _importResult.asStateFlow()

    private val _isOperationInProgress = MutableStateFlow(false)
    val isOperationInProgress: StateFlow<Boolean> = _isOperationInProgress.asStateFlow()

    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            textToSpeechManager.setVoiceEnabled(enabled)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            textToSpeechManager.setVibrationEnabled(enabled)
        }
    }

    fun setAlertDistance(distance: Float) {
        viewModelScope.launch {
            textToSpeechManager.setAlertDistance(distance)
        }
    }

    fun testAlert() {
        viewModelScope.launch {
            textToSpeechManager.speakAlert("Test alert: Speed bump ahead in 200 meters.", 0L)
        }
    }

    fun reloadSeeds() {
        viewModelScope.launch {
            // In a real implementation, you'd show a confirmation dialog
            // For now, we'll reload with replaceExisting = false (safe mode)
            hazardRepository.reloadSeeds(replaceExisting = false)
        }
    }

    fun exportData(uri: Uri) {
        viewModelScope.launch {
            _isOperationInProgress.value = true
            _exportResult.value = null

            try {
                val result = importExportManager.exportHazards(uri)
                _exportResult.value = result
            } catch (e: Exception) {
                _exportResult.value = ImportExportManager.ExportResult.Error(e.message ?: "Unknown error")
            } finally {
                _isOperationInProgress.value = false
            }
        }
    }

    fun importData(uri: Uri) {
        viewModelScope.launch {
            _isOperationInProgress.value = true
            _importResult.value = null

            try {
                val result = importExportManager.importHazards(uri)
                _importResult.value = result
            } catch (e: Exception) {
                _importResult.value = ImportExportManager.ImportResult.Error(e.message ?: "Unknown error")
            } finally {
                _isOperationInProgress.value = false
            }
        }
    }

    fun clearOperationResults() {
        _exportResult.value = null
        _importResult.value = null
    }
}
