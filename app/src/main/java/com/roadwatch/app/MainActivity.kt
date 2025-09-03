package com.roadwatch.app

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.roadwatch.R
import com.roadwatch.app.ui.insets.applySystemBarInsets
import com.roadwatch.core.location.DriveModeService
import com.roadwatch.core.util.RemoteLogger

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d(TAG, "MainActivity onCreate started")
            RemoteLogger.logAppEvent("MainActivity", "onCreate started")

            // Enable edge-to-edge
            WindowCompat.setDecorFitsSystemWindows(window, false)
            Log.d(TAG, "WindowCompat set successfully")
            RemoteLogger.d(TAG, "WindowCompat set successfully")

            setContentView(R.layout.activity_main)
            Log.d(TAG, "setContentView completed")
            RemoteLogger.d(TAG, "setContentView completed")

            setupNavigation()
            setupInsets()

            Log.d(TAG, "MainActivity onCreate completed successfully")
            RemoteLogger.logAppEvent("MainActivity", "onCreate completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error in MainActivity onCreate", e)
            RemoteLogger.logError("MainActivity.onCreate", "Exception occurred", e)
            throw e
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        val navController = navHostFragment.navController

        // Setup BottomNavigationView
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setupWithNavController(navController)

        // Setup AppBar
        appBarConfiguration = AppBarConfiguration.Builder(
            setOf(R.id.home, R.id.locations, R.id.settings)
        ).build()
        setupActionBarWithNavController(navController, appBarConfiguration)
    }

    private fun setupInsets() {
        // Apply insets to root coordinator layout
        findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.coordinator)
            .applySystemBarInsets(applyTop = true, applyBottom = true)

        // Apply insets to toolbar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar)) { view, insets ->
            val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                sysInsets.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        // Apply insets to bottom navigation
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottom_nav)) { view, insets ->
            val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                sysInsets.bottom
            )
            insets
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    fun startDriveMode() {
        val intent = DriveModeService.createStartIntent(this)
        startForegroundService(intent)
    }

    fun stopDriveMode() {
        val intent = DriveModeService.createStopIntent(this)
        startService(intent)
    }
}
