package com.roadwatch.feature.drive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.roadwatch.app.R
import com.roadwatch.data.SeedRepository
import com.roadwatch.prefs.AppPrefs
import com.roadwatch.feature.report.DriverReportSheet
import com.roadwatch.feature.report.PassengerReportSheet
import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import java.time.Instant
import com.google.android.gms.maps.GoogleMap
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.res.ResourcesCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DriveHudFragment : Fragment() {
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var googleMap: GoogleMap? = null
    private var lm: LocationManager? = null
    private var firstFix = true
    private var txtSpeed: android.widget.TextView? = null
    private var txtBearing: android.widget.TextView? = null
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            googleMap?.isMyLocationEnabled = hasFinePermission()
            // Update HUD speed and bearing
            updateHudFrom(location)
            updateNextHazardChip(location)
            if (firstFix) {
                firstFix = false
                googleMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude), 16f
                    )
                )
            } else {
                googleMap?.animateCamera(
                    CameraUpdateFactory.newLatLng(
                        LatLng(location.latitude, location.longitude)
                    )
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_drive_hud, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Ensure required permissions and services
        ensurePrerequisitesOrExit()

        // Setup map
        val tag = "drive_map"
        var mapFragment = childFragmentManager.findFragmentByTag(tag) as? SupportMapFragment
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance()
            childFragmentManager.beginTransaction()
                .replace(R.id.map_container, mapFragment, tag)
                .commitNow()
        }
        mapFragment.getMapAsync { gMap ->
            googleMap = gMap
            gMap.uiSettings.isMyLocationButtonEnabled = false
            gMap.uiSettings.isCompassEnabled = true
            gMap.isTrafficEnabled = true
            enableMyLocation()
            // Ensure map controls avoid being obscured by HUD
            view.post {
                val bottom = view.findViewById<View>(R.id.bottom_bar).height + view.findViewById<View>(R.id.btn_quick_report).height + dp(24)
                val top = (view.findViewById<View>(R.id.mute_chip).height + view.findViewById<View>(R.id.next_chip).height + dp(16)).coerceAtLeast(dp(16))
                gMap.setPadding(dp(16), top, dp(16), bottom)
            }
            ioScope.launch {
                val repo = SeedRepository(requireContext())
                val (_, hazards) = repo.loadSeeds()
                withContext(Dispatchers.Main) {
                    if (hazards.isNotEmpty()) {
                        val first = LatLng(hazards.first().lat, hazards.first().lng)
                        if (firstFix) gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 12f))
                    }
                    hazards.take(200).forEach { h ->
                        val iconRes = when (h.type) {
                            com.roadwatch.data.HazardType.SPEED_BUMP -> com.roadwatch.app.R.drawable.ic_marker_bump
                            com.roadwatch.data.HazardType.RUMBLE_STRIP -> com.roadwatch.app.R.drawable.ic_marker_rumble
                            com.roadwatch.data.HazardType.POTHOLE -> com.roadwatch.app.R.drawable.ic_marker_pothole
                            else -> com.roadwatch.app.R.drawable.ic_marker_bump
                        }
                        val scale = 1.0f + (getVotesFor(h) / 10.0f)
                        val icon = vectorToBitmapDescriptor(iconRes, scale)
                        gMap.addMarker(
                            MarkerOptions().position(LatLng(h.lat, h.lng)).title(h.type.name).icon(icon)
                        )
                    }
                }
            }
        }

        // Controls
        val btnStop = view.findViewById<Button>(R.id.btn_stop)
        val btnAdd = view.findViewById<Button>(R.id.btn_passenger)
        val btnQuick = view.findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.btn_quick_report)
        txtSpeed = view.findViewById(R.id.txt_speed_value)
        txtBearing = view.findViewById(R.id.txt_bearing_value)

        btnStop.setOnClickListener {
            (activity as? com.roadwatch.app.MainActivity)?.stopDriveMode()
            parentFragmentManager.popBackStack()
        }
        btnAdd.setOnClickListener {
            if (com.roadwatch.prefs.AppPrefs.isPassengerEnabled(requireContext())) {
                PassengerReportSheet().show(parentFragmentManager, "passenger_report")
            } else {
                Toast.makeText(requireContext(), "Enable passenger mode in Settings to use this.", Toast.LENGTH_SHORT).show()
            }
        }

        // Start foreground service when HUD opens
        val intent = com.roadwatch.core.location.DriveModeService.createStartIntent(requireContext())
        requireContext().startForegroundService(intent)

        // Muted-until chip
        val chip = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.mute_chip)
        val chipText = view.findViewById<android.widget.TextView>(R.id.txt_muted_until)
        if (AppPrefs.isMuted(requireContext())) {
            chip.visibility = View.VISIBLE
            val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(AppPrefs.getMutedUntilMillis(requireContext())))
            chipText.text = "Muted until $until"
        } else {
            chip.visibility = View.GONE
        }

        btnQuick.setOnClickListener { promptReportType() }
    }

    private fun enableMyLocation() {
        if (!hasFinePermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC_FINE)
            return
        }
        googleMap?.isMyLocationEnabled = true
        startLocationUpdates()
    }

    private fun hasFinePermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        if (lm == null) lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, locListener, Looper.getMainLooper())
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, locListener, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        try { lm?.removeUpdates(locListener) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC_FINE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
            }
            else {
                // Without location we exit Drive Mode
                Toast.makeText(requireContext(), "Location permission required", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }
        if (requestCode == REQ_NOTIF && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(requireContext(), "Notifications are required for alerts", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationUpdates()
        googleMap = null
        txtSpeed = null
        txtBearing = null
    }

    companion object {
        private const val REQ_LOC_FINE = 2001
        private const val REQ_NOTIF = 2002
    }

    private fun vectorToBitmapDescriptor(resId: Int, scale: Float = 1.0f): BitmapDescriptor {
        val drawable = ResourcesCompat.getDrawable(resources, resId, null) ?: return BitmapDescriptorFactory.defaultMarker()
        val baseW = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
        val baseH = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
        val width = (baseW * scale).toInt().coerceAtLeast(24)
        val height = (baseH * scale).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun getVotesFor(h: com.roadwatch.data.Hazard): Int {
        val key = com.roadwatch.data.SeedOverrides.keyOf(h)
        return com.roadwatch.data.CommunityVotes.getVotes(requireContext(), key)
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun lastKnown(): android.location.Location? {
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = lm.getProviders(true)
        var best: android.location.Location? = null
        for (p in providers) {
            val l = lm.getLastKnownLocation(p) ?: continue
            if (best == null || l.accuracy < best!!.accuracy) best = l
        }
        return best
    }

    private fun promptReportType() {
        val isAdmin = com.roadwatch.app.BuildConfig.IS_ADMIN
        val types = if (isAdmin) arrayOf(
            HazardType.SPEED_BUMP.name,
            HazardType.POTHOLE.name,
            HazardType.RUMBLE_STRIP.name
        ) else arrayOf(HazardType.SPEED_BUMP.name)
        var selected = 0
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select hazard type")
            .setSingleChoiceItems(types, selected) { _, which -> selected = which }
            .setPositiveButton("Report") { _, _ ->
                val type = HazardType.valueOf(types[selected])
                // Try fresh fix with fallback to last known
                val progress = showGpsProgress()
                requestSingleFix(2500L) { fix ->
                    val loc = fix ?: lastKnown()
                    try { progress.dismiss() } catch (_: Exception) {}
                    if (loc == null) {
                        Toast.makeText(requireContext(), "Location unavailable", Toast.LENGTH_SHORT).show()
                        return@requestSingleFix
                    }
                    val ok = SeedRepository(requireContext()).addUserHazard(
                        Hazard(
                            type = type,
                            lat = loc.latitude,
                            lng = loc.longitude,
                            active = true,
                            source = "USER",
                            createdAt = Instant.now()
                        )
                    )
                    Toast.makeText(requireContext(), if (ok) "Reported ${type.name}" else "Report failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestSingleFix(timeoutMs: Long, callback: (Location?) -> Unit) {
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        if (!hasFinePermission()) { callback(null); return }
        var delivered = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!delivered) {
                    delivered = true
                    try { lm.removeUpdates(this) } catch (_: Exception) {}
                    callback(location)
                }
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) { callback(null); return }
        view?.postDelayed({
            if (!delivered) {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
                callback(null)
            }
        }, timeoutMs)
    }

    private fun showGpsProgress(): android.app.AlertDialog {
        val pb = android.widget.ProgressBar(requireContext()).apply { isIndeterminate = true }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(48,32,48,32)
            addView(pb)
            val tv = android.widget.TextView(requireContext()).apply { text = "Getting GPS fix…"; setPadding(24,0,0,0) }
            addView(tv)
        }
        val dlg = android.app.AlertDialog.Builder(requireContext())
            .setView(container)
            .setCancelable(false)
            .create()
        dlg.show()
        return dlg
    }

    // --- Prerequisites: notifications allowed, location permission, location services on ---
    private fun ensurePrerequisitesOrExit() {
        // Notifications
        if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            // On Android 13+, request POST_NOTIFICATIONS; otherwise guide to settings
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            } else {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Enable notifications")
                    .setMessage("Notifications are required for Drive Mode alerts.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                        })
                    }
                    .setNegativeButton("Cancel") { _, _ -> parentFragmentManager.popBackStack() }
                    .show()
            }
        }

        // Location services enabled
        val lmLocal = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val enabled = lmLocal.isProviderEnabled(LocationManager.GPS_PROVIDER) || lmLocal.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Enable Location Services")
                .setMessage("Turn on location (GPS or network) to use Drive Mode.")
                .setPositiveButton("Open Settings") { _, _ -> startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Cancel") { _, _ -> parentFragmentManager.popBackStack() }
                .show()
        }
    }
    private fun updateHudFrom(location: Location) {
        val speedKph = (location.speed * 3.6).toInt().coerceAtLeast(0)
        txtSpeed?.text = speedKph.toString()
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN
        txtBearing?.text = if (bearing.isNaN()) "N" else degToCompass(bearing)
    }

    private fun degToCompass(bearing: Double): String {
        val dirs = arrayOf("N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW")
        var b = bearing
        while (b < 0) b += 360.0
        val idx = ((b + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }

    private fun updateNextHazardChip(location: Location) {
        val chip = view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.next_chip)
        val chipText = view?.findViewById<android.widget.TextView>(R.id.txt_next_hazard)
        val subtitle = view?.findViewById<android.widget.TextView>(R.id.txt_next_subtitle)
        if (chip == null || chipText == null) return
        val hazards = SeedRepository(requireContext()).allHazards().filter { it.active }
        var bestLabel: String? = null
        var bestDist = Double.MAX_VALUE
        var bestType: String? = null
        hazards.forEach { h ->
            val d = distanceMeters(location.latitude, location.longitude, h.lat, h.lng)
            if (d < bestDist) {
                bestDist = d
                val type = h.type.name.replace('_',' ').lowercase().replaceFirstChar { it.uppercase() }
                bestType = type
                bestLabel = "$type in ${formatNiceDistance(bestDist)}"
            }
        }
        if (bestLabel != null) {
            chipText.text = bestLabel
            chip.visibility = View.VISIBLE
            subtitle?.text = "Next: ${bestType} • ${formatNiceDistance(bestDist)}"
        } else {
            chip.visibility = View.GONE
            subtitle?.text = ""
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sin1 = kotlin.math.sin(dLat / 2)
        val sin2 = kotlin.math.sin(dLon / 2)
        val a = sin1 * sin1 +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                sin2 * sin2
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
        return R * c
    }

    private fun formatNiceDistance(meters: Double): String {
        val m = meters.toInt()
        return when {
            m < 50 -> "<50 m"
            m < 200 -> "${(m / 10) * 10} m"
            m < 1000 -> "${(m / 50) * 50} m"
            else -> String.format(java.util.Locale.US, "%.1f km", m / 1000.0)
        }
    }
}
