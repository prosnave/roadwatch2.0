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

class HomeFragment : Fragment() {
    private val ioScope = CoroutineScope(Dispatchers.IO)
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
            val repo = SeedRepository(requireContext())
            val (result, _) = repo.loadSeeds()
            withContext(Dispatchers.Main) {
                statusText.text = if (result.loaded) {
                    "Ready • Seeds loaded: ${result.count}"
                } else {
                    "Ready • Seed load failed"
                }
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
    }

    private fun ensureLocationEnabled(onReady: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC)
            pendingLocCallback = onReady
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

    private var pendingLocCallback: (() -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                pendingLocCallback?.invoke()
            } else {
                Toast.makeText(requireContext(), "Location permission is required.", Toast.LENGTH_SHORT).show()
            }
            pendingLocCallback = null
        }
    }

    companion object {
        private const val REQ_LOC = 1003
    }
}
