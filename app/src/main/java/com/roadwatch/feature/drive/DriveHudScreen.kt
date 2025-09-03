package com.roadwatch.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.roadwatch.core.location.LocationData
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardType
import com.roadwatch.domain.usecases.ProximityAlertEngine

@Composable
fun DriveHudScreen(
    onStopDriveMode: () -> Unit,
    onOpenPassengerMode: () -> Unit,
    viewModel: DriveHudViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val currentLocation by viewModel.currentLocation.collectAsState()
    val nearbyHazards by viewModel.nearbyHazards.collectAsState()
    val activeAlerts by viewModel.activeAlerts.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()

    var showReportDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map
        DriveMap(
            currentLocation = currentLocation,
            nearbyHazards = nearbyHazards,
            modifier = Modifier.fillMaxSize()
        )

        // Top status bar
        DriveStatusBar(
            currentLocation = currentLocation,
            isMuted = isMuted,
            onToggleMute = { viewModel.toggleMute() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter)
        )

        // Active alerts overlay
        if (activeAlerts.isNotEmpty()) {
            ActiveAlertsOverlay(
                alerts = activeAlerts,
                onDismissAlert = { viewModel.clearAlert(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            )
        }

        // Bottom control panel
        DriveControlPanel(
            onStopDriveMode = onStopDriveMode,
            onOpenPassengerMode = onOpenPassengerMode,
            onReportHazard = { showReportDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        )

        // Report hazard dialog
        if (showReportDialog) {
            ReportHazardDialog(
                onConfirm = {
                    viewModel.reportCurrentLocationHazard()
                    showReportDialog = false
                },
                onDismiss = { showReportDialog = false }
            )
        }
    }
}

@Composable
private fun DriveMap(
    currentLocation: LocationData?,
    nearbyHazards: List<Hazard>,
    modifier: Modifier = Modifier
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            currentLocation?.let { LatLng(it.latitude, it.longitude) }
                ?: LatLng(-1.2864, 36.8172), // Nairobi default
            15f
        )
    }

    // Update camera when location changes
    currentLocation?.let { location ->
        val latLng = LatLng(location.latitude, location.longitude)
        cameraPositionState.position = CameraPosition.fromLatLngZoom(latLng, 17f)
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            mapType = MapType.NORMAL,
            isMyLocationEnabled = true
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = true,
            mapToolbarEnabled = false
        )
    ) {
        // Current location marker (if not using my location)
        currentLocation?.let { location ->
            Marker(
                state = MarkerState(position = LatLng(location.latitude, location.longitude)),
                title = "Current Location",
                snippet = "Speed: ${location.speedKmh.toInt()} km/h"
            )
        }

        // Hazard markers
        nearbyHazards.forEach { hazard ->
            Marker(
                state = MarkerState(position = hazard.position),
                title = getHazardTitle(hazard),
                snippet = getHazardSnippet(hazard),
                icon = getHazardIcon(hazard.type)
            )
        }
    }
}

@Composable
private fun DriveStatusBar(
    currentLocation: LocationData?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed display
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = "${currentLocation?.speedKmh?.toInt() ?: 0}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Mute button
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ActiveAlertsOverlay(
    alerts: List<ProximityAlertEngine.AlertData>,
    onDismissAlert: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        alerts.forEach { alert ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = alert.message,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${alert.distance}m ahead",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }

                    IconButton(
                        onClick = { onDismissAlert(alert.hazardId) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Dismiss alert",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DriveControlPanel(
    onStopDriveMode: () -> Unit,
    onOpenPassengerMode: () -> Unit,
    onReportHazard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onStopDriveMode,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop Drive")
            }

            Spacer(modifier = Modifier.width(8.dp))

            FloatingActionButton(
                onClick = onReportHazard,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Report hazard",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = onOpenPassengerMode,
                modifier = Modifier.weight(1f)
            ) {
                Text("Passenger")
            }
        }
    }
}

@Composable
private fun ReportHazardDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Report Hazard",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = "Report a hazard at your current location? This will help other drivers.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Report")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun getHazardTitle(hazard: Hazard): String {
    return when (hazard.type) {
        HazardType.SPEED_BUMP -> "Speed Bump"
        HazardType.RUMBLE_STRIP -> "Rumble Strip"
        HazardType.POTHOLE -> "Pothole"
        HazardType.DEBRIS -> "Debris"
        HazardType.POLICE -> "Police"
        HazardType.SPEED_LIMIT_ZONE -> "Speed Limit Zone"
    }
}

private fun getHazardSnippet(hazard: Hazard): String {
    return when (hazard.type) {
        HazardType.SPEED_LIMIT_ZONE -> hazard.speedLimitKph?.let { "$it km/h zone" } ?: "Speed limit zone"
        else -> hazard.type.name.lowercase().replace("_", " ")
    }
}

private fun getHazardIcon(hazardType: HazardType): com.google.android.gms.maps.model.BitmapDescriptor? {
    // TODO: Implement custom hazard icons
    // For now, return null to use default marker
    return null
}
