package com.roadwatch.app

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
// import androidx.navigation.fragment.NavHostFragment
// import androidx.navigation.ui.AppBarConfiguration
// import androidx.navigation.ui.navigateUp
// import androidx.navigation.ui.setupActionBarWithNavController
// import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
// Use the generated R in this module's namespace
import com.roadwatch.app.R
// import com.roadwatch.app.ui.insets.applySystemBarInsets
// import com.roadwatch.core.location.DriveModeService
// import com.roadwatch.core.util.RemoteLogger
// import com.roadwatch.data.repository.HazardRepository
// import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// import javax.inject.Inject

// @AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // @Inject
    // lateinit var hazardRepository: HazardRepository

    private val applicationScope = CoroutineScope(Dispatchers.IO)

    private val TAG = "MainActivity"
    // private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            val needFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
            val needNotif = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED else false
            val first = if (needFine || needNotif) com.roadwatch.app.PermissionsFragment() else com.roadwatch.feature.home.HomeFragment()
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, first)
                .commit()
        }
    }

    private fun setupNavigation() {
        // val navHostFragment = supportFragmentManager
        //     .findFragmentById(R.id.nav_host) as NavHostFragment
        // val navController = navHostFragment.navController

        // // Setup BottomNavigationView
        // val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        // bottomNav.setupWithNavController(navController)

        // // Setup AppBar
        // appBarConfiguration = AppBarConfiguration.Builder(
        //     setOf(R.id.home, R.id.locations, R.id.settings)
        // ).build()
        // setupActionBarWithNavController(navController, appBarConfiguration)
    }

    private fun setupInsets() {
        // Apply insets to root coordinator layout
        // findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.coordinator)
        //     .applySystemBarInsets(applyTop = true, applyBottom = true)

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

    // override fun onSupportNavigateUp(): Boolean {
    //     val navHostFragment = supportFragmentManager
    //         .findFragmentById(R.id.nav_host) as NavHostFragment
    //     val navController = navHostFragment.navController
    //     return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    // }

    fun startDriveMode() {
        // val intent = DriveModeService.createStartIntent(this)
        // startForegroundService(intent)
    }

    fun stopDriveMode() {
        // val intent = DriveModeService.createStopIntent(this)
        // startService(intent)
    }

    private fun loadSeedData() {
        applicationScope.launch {
            try {
                // hazardRepository.reloadSeeds()
                Log.d(TAG, "Seed data loaded successfully.")
                // RemoteLogger.logAppEvent("Database", "Seed data loaded successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading seed data", e)
                // RemoteLogger.logError("Database", "Error loading seed data", e)
            }
        }
    }
}
