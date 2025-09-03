package com.roadwatch.feature.passenger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roadwatch.R
import com.roadwatch.data.entities.HazardType

@Composable
fun PassengerModeScreen(
    onBackToDrive: () -> Unit,
    viewModel: PassengerModeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val locationAccuracy by viewModel.locationAccuracy.collectAsState()
    val selectedHazardType by viewModel.selectedHazardType.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "Report Hazard",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Location accuracy indicator
        LocationAccuracyChip(accuracy = locationAccuracy)

        Spacer(modifier = Modifier.height(24.dp))

        // Hazard type selection
        Text(
            text = "Select Hazard Type",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Hazard type grid
        HazardTypeGrid(
            selectedType = selectedHazardType,
            onTypeSelected = { viewModel.selectHazardType(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Direction selector (for dual carriageway support)
        DirectionSelector(
            onDirectionSelected = { viewModel.setDirection(it) }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBackToDrive,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }

            Button(
                onClick = {
                    viewModel.reportHazard()
                    onBackToDrive()
                },
                modifier = Modifier.weight(1f),
                enabled = selectedHazardType != null
            ) {
                Text("Report")
            }
        }
    }
}

@Composable
private fun LocationAccuracyChip(accuracy: Float?) {
    val accuracyText = when {
        accuracy == null -> "Getting location..."
        accuracy < 10 -> "📍 Very accurate (±${accuracy.toInt()}m)"
        accuracy < 50 -> "📍 Accurate (±${accuracy.toInt()}m)"
        else -> "📍 Approximate (±${accuracy.toInt()}m)"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = accuracyText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HazardTypeGrid(
    selectedType: HazardType?,
    onTypeSelected: (HazardType) -> Unit
) {
    val hazardTypes = listOf(
        HazardType.SPEED_BUMP to "Speed\nBump",
        HazardType.POTHOLE to "Pothole",
        HazardType.RUMBLE_STRIP to "Rumble\nStrip",
        HazardType.DEBRIS to "Debris",
        HazardType.POLICE to "Police",
        HazardType.SPEED_LIMIT_ZONE to "Speed\nZone"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        hazardTypes.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { (type, label) ->
                    HazardTypeCard(
                        type = type,
                        label = label,
                        isSelected = selectedType == type,
                        onClick = { onTypeSelected(type) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill remaining space if row is not full
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HazardTypeCard(
    type: HazardType,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder(
            enabled = isSelected
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DirectionSelector(onDirectionSelected: (Direction) -> Unit) {
    Text(
        text = "Direction (Dual Carriageway)",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Direction.values().forEach { direction ->
            OutlinedButton(
                onClick = { onDirectionSelected(direction) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = direction.displayName,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

enum class Direction(val displayName: String) {
    THIS_WAY("This Direction"),
    OPPOSITE("Opposite Direction")
}
