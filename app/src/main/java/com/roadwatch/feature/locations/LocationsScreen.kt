package com.roadwatch.feature.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocationsScreen(
    onBack: () -> Unit,
    viewModel: LocationsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val userHazards by viewModel.userHazards.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var hazardToDelete by remember { mutableStateOf<Hazard?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "My Reported Hazards",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else if (userHazards.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(userHazards) { hazard ->
                    HazardCard(
                        hazard = hazard,
                        onEdit = { /* TODO: Implement edit functionality */ },
                        onDelete = { hazardToDelete = hazard }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Back button
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }

    // Delete confirmation dialog
    hazardToDelete?.let { hazard ->
        DeleteHazardDialog(
            hazard = hazard,
            onConfirm = {
                viewModel.deleteHazard(hazard.id)
                hazardToDelete = null
            },
            onDismiss = { hazardToDelete = null }
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No hazards reported yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Hazards you report will appear here for management",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HazardCard(
    hazard: Hazard,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getHazardDisplayName(hazard.type),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Lat: ${"%.6f".format(hazard.lat)}, Lon: ${"%.6f".format(hazard.lon)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    hazard.headingDeg?.let { bearing ->
                        Text(
                            text = "Direction: ${getBearingDirection(bearing)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "Reported: ${formatDate(hazard.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit hazard",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete hazard",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteHazardDialog(
    hazard: Hazard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete Hazard",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete this ${getHazardDisplayName(hazard.type).lowercase()}? This action cannot be undone.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun getHazardDisplayName(type: HazardType): String {
    return when (type) {
        HazardType.SPEED_BUMP -> "Speed Bump"
        HazardType.RUMBLE_STRIP -> "Rumble Strip"
        HazardType.POTHOLE -> "Pothole"
        HazardType.DEBRIS -> "Debris"
        HazardType.POLICE -> "Police Checkpoint"
        HazardType.SPEED_LIMIT_ZONE -> "Speed Limit Zone"
    }
}

private fun getBearingDirection(bearing: Float): String {
    return when {
        bearing >= 337.5 || bearing < 22.5 -> "North"
        bearing >= 22.5 && bearing < 67.5 -> "Northeast"
        bearing >= 67.5 && bearing < 112.5 -> "East"
        bearing >= 112.5 && bearing < 157.5 -> "Southeast"
        bearing >= 157.5 && bearing < 202.5 -> "South"
        bearing >= 202.5 && bearing < 247.5 -> "Southwest"
        bearing >= 247.5 && bearing < 292.5 -> "West"
        bearing >= 292.5 && bearing < 337.5 -> "Northwest"
        else -> "${bearing.toInt()}°"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
