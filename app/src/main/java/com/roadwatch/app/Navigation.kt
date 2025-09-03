package com.roadwatch.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.roadwatch.feature.drive.DriveHudScreen
import com.roadwatch.feature.home.HomeScreen
import com.roadwatch.feature.passenger.PassengerModeScreen
import com.roadwatch.feature.settings.SettingsScreen

@Composable
fun RoadWatchNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartDriveMode = {
                    (context as? MainActivity)?.startDriveMode()
                    navController.navigate("drive") {
                        popUpTo("home")
                    }
                },
                onOpenSettings = { navController.navigate("settings") },
                onManageLocations = { navController.navigate("locations") }
            )
        }

        composable("drive") {
            DriveHudScreen(
                onStopDriveMode = {
                    (context as? MainActivity)?.stopDriveMode()
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onOpenPassengerMode = { navController.navigate("passenger") }
            )
        }

        composable("passenger") {
            PassengerModeScreen(
                onBackToDrive = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("locations") {
            // TODO: Implement ManageLocationsScreen
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
