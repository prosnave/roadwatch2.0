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
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import android.util.TypedValue
import android.widget.LinearLayout
import kotlin.math.abs

class DriveHudFragment : Fragment() {
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var googleMap: GoogleMap? = null
    private var lm: LocationManager? = null
    private var firstFix = true
    private var txtSpeed: android.widget.TextView? = null
    private var txtBearing: android.widget.TextView? = null
    private var lastBaselineLoc: Location? = null
    private var lastBaselineTime: Long = 0L
    private var lastMoveTime: Long = 0L
    private val idleDistanceMeters = 100.0
    private val idleTimeoutMs = 120_000L
    private var providersReceiver: BroadcastReceiver? = null
    private var overlayReceiver: BroadcastReceiver? = null
    private var baseSpeedTextSizePx = 0f
    private var baseBearingTextSizePx = 0f
    private var baseLabelTextSizePx = 0f
    private var baseSubtitleTextSizePx = 0f
    private var baseCardPaddingHorizontalPx = 0
    private var baseCardPaddingVerticalPx = 0
    private var preferredZoom = DEFAULT_MAP_ZOOM
    private var userAdjustingCamera = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                val permission = it.key
                val isGranted = it.value
                if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                    if (isGranted) {
                        enableMyLocation()
                    } else {
                        Toast.makeText(requireContext(), "Location permission required", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    }
                }
                if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                    if (!isGranted) {
                        Toast.makeText(requireContext(), "Notifications are required for alerts", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            googleMap?.isMyLocationEnabled = hasFinePermission()
            // Update HUD speed and bearing
            updateHudFrom(location)
            updateNextHazardChip(location)
            evaluateIdleStop(location)
            val zoom = clampZoom(preferredZoom)
            if (abs(zoom - preferredZoom) > 0.01f) {
                preferredZoom = zoom
                AppPrefs.setDriveMapZoom(requireContext(), zoom)
            }
            val cameraPosition = com.google.android.gms.maps.model.CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(preferredZoom)
                .bearing(location.bearing)
                .tilt(45f)
                .build()
            googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
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
        val mapTag = "drive_map"
        var mapFragment = childFragmentManager.findFragmentByTag(mapTag) as? SupportMapFragment
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance()
            childFragmentManager.beginTransaction()
                .replace(R.id.map_container, mapFragment, mapTag)
                .commitNow()
        }
        mapFragment.getMapAsync { gMap ->
            googleMap = gMap
            gMap.uiSettings.isMyLocationButtonEnabled = false
            gMap.uiSettings.isCompassEnabled = true
            gMap.isTrafficEnabled = true

            AppPrefs.getDriveMapZoom(requireContext())?.let {
                val clamped = clampZoom(it)
                preferredZoom = clamped
                if (abs(clamped - it) > 0.01f) {
                    AppPrefs.setDriveMapZoom(requireContext(), clamped)
                }
            }

            gMap.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    userAdjustingCamera = true
                }
            }
            gMap.setOnCameraMoveCanceledListener {
                userAdjustingCamera = false
            }
            gMap.setOnCameraIdleListener {
                if (userAdjustingCamera) {
                    val zoom = clampZoom(gMap.cameraPosition.zoom)
                    if (abs(zoom - preferredZoom) > 0.01f) {
                        preferredZoom = zoom
                        AppPrefs.setDriveMapZoom(requireContext(), zoom)
                    }
                }
                userAdjustingCamera = false
            }
            // Night mode polish: apply dark map style when in night mode
            try {
                val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (night) {
                    val opts = com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(requireContext(), com.roadwatch.app.R.raw.map_style_night)
                    gMap.setMapStyle(opts)
                }
            } catch (_: Exception) {}
            enableMyLocation()
            // Center to last known location at a navigation-friendly zoom
            lastKnown()?.let { lk ->
                gMap.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lk.latitude, lk.longitude), preferredZoom)
                )
            }
            // Ensure map controls avoid being obscured by HUD
            view.post {
                val bottom = view.findViewById<View>(R.id.bottom_bar).height + view.findViewById<View>(R.id.hazard_buttons_container).height + dp(24)
                val top = (view.findViewById<View>(R.id.mute_chip).height + view.findViewById<View>(R.id.next_chip).height + dp(16)).coerceAtLeast(dp(16))
                gMap.setPadding(dp(16), top, dp(16), bottom)
            }
            refreshMarkers()

            // Open editor when a hazard marker is tapped
            gMap.setOnMarkerClickListener { marker ->
                val markerTag = marker.tag
                if (markerTag is com.roadwatch.data.Hazard) {
                    openHazardEditor(markerTag)
                    true
                } else {
                    false
                }
            }
        }

        // Controls
        val btnStop = view.findViewById<Button>(R.id.btn_stop)
        txtSpeed = view.findViewById(R.id.txt_speed_value)
        txtBearing = view.findViewById(R.id.txt_bearing_value)
        if (baseSpeedTextSizePx == 0f) {
            baseSpeedTextSizePx = txtSpeed?.textSize ?: 0f
        }
        if (baseBearingTextSizePx == 0f) {
            baseBearingTextSizePx = txtBearing?.textSize ?: 0f
        }
        val speedLabel = view.findViewById<android.widget.TextView>(R.id.txt_speed_label)
        val subtitleView = view.findViewById<android.widget.TextView>(R.id.txt_next_subtitle)
        val speedCardContent = view.findViewById<LinearLayout>(R.id.speed_card_content)
        if (baseLabelTextSizePx == 0f) {
            baseLabelTextSizePx = speedLabel?.textSize ?: 0f
        }
        if (baseSubtitleTextSizePx == 0f) {
            baseSubtitleTextSizePx = subtitleView?.textSize ?: 0f
        }
        if (baseCardPaddingHorizontalPx == 0 && speedCardContent != null) {
            baseCardPaddingHorizontalPx = speedCardContent.paddingStart
            baseCardPaddingVerticalPx = speedCardContent.paddingTop
        }

        val btnReportBump = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_report_bump)
        val btnReportPothole = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_report_pothole)
        val btnReportRumble = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_report_rumble)
        val btnReportZone = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_report_zone)

        btnReportBump.setOnClickListener { DriverReportSheet.newInstance(HazardType.SPEED_BUMP).show(parentFragmentManager, "driver_report") }
        btnReportPothole.setOnClickListener { DriverReportSheet.newInstance(HazardType.POTHOLE).show(parentFragmentManager, "driver_report") }
        btnReportRumble.setOnClickListener { DriverReportSheet.newInstance(HazardType.RUMBLE_STRIP).show(parentFragmentManager, "driver_report") }
        btnReportZone.setOnClickListener {
            // The zone reporting still needs the sheet for start/end points
            DriverReportSheet.newInstance(HazardType.SPEED_LIMIT_ZONE).show(parentFragmentManager, "driver_report")
        }

        btnStop.setOnClickListener {
            (activity as? com.roadwatch.app.MainActivity)?.stopDriveMode()
            parentFragmentManager.popBackStack()
        }
        // Passenger reporting removed for MVP; unified driver sheet used for all reporting.

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

        // Initialize idle tracking baseline
        view.post {
            lastBaselineLoc = lastKnown()
            val now = System.currentTimeMillis()
            lastBaselineTime = now
            lastMoveTime = now
        }

        // Default the speed card to neutral styling until the first hazard evaluation runs.
        updateSpeedCardStyle(null)

        // Prompt for DND bypass so alerts can play in Silent/DND
        ensureDndAccessPrompt()
    }

    private fun enableMyLocation() {
        if (!hasFinePermission()) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
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

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationUpdates()
        googleMap = null
        txtSpeed = null
        txtBearing = null
    }

    override fun onResume() {
        super.onResume()
        // Keep screen on during drive
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Start provider checks
        view?.postDelayed(checkProvidersRunnable, 10_000L)

        // Also listen for provider changes (user toggles Location in quick settings)
        if (providersReceiver == null) {
            providersReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    val lmLocal = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
                    val enabled = lmLocal.isProviderEnabled(LocationManager.GPS_PROVIDER) || lmLocal.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    val hasFine = hasFinePermission()
                    if (!enabled || !hasFine) {
                        stopDriveService()
                        closeOverlaysAndExit()
                    }
                }
            }
            requireContext().registerReceiver(providersReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        }

        // Listen for hazard overlay broadcasts
        if (overlayReceiver == null) {
            overlayReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        "com.roadwatch.ALERT_OVERLAY" -> {
                            // Audio cues already inform the driver; suppress duplicate popup UI.
                            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.alert_overlay)?.visibility = View.GONE
                        }
                        "com.roadwatch.REFRESH_HAZARDS" -> refreshMarkers()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("com.roadwatch.ALERT_OVERLAY")
                addAction("com.roadwatch.REFRESH_HAZARDS")
            }
            requireContext().registerReceiver(overlayReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view?.removeCallbacks(checkProvidersRunnable)
        try { requireContext().unregisterReceiver(providersReceiver) } catch (_: Exception) {}
        providersReceiver = null
        try { requireContext().unregisterReceiver(overlayReceiver) } catch (_: Exception) {}
        overlayReceiver = null
    }

    companion object {
        private const val MIN_HEADING_AGREE_DEG = 15.0
        private const val ONE_WAY_MAX_HEADING_DEG = 10.0
        private const val MAX_LATERAL_OFFSET_METERS = 7.0
        private const val WARN_DISTANCE_METERS = 75.0
        private const val CAUTION_DISTANCE_METERS = 200.0
        private const val PRIORITY_SUPERSEDE_DISTANCE_METERS = 80.0
        private const val HAZARD_LINGER_METERS = 35.0
        private const val MIN_MAP_ZOOM = 15.0f
        private const val MAX_MAP_ZOOM = 20.0f
        private const val DEFAULT_MAP_ZOOM = 18.5f
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
            if (best == null || l.accuracy < best.accuracy) best = l
        }
        return best
    }

    private fun clampZoom(value: Float): Float = value.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)

    // Removed legacy quick picker dialog and GPS one-off fix helpers to keep code lean.

    private val checkProvidersRunnable = object : Runnable {
        override fun run() {
            try {
                val lmLocal = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
                val enabled = lmLocal.isProviderEnabled(LocationManager.GPS_PROVIDER) || lmLocal.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                if (!enabled) {
                    Toast.makeText(requireContext(), "Location turned off. Stopping Drive Mode.", Toast.LENGTH_SHORT).show()
                    stopDriveService()
                    closeOverlaysAndExit()
                    return
                }
                if (!hasFinePermission()) {
                    Toast.makeText(requireContext(), "Location permission revoked. Stopping Drive Mode.", Toast.LENGTH_SHORT).show()
                    stopDriveService()
                    closeOverlaysAndExit()
                    return
                }
            } catch (_: Exception) {}
            view?.postDelayed(this, 10_000L)
        }
    }

    private fun evaluateIdleStop(location: Location) {
        val now = System.currentTimeMillis()
        val base = lastBaselineLoc
        if (base == null) {
            lastBaselineLoc = location
            lastBaselineTime = now
            lastMoveTime = now
            return
        }
        val dist = distanceMeters(base.latitude, base.longitude, location.latitude, location.longitude)
        if (dist >= idleDistanceMeters) {
            // Significant movement: reset baseline and move time
            lastBaselineLoc = location
            lastBaselineTime = now
            lastMoveTime = now
            return
        }
        // Some movement without crossing threshold
        if (dist > 10.0) lastMoveTime = now
        val sinceBaseline = now - lastBaselineTime
        if (sinceBaseline >= idleTimeoutMs) {
            Toast.makeText(requireContext(), "No movement detected. Stopping Drive Mode.", Toast.LENGTH_SHORT).show()
            try { com.roadwatch.prefs.AppPrefs.recordAutoStop(requireContext(), location.latitude, location.longitude) } catch (_: Exception) {}
            stopDriveService()
            parentFragmentManager.popBackStack()
        }
    }

    private fun stopDriveService() {
        try {
            val intent = com.roadwatch.core.location.DriveModeService.createStopIntent(requireContext())
            requireContext().startService(intent)
        } catch (_: Exception) {}
    }

    private fun closeOverlaysAndExit() {
        try {
            // Dismiss any open bottom sheets/fragments hosted by this fragment
            childFragmentManager.fragments.forEach { f ->
                if (f is com.google.android.material.bottomsheet.BottomSheetDialogFragment) {
                    try { f.dismissAllowingStateLoss() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        parentFragmentManager.popBackStack()
    }

    // --- Prerequisites: notifications allowed, location permission, location services on ---
    private fun ensurePrerequisitesOrExit() {
        // Notifications
        if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            // On Android 13+, request POST_NOTIFICATIONS; otherwise guide to settings
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
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

    private fun ensureDndAccessPrompt() {
        // Gate the prompt: only if notifications are disabled or alerts channel has low importance and we haven't prompted recently
        val ctx = requireContext()
        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelsOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(com.roadwatch.notifications.NotificationHelper.CHANNEL_ALERTS)
            ch != null && ch.importance >= android.app.NotificationManager.IMPORTANCE_HIGH
        } else true
        val enabled = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        val needsPrompt = !(enabled && channelsOk)

        // Throttle: no more than once per 24h
        val last = com.roadwatch.prefs.AppPrefs.getLastDndPromptAt(ctx)
        if (!needsPrompt || (System.currentTimeMillis() - last) < 24 * 60 * 60 * 1000L) return

        com.roadwatch.prefs.AppPrefs.setLastDndPromptAt(ctx)
        com.roadwatch.notifications.NotificationHelper.ensureChannels(ctx)

        val builder = android.app.AlertDialog.Builder(ctx)
            .setTitle("Allow critical alerts")
            .setMessage("To play alerts in Silent/DND, allow DND and set Alerts channel to High.")
            .setPositiveButton("DND Settings") { _, _ ->
                val intent = android.content.Intent("android.settings.ZEN_MODE_SETTINGS")
                try { startActivity(intent) } catch (_: Exception) {}
            }
            .setNeutralButton("App Notification Settings") { _, _ ->
                val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                }
                try { startActivity(intent) } catch (_: Exception) {}
            }
            .setNegativeButton("Later", null)
        builder.show()
    }
    private fun updateHudFrom(location: Location) {
        val speedKph = (location.speed * 3.6).toInt().coerceAtLeast(0)
        txtSpeed?.text = speedKph.toString()
        // Increase text size slightly with speed for glanceability
        val sp = when {
            speedKph >= 80 -> 56f
            speedKph >= 50 -> 48f
            else -> 40f
        }
        txtSpeed?.textSize = sp
        txtBearing?.textSize = (sp - 12f).coerceAtLeast(28f)
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
        val hazards = SeedRepository(requireContext()).activeHazards()

        // Require bearing to avoid false positives; hide when unknown or speed too low
        if (!location.hasBearing() || location.speed < 1.0f) {
            chip.visibility = View.GONE
            subtitle?.text = ""
            updateSpeedCardStyle(null)
            return
        }

        val speedKph = location.speed * 3.6
        val lead = leadDistanceMeters(speedKph)
        val heading = Math.toRadians(location.bearing.toDouble())

        val chipCandidates = mutableListOf<HazardCandidate>()
        val styleCandidates = mutableListOf<HazardCandidate>()

        hazards.forEach { h ->
            val (tLat, tLng) = targetPoint(h)
            val d = distanceMeters(location.latitude, location.longitude, tLat, tLng)
            if (d > lead) return@forEach
            val bTo = bearingRadians(location.latitude, location.longitude, tLat, tLng)
            val headingDiffDeg = Math.toDegrees(kotlin.math.abs(angleDelta(heading, bTo)))
            if (h.directionality == "ONE_WAY" && headingDiffDeg > ONE_WAY_MAX_HEADING_DEG) return@forEach
            if (headingDiffDeg > MIN_HEADING_AGREE_DEG) return@forEach
            val lateral = d * kotlin.math.sin(bTo - heading)
            if (kotlin.math.abs(lateral) > MAX_LATERAL_OFFSET_METERS) return@forEach
            val along = d * kotlin.math.cos(bTo - heading)
            if (along < -HAZARD_LINGER_METERS) return@forEach
            val priority = hazardPriority(h.type)
            val candidate = HazardCandidate(h, along, d, priority)
            styleCandidates += candidate
            if (along > 0.0) {
                chipCandidates += candidate
            }
        }

        val bestAhead = selectBestCandidate(chipCandidates)
        val bestStyle = bestAhead ?: selectBestCandidate(styleCandidates)

        if (bestAhead != null) {
            val type = bestAhead.hazard.type.name.replace('_',' ').lowercase().replaceFirstChar { it.uppercase() }
            val text = "$type in ${formatNiceDistance(bestAhead.along)}"
            chipText.text = text
            chip.visibility = View.VISIBLE
            subtitle?.text = "Next: ${type} • ${formatNiceDistance(bestAhead.distance)}"
        } else {
            chip.visibility = View.GONE
            subtitle?.text = ""
        }

        updateSpeedCardStyle(bestStyle)
    }

    private fun updateSpeedCardStyle(candidate: HazardCandidate?) {
        val card = view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.speed_card) ?: return
        val ctx = card.context
        val neutralBg = ContextCompat.getColor(ctx, R.color.surface)
        val neutralFg = ContextCompat.getColor(ctx, R.color.on_surface)
        val neutralStroke = ContextCompat.getColor(ctx, R.color.outline)
        val cautionBg = ContextCompat.getColor(ctx, R.color.primary_container)
        val cautionFg = ContextCompat.getColor(ctx, R.color.on_primary_container)
        val cautionStroke = ContextCompat.getColor(ctx, R.color.primary)
        val warnBg = ContextCompat.getColor(ctx, R.color.hazard_warn_background)
        val warnFg = ContextCompat.getColor(ctx, R.color.hazard_warn_on_background)
        val warnStroke = ContextCompat.getColor(ctx, R.color.hazard_warn_stroke)

        val effectiveDistance = candidate?.effectiveAlong
        val urgency = when {
            effectiveDistance == null -> HazardUrgency.NEUTRAL
            effectiveDistance <= WARN_DISTANCE_METERS -> HazardUrgency.WARN
            effectiveDistance <= CAUTION_DISTANCE_METERS -> HazardUrgency.CAUTION
            else -> HazardUrgency.NEUTRAL
        }

        val bg: Int
        val fg: Int
        val stroke: Int
        val subtitleColor: Int
        when (urgency) {
            HazardUrgency.WARN -> {
                bg = warnBg
                fg = warnFg
                stroke = warnStroke
                subtitleColor = warnFg
            }
            HazardUrgency.CAUTION -> {
                bg = cautionBg
                fg = cautionFg
                stroke = cautionStroke
                subtitleColor = cautionFg
            }
            HazardUrgency.NEUTRAL -> {
                bg = neutralBg
                fg = neutralFg
                stroke = neutralStroke
                subtitleColor = ContextCompat.getColor(ctx, R.color.on_surface_variant)
            }
        }

        card.setCardBackgroundColor(bg)
        card.strokeWidth = if (stroke == neutralStroke) 0 else dp(2)
        card.setStrokeColor(stroke)

        val subtitle = view?.findViewById<android.widget.TextView>(R.id.txt_next_subtitle)
        subtitle?.setTextColor(subtitleColor)

        val labels = listOfNotNull(
            txtSpeed,
            txtBearing,
            view?.findViewById<android.widget.TextView>(R.id.txt_speed_label),
            view?.findViewById<android.widget.TextView>(R.id.txt_direction_label)
        )
        labels.forEach { it.setTextColor(fg) }

        if (baseSpeedTextSizePx > 0f) {
            txtSpeed?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSpeedTextSizePx)
        }
        if (baseBearingTextSizePx > 0f) {
            txtBearing?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseBearingTextSizePx)
        }
        val speedLabel = view?.findViewById<android.widget.TextView>(R.id.txt_speed_label)
        val directionLabel = view?.findViewById<android.widget.TextView>(R.id.txt_direction_label)
        if (baseLabelTextSizePx > 0f) {
            speedLabel?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseLabelTextSizePx)
            directionLabel?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseLabelTextSizePx)
        }
        if (baseSubtitleTextSizePx > 0f) {
            subtitle?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSubtitleTextSizePx)
        }
        val content = view?.findViewById<LinearLayout>(R.id.speed_card_content)
        if (content != null && baseCardPaddingHorizontalPx > 0) {
            content.setPadding(
                baseCardPaddingHorizontalPx,
                baseCardPaddingVerticalPx,
                baseCardPaddingHorizontalPx,
                baseCardPaddingVerticalPx
            )
        }

        card.animate().cancel()
        card.scaleX = 1f
        card.scaleY = 1f
    }

    private fun targetPoint(h: com.roadwatch.data.Hazard): Pair<Double, Double> {
        return if (h.type.name == "SPEED_LIMIT_ZONE" && h.zoneStartLat != null && h.zoneStartLng != null) {
            h.zoneStartLat to h.zoneStartLng
        } else h.lat to h.lng
    }

    private fun bearingRadians(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(phi2)
        val x = kotlin.math.cos(phi1) * kotlin.math.sin(phi2) - kotlin.math.sin(phi1) * kotlin.math.cos(phi2) * kotlin.math.cos(dLon)
        return kotlin.math.atan2(y, x)
    }

    private fun angleDelta(a: Double, b: Double): Double {
        var d = (b - a + Math.PI) % (2 * Math.PI)
        if (d < 0) d += 2 * Math.PI
        return d - Math.PI
    }

    private fun leadDistanceMeters(speedKph: Double): Double {
        return when {
            speedKph <= 0 -> 150.0
            speedKph <= 50 -> 150 + (speedKph / 50.0) * 150
            speedKph <= 100 -> 300 + ((speedKph - 50) / 50.0) * 300
            speedKph <= 120 -> 600 + ((speedKph - 100) / 20.0) * 200
            else -> 900.0
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
    private fun refreshMarkers() {
        val map = googleMap ?: return
        ioScope.launch {
            val hazards = SeedRepository(requireContext()).activeHazards()
            withContext(Dispatchers.Main) {
                map.clear()
                if (hazards.isNotEmpty()) {
                    val first = LatLng(hazards.first().lat, hazards.first().lng)
                    if (firstFix) {
                        val zoom = clampZoom(preferredZoom)
                        if (abs(zoom - preferredZoom) > 0.01f) {
                            preferredZoom = zoom
                            AppPrefs.setDriveMapZoom(requireContext(), zoom)
                        }
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(first, preferredZoom))
                    }
                }
                hazards.take(500).forEach { h ->
                    val iconRes = when (h.type) {
                        com.roadwatch.data.HazardType.SPEED_BUMP -> com.roadwatch.app.R.drawable.ic_marker_bump
                        com.roadwatch.data.HazardType.RUMBLE_STRIP -> com.roadwatch.app.R.drawable.ic_marker_rumble
                        com.roadwatch.data.HazardType.POTHOLE -> com.roadwatch.app.R.drawable.ic_marker_pothole
                        else -> com.roadwatch.app.R.drawable.ic_marker_bump
                    }
                    val scale = 1.0f + (getVotesFor(h) / 10.0f)
                    val icon = vectorToBitmapDescriptor(iconRes, scale)
                    val m = map.addMarker(
                        MarkerOptions().position(LatLng(h.lat, h.lng)).title(h.type.name).icon(icon)
                    )
                    m?.tag = h
                }
            }
        }
    }

    private fun openHazardEditor(h: com.roadwatch.data.Hazard) {
        val ctx = requireContext()
        val store = com.roadwatch.data.HazardStore(ctx)
        val key = com.roadwatch.data.SeedOverrides.keyOf(h)
        val userMatch = store.list().find { com.roadwatch.data.SeedOverrides.keyOf(it.hazard) == key }

        // If it's a user hazard, jump straight into the edit dialog.
        if (userMatch != null) {
            showEditDialog(userMatch)
            return
        }

        // For seed hazards, provide quick edit affordances: hide/show or create an editable copy.
        val isActive = h.active && !com.roadwatch.data.SeedOverrides.isDisabled(ctx, key)
        val toggleLabel = if (isActive) "Hide (Mark Inactive)" else "Show (Mark Active)"
        val niceType = h.type.name.lowercase().replace('_',' ').replaceFirstChar { it.uppercase() }

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Manage: $niceType")
            .setMessage(
                "This is a seed hazard. You can hide/show it, or make an editable copy to change type or move the pin."
            )
            .setPositiveButton(toggleLabel) { _, _ ->
                com.roadwatch.data.SeedOverrides.setDisabled(ctx, key, isActive)
                notifyRefresh()
            }
            .setNeutralButton("Make Editable Copy") { _, _ ->
                val created = createEditableCopyFromSeed(h)
                if (created != null) {
                    showEditDialog(created)
                } else {
                    com.roadwatch.ui.UiAlerts.error(view, "Could not create editable copy")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createEditableCopyFromSeed(h: com.roadwatch.data.Hazard): com.roadwatch.data.UserHazard? {
        val ctx = requireContext()
        val store = com.roadwatch.data.HazardStore(ctx)
        val copy = com.roadwatch.data.Hazard(
            type = h.type,
            lat = h.lat,
            lng = h.lng,
            directionality = when (h.directionality.uppercase()) {
                "BIDIRECTIONAL" -> "BIDIRECTIONAL"
                else -> "ONE_WAY"
            },
            reportedHeadingDeg = 0.0f,
            userBearing = null,
            active = true,
            source = "USER",
            createdAt = java.time.Instant.now(),
            speedLimitKph = h.speedLimitKph,
            zoneLengthMeters = h.zoneLengthMeters,
            zoneStartLat = h.zoneStartLat,
            zoneStartLng = h.zoneStartLng,
            zoneEndLat = h.zoneEndLat,
            zoneEndLng = h.zoneEndLng,
        )
        return if (store.add(copy)) {
            // Find the newly added user hazard by matching key and source
            val key = com.roadwatch.data.SeedOverrides.keyOf(copy)
            store.list().find { it.hazard.source == "USER" && com.roadwatch.data.SeedOverrides.keyOf(it.hazard) == key }
        } else null
    }

    private fun showEditDialog(u: com.roadwatch.data.UserHazard) {
        val ctx = requireContext()
        val store = com.roadwatch.data.HazardStore(ctx)
        val dialogView = layoutInflater.inflate(com.roadwatch.app.R.layout.dialog_edit_hazard, null)
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(com.roadwatch.app.R.id.radio_group_directionality)
        val spinner = dialogView.findViewById<android.widget.Spinner>(com.roadwatch.app.R.id.spinner_hazard_type)
        val quickLabel = dialogView.findViewById<android.widget.TextView>(com.roadwatch.app.R.id.txt_quick_actions)
        val btnOppositeLane = dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.roadwatch.app.R.id.btn_quick_opposite_lane)
        val btnDelete = dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.roadwatch.app.R.id.btn_delete_hazard)

        val hazardTypes = com.roadwatch.data.HazardType.values()
            .filter { it != com.roadwatch.data.HazardType.SPEED_LIMIT_ZONE }
            .map { it.name }
        val adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, hazardTypes)
        spinner.adapter = adapter

        spinner.setSelection(hazardTypes.indexOf(u.hazard.type.name))
        when (u.hazard.directionality) {
            "ONE_WAY" -> radioGroup.check(com.roadwatch.app.R.id.radio_one_way)
            "BIDIRECTIONAL" -> radioGroup.check(com.roadwatch.app.R.id.radio_two_way)
            else -> radioGroup.check(com.roadwatch.app.R.id.radio_one_way)
        }

        fun normalizeBearing(value: Float): Float {
            var v = value % 360f
            if (v < 0f) v += 360f
            return v
        }

        val builder = android.app.AlertDialog.Builder(ctx)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newDirectionality = when (radioGroup.checkedRadioButtonId) {
                    com.roadwatch.app.R.id.radio_one_way -> "ONE_WAY"
                    com.roadwatch.app.R.id.radio_two_way -> "BIDIRECTIONAL"
                    else -> u.hazard.directionality
                }
                val newType = com.roadwatch.data.HazardType.valueOf(spinner.selectedItem as String)
                val updated = u.hazard.copy(directionality = newDirectionality, type = newType)
                val originalKey = com.roadwatch.data.SeedOverrides.keyOf(u.hazard)
                if (store.upsertByKey(originalKey, updated)) {
                    notifyRefresh()
                    refreshMarkers()
                    com.roadwatch.ui.UiAlerts.success(view, "Hazard updated")
                } else {
                    com.roadwatch.ui.UiAlerts.error(view, "Update failed")
                }
            }
            .setNeutralButton("Move Pin") { _, _ ->
                startMovePin(u)
            }
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()

        if (com.roadwatch.app.BuildConfig.IS_ADMIN) {
            quickLabel?.visibility = View.VISIBLE
            btnOppositeLane?.visibility = View.VISIBLE
            btnDelete?.visibility = View.VISIBLE

            btnOppositeLane?.setOnClickListener {
                val loc = com.roadwatch.core.location.DriveModeService.lastKnownLocation
                val baseHeading = when {
                    loc != null && loc.hasBearing() -> loc.bearing
                    else -> u.hazard.reportedHeadingDeg
                }
                val opposite = normalizeBearing(baseHeading + 180f)
                val newDirectionality = when (u.hazard.directionality.uppercase(Locale.US)) {
                    "BIDIRECTIONAL" -> "ONE_WAY"
                    "ONE_WAY" -> "ONE_WAY"
                    else -> "ONE_WAY"
                }
                val updated = u.hazard.copy(
                    directionality = newDirectionality,
                    reportedHeadingDeg = opposite,
                    userBearing = opposite,
                )
                val originalKey = com.roadwatch.data.SeedOverrides.keyOf(u.hazard)
                if (store.upsertByKey(originalKey, updated)) {
                    val updatedUser = u.copy(hazard = updated)
                    notifyRefresh()
                    refreshMarkers()
                    dialog.dismiss()
                    startMovePin(
                        updatedUser,
                        ctx.getString(com.roadwatch.app.R.string.move_pin_opposite_prompt)
                    )
                } else {
                    com.roadwatch.ui.UiAlerts.error(view, "Update failed")
                }
            }

            btnDelete?.setOnClickListener {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Delete hazard?")
                    .setMessage("This removes the hazard for all drivers.")
                    .setPositiveButton("Delete") { _, _ ->
                        if (store.delete(u.id)) {
                            notifyRefresh()
                            refreshMarkers()
                            com.roadwatch.ui.UiAlerts.success(view, "Hazard deleted")
                            dialog.dismiss()
                        } else {
                            com.roadwatch.ui.UiAlerts.error(view, "Delete failed")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        dialog.show()
    }

    private var pendingMove: com.roadwatch.data.UserHazard? = null
    private fun startMovePin(u: com.roadwatch.data.UserHazard, message: String? = null) {
        pendingMove = u
        val prompt = message ?: getString(com.roadwatch.app.R.string.tap_map_set_location)
        com.roadwatch.ui.UiAlerts.info(view, prompt)
        googleMap?.setOnMapClickListener { latLng ->
            finalizeMovePin(latLng)
        }
    }
    private fun finalizeMovePin(latLng: com.google.android.gms.maps.model.LatLng) {
        val u = pendingMove ?: return
        val ctx = requireContext()
        val store = com.roadwatch.data.HazardStore(ctx)
        val originalKey = com.roadwatch.data.SeedOverrides.keyOf(u.hazard)
        val updated = u.hazard.copy(lat = latLng.latitude, lng = latLng.longitude)
        store.upsertByKey(originalKey, updated)
        googleMap?.setOnMapClickListener(null)
        pendingMove = null
        notifyRefresh()
        com.roadwatch.ui.UiAlerts.success(view, "Location updated")
    }

    private fun selectBestCandidate(candidates: List<HazardCandidate>): HazardCandidate? {
        if (candidates.isEmpty()) return null
        val nearest = candidates.minOf { it.effectiveAlong }
        val window = candidates.filter { it.effectiveAlong - nearest <= PRIORITY_SUPERSEDE_DISTANCE_METERS }
        if (window.isEmpty()) return null
        val bestPriority = window.minOf { it.priority }
        return window.filter { it.priority == bestPriority }
            .minByOrNull { it.effectiveAlong }
    }

    private data class HazardCandidate(
        val hazard: com.roadwatch.data.Hazard,
        val along: Double,
        val distance: Double,
        val priority: Int,
    ) {
        val effectiveAlong: Double
            get() = if (along > 0.0) along else 0.0
    }

    private fun hazardPriority(type: HazardType): Int = when (type) {
        HazardType.SPEED_BUMP -> 0
        HazardType.SPEED_LIMIT_ZONE -> 1
        HazardType.POTHOLE -> 1
        HazardType.RUMBLE_STRIP -> 2
    }

    private enum class HazardUrgency { NEUTRAL, CAUTION, WARN }

    private fun Double.format(digits: Int = 6): String = String.format(Locale.US, "%.${digits}f", this)

    private fun notifyRefresh() {
        try {
            val refreshIntent = android.content.Intent("com.roadwatch.REFRESH_HAZARDS")
            requireContext().sendBroadcast(refreshIntent)
        } catch (_: Exception) {}
    }
}
