package com.roadwatch.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.roadwatch.app.R
import com.roadwatch.data.SeedRepository
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts

class HomeFragment : Fragment() {
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var pendingLocCallback: (() -> Unit)? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pendingLocCallback?.invoke()
            } else {
                Toast.makeText(requireContext(), "Location permission is required.", Toast.LENGTH_SHORT).show()
            }
            pendingLocCallback = null
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val statusText = view.findViewById<TextView>(R.id.status_text)
        val startBtn = view.findViewById<Button>(R.id.btn_start_drive)
        val stopBtn = view.findViewById<Button>(R.id.btn_stop_drive)
        val settingsBtn = view.findViewById<Button>(R.id.btn_open_settings)

        ioScope.launch {
            val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val loc = com.roadwatch.core.location.DriveModeService.lastKnownLocation
            val visible = try {
                if (base.isNotEmpty() && loc != null) {
                    val res = com.roadwatch.network.ApiClient.listHazards(
                        base,
                        loc.latitude,
                        loc.longitude,
                        com.roadwatch.prefs.AppPrefs.getSyncRadiusMeters(requireContext()),
                        50,
                        null,
                        null
                    )
                    if (res.isSuccess) res.getOrNull()!!.hazards.size else 0
                } else 0
            } catch (_: Exception) { 0 }
            withContext(Dispatchers.Main) {
                statusText.text = if (visible > 0) "Ready • Visible hazards: ${visible}" else getString(R.string.ready)
            }
        }

        startBtn.setOnClickListener {
            ensureLocationEnabled {
                parentFragmentManager
                    .beginTransaction()
                    .replace(
                        com.roadwatch.app.R.id.fragment_container,
                        com.roadwatch.feature.drive.DriveHudFragment()
                    )
                    .addToBackStack(null)
                    .commit()
            }
        }
        stopBtn.setOnClickListener {
            (activity as? com.roadwatch.app.MainActivity)?.stopDriveMode()
            stopBtn.visibility = View.GONE
            startBtn.visibility = View.VISIBLE
            statusText.text = getString(R.string.ready)
        }
        settingsBtn.setOnClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(
                    com.roadwatch.app.R.id.fragment_container,
                    com.roadwatch.feature.settings.SettingsFragment()
                )
                .addToBackStack(null)
                .commit()
        }

        // Auto-resume suggestion: if we stopped recently and moved >100m
        view.post {
            try {
                if (!com.roadwatch.prefs.AppPrefs.isAutoResumeEnabled(requireContext())) return@post
                val lastLat = com.roadwatch.prefs.AppPrefs.getLastAutoStopLat(requireContext())
                val lastLng = com.roadwatch.prefs.AppPrefs.getLastAutoStopLng(requireContext())
                val lastAt = com.roadwatch.prefs.AppPrefs.getLastAutoStopAt(requireContext())
                if (lastLat != null && lastLng != null && lastAt != null && System.currentTimeMillis() - lastAt < 2 * 60 * 60 * 1000) {
                    if (!hasFinePermission()) return@post
                    val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
                    val providers = lm.getProviders(true)
                    var best: android.location.Location? = null
                    for (p in providers) {
                        @android.annotation.SuppressLint("MissingPermission")
                        val l = lm.getLastKnownLocation(p) ?: continue
                        if (best == null || l.accuracy < best.accuracy) best = l
                    }
                    if (best != null) {
                        val d = distanceMeters(lastLat, lastLng, best.latitude, best.longitude)
                        if (d > 100.0) {
                            android.app.AlertDialog.Builder(requireContext())
                                .setTitle("Resume Drive Mode?")
                                .setMessage("You’ve moved ${d.toInt()} m since last stop.")
                                .setPositiveButton("Resume") { _, _ ->
                                    com.roadwatch.prefs.AppPrefs.clearAutoStop(requireContext())
                                    startBtn.performClick()
                                }
                                .setNegativeButton("Not now", null)
                                .show()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    private fun hasFinePermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLocationEnabled(onReady: () -> Unit) {
        val granted = hasFinePermission()
        if (!granted) {
            pendingLocCallback = onReady
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) {
            AlertDialog.Builder(requireContext())
                .setTitle("Enable Location")
                .setMessage("Please enable Location services to start Drive Mode.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        onReady()
    }
}
