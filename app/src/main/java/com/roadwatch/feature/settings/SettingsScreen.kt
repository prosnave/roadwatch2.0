package com.roadwatch.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roadwatch.BuildConfig
import com.roadwatch.core.util.ImportExportManager
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val voiceEnabled by viewModel.voiceEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()
    val alertDistance by viewModel.alertDistance.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val isOperationInProgress by viewModel.isOperationInProgress.collectAsState()

    // File picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportData(it) }
    }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importData(it) }
    }

    // Handle export result
    exportResult?.let { result ->
        when (result) {
            is ImportExportManager.ExportResult.Success -> {
                scope.launch {
                    snackbarHostState.showSnackbar("Exported ${result.exportedCount} hazards successfully")
                }
            }
            is ImportExportManager.ExportResult.Error -> {
                scope.launch {
                    snackbarHostState.showSnackbar("Export failed: ${result.message}")
                }
            }
        }
        viewModel.clearOperationResults()
    }

    // Handle import result
    importResult?.let { result ->
        when (result) {
            is ImportExportManager.ImportResult.Success -> {
                scope.launch {
                    snackbarHostState.showSnackbar("Imported ${result.added} hazards, updated ${result.updated}, skipped ${result.skipped}")
                }
            }
            is ImportExportManager.ImportResult.Error -> {
                scope.launch {
                    snackbarHostState.showSnackbar("Import failed: ${result.message}")
                }
            }
        }
        viewModel.clearOperationResults()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Voice alerts setting
        SettingCard(
            title = "Voice Announcements",
            subtitle = "Speak hazard alerts aloud",
            control = {
                Switch(
                    checked = voiceEnabled,
                    onCheckedChange = { viewModel.setVoiceEnabled(it) }
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Vibration setting
        SettingCard(
            title = "Vibration Alerts",
            subtitle = "Vibrate on hazard detection",
            control = {
                Switch(
                    checked = vibrationEnabled,
                    onCheckedChange = { viewModel.setVibrationEnabled(it) }
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Alert distance setting
        AlertDistanceCard(
            currentDistance = alertDistance,
            onDistanceChanged = { viewModel.setAlertDistance(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Test alert button
        Button(
            onClick = { viewModel.testAlert() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test Alert")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Data management section
        Text(
            text = "Data Management",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Reload seeds button
        OutlinedButton(
            onClick = { viewModel.reloadSeeds() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reload Seed Hazards")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Export data button
        OutlinedButton(
            onClick = { exportLauncher.launch("roadwatch_hazards_${System.currentTimeMillis()}.csv") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isOperationInProgress
        ) {
            Text(if (isOperationInProgress) "Exporting..." else "Export Hazards")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Import data button
        OutlinedButton(
            onClick = { importLauncher.launch("text/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isOperationInProgress
        ) {
            Text(if (isOperationInProgress) "Importing..." else "Import Hazards")
        }

        // Admin features
        if (BuildConfig.IS_ADMIN) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Admin Features",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { /* Navigate to manage locations */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage All Locations")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Back button
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    control: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            control()
        }
    }
}

@Composable
private fun AlertDistanceCard(
    currentDistance: Float,
    onDistanceChanged: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Alert Distance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "How far ahead to warn about hazards",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Slider(
                value = currentDistance,
                onValueChange = onDistanceChanged,
                valueRange = 0.5f..2.0f,
                steps = 5,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "${(currentDistance * 100).toInt()}% (${calculateEffectiveDistance(currentDistance)}m at 50km/h)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

private fun calculateEffectiveDistance(multiplier: Float): Int {
    // Base distance at 50km/h: 50km/h * 1.2s = 16.67m, multiplied by alert distance
    return (16.67f * multiplier).toInt()
}
