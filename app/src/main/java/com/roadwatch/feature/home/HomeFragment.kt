package com.roadwatch.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.roadwatch.R
import com.roadwatch.app.MainActivity
import com.roadwatch.app.ui.insets.applySystemBarInsets
import com.roadwatch.core.location.DriveModeService
import com.roadwatch.core.util.RemoteLogger
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()

    // Permission launcher for runtime permission requests
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startDriveAfterPermission()
        } else {
            Toast.makeText(requireContext(), "Permissions required to start Drive Mode", Toast.LENGTH_LONG).show()
            RemoteLogger.logUserAction("PermissionDenied", "User did not grant required permissions")
        }
    }

    private lateinit var statusText: TextView
    private lateinit var btnStartDrive: MaterialButton
    private lateinit var btnStopDrive: MaterialButton
    private lateinit var btnOpenSettings: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        RemoteLogger.logScreenView("HomeFragment", "onViewCreated")

        // Apply insets to root
        view.findViewById<View>(R.id.root).applySystemBarInsets(
            applyTop = true,
            applyBottom = true
        )

        // Initialize views
        statusText = view.findViewById(R.id.status_text)
        btnStartDrive = view.findViewById(R.id.btn_start_drive)
        btnStopDrive = view.findViewById(R.id.btn_stop_drive)
        btnOpenSettings = view.findViewById(R.id.btn_open_settings)

        // Setup click listeners
        setupClickListeners()

        // Observe drive mode state
        observeDriveModeState()

        // Observe ViewModel drive mode state to keep UI in sync
        lifecycleScope.launchWhenStarted {
            viewModel.isDriveModeActive.collect { active ->
                updateDriveModeUI(active)
            }
        }

        RemoteLogger.i("HomeFragment", "View initialization completed")
    }

    private fun setupClickListeners() {
        btnStartDrive.setOnClickListener {
            RemoteLogger.logUserAction("Start Drive Mode", "Button clicked")

            if (arePermissionsGranted()) {
                startDriveAfterPermission()
            } else {
                // Build permission list depending on SDK version
                val perms = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }

                permissionLauncher.launch(perms.toTypedArray())
            }
        }

        btnStopDrive.setOnClickListener {
            RemoteLogger.logUserAction("Stop Drive Mode", "Button clicked")
            (requireActivity() as? MainActivity)?.stopDriveMode()
            updateDriveModeUI(false)
        }

        btnOpenSettings.setOnClickListener {
            RemoteLogger.logUserAction("Open Settings", "Button clicked")
            findNavController().navigate(HomeFragmentDirections.actionOpenSettings())
            RemoteLogger.logScreenView("SettingsFragment", "Navigation from HomeFragment")
        }
    }

    private fun arePermissionsGranted(): Boolean {
        val context = requireContext()
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        var background = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        var notifications = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }

        return fine && coarse && background && notifications
    }

    private fun startDriveAfterPermission() {
        (requireActivity() as? MainActivity)?.startDriveMode()
        findNavController().navigate(HomeFragmentDirections.actionOpenDriveHud())
        updateDriveModeUI(true)
        RemoteLogger.logUserAction("StartDriveModeGranted", "Drive mode started after permissions granted")
    }

    private fun observeDriveModeState() {
        // Note: Service state observation would be implemented here
        // For now, we'll update based on navigation state
        // TODO: Implement proper service state observation
    }

    private fun updateDriveModeUI(isActive: Boolean) {
        if (isActive) {
            statusText.text = getString(R.string.drive_mode_active)
            btnStartDrive.visibility = View.GONE
            btnStopDrive.visibility = View.VISIBLE
        } else {
            statusText.text = getString(R.string.ready)
            btnStartDrive.visibility = View.VISIBLE
            btnStopDrive.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Update UI based on current state
        // This would be improved with proper state management
        updateDriveModeUI(false)
    }
}
