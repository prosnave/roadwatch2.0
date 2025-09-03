package com.roadwatch.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roadwatch.BuildConfig

@Composable
fun HomeScreen(
    onStartDriveMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onManageLocations: () -> Unit,
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val isDriveModeActive by viewModel.isDriveModeActive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status indicator
        Text(
            text = if (isDriveModeActive) "Drive Mode Active" else "Ready",
            style = MaterialTheme.typography.headlineMedium,
            color = if (isDriveModeActive)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isDriveModeActive)
                "Monitoring hazards ahead..."
            else
                "Tap to start monitoring road hazards",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Main action button
        Button(
            onClick = onStartDriveMode,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = if (isDriveModeActive) "Stop Drive Mode" else "Start Drive Mode",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Secondary actions
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text("Settings")
        }

        // Admin features (only in admin flavor)
        if (BuildConfig.IS_ADMIN) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onManageLocations,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Text("Manage Locations")
            }
        }
    }
}
