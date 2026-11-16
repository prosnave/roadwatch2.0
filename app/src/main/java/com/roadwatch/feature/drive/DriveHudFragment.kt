package com.roadwatch.feature.drive

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.roadwatch.app.BuildConfig
import com.roadwatch.app.R
import com.roadwatch.core.location.DriveModeService
import com.roadwatch.data.CommunityVotes
import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardStore
import com.roadwatch.data.HazardType
import com.roadwatch.data.SeedOverrides
import com.roadwatch.data.SeedRepository
import com.roadwatch.data.UserHazard
import com.roadwatch.drive.DriveActions
import com.roadwatch.drive.DriveSession
import com.roadwatch.drive.ReportActions
import com.roadwatch.engine.AlertText
import com.roadwatch.engine.Geo
import com.roadwatch.feature.report.DriverReportSheet
import com.roadwatch.prefs.AppPrefs
import com.roadwatch.ui.UiAlerts
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Heads-up display for Drive Mode. This screen is a pure *observer* of [DriveSession]: the foreground
 * service owns GPS, matching and alerts, so leaving this screen (e.g. to use Google Maps) does not
 * stop alerts. It adds the map, one-tap reporting with Undo, and live status.
 */
class DriveHudFragment : Fragment() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var googleMap: GoogleMap? = null

    private var sawRunning = false
    private var lastShownAlertId = -1L
    private var preferredZoom = DEFAULT_MAP_ZOOM
    private var userAdjustingCamera = false
    private var pendingMove: UserHazard? = null
    private var lastMarkerRefresh = 0L

    private val hideAlertCard = Runnable {
        view?.findViewById<MaterialCardView>(R.id.alert_overlay)?.visibility = View.GONE
    }

    private val sessionListener: (DriveSession.State) -> Unit = { s -> handler.post { render(s) } }
    private val storeListener: () -> Unit = { handler.post { refreshMarkers() } }

    private val checkProviders = object : Runnable {
        override fun run() {
            val ctx = context ?: return
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Toast.makeText(ctx, "GPS is off. Turn on Location to get alerts.", Toast.LENGTH_LONG).show()
            } else if (!hasFinePermission()) {
                Toast.makeText(ctx, "Location permission revoked. Stopping Drive Mode.", Toast.LENGTH_SHORT).show()
                stopDrive()
                return
            }
            handler.postDelayed(this, 15_000L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == false) {
                Toast.makeText(requireContext(), "Location permission required", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                showMyLocation()
                startServiceIfReady()
            }
            if (result[Manifest.permission.POST_NOTIFICATIONS] == false) {
                Toast.makeText(requireContext(), "Without notifications, alert pop-ups can't show (voice still works).", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_drive_hud, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensurePermissions()
        setupMap(view)
        setupControls(view)

        if (!DriveSession.state.running) startServiceIfReady()
        updateMuteUi(view)
        maybePromptOverlayPermission()
    }

    override fun onStart() {
        super.onStart()
        DriveSession.addListener(sessionListener)
        HazardStore.addListener(storeListener)
    }

    override fun onResume() {
        super.onResume()
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.postDelayed(checkProviders, 15_000L)
    }

    override fun onPause() {
        super.onPause()
        activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(checkProviders)
    }

    override fun onStop() {
        super.onStop()
        DriveSession.removeListener(sessionListener)
        HazardStore.removeListener(storeListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        googleMap = null
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---- setup -------------------------------------------------------------------------------

    private fun setupMap(view: View) {
        val tag = "drive_map"
        var mapFragment = childFragmentManager.findFragmentByTag(tag) as? SupportMapFragment
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance()
            childFragmentManager.beginTransaction().replace(R.id.map_container, mapFragment, tag).commitNow()
        }
        mapFragment.getMapAsync { gMap ->
            googleMap = gMap
            gMap.uiSettings.isMyLocationButtonEnabled = false
            gMap.uiSettings.isCompassEnabled = true
            gMap.isTrafficEnabled = true
            showMyLocation()

            AppPrefs.getDriveMapZoom(requireContext())?.let { preferredZoom = it.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM) }
            gMap.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) userAdjustingCamera = true
            }
            gMap.setOnCameraIdleListener {
                if (userAdjustingCamera) {
                    preferredZoom = gMap.cameraPosition.zoom.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                    AppPrefs.setDriveMapZoom(requireContext(), preferredZoom)
                }
                userAdjustingCamera = false
            }
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (night) runCatching { gMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_night)) }

            lastKnown()?.let { gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), preferredZoom)) }
            gMap.setPadding(dp(12), dp(12), dp(12), dp(12))
            gMap.setOnMarkerClickListener { marker ->
                (marker.tag as? Hazard)?.let { openHazardEditor(it); true } ?: false
            }
            refreshMarkers()
        }
    }

    private fun setupControls(view: View) {
        val ctx = requireContext()

        view.findViewById<MaterialButton>(R.id.btn_stop).setOnClickListener { stopDrive() }
        view.findViewById<MaterialButton>(R.id.btn_mute).setOnClickListener {
            if (AppPrefs.isMuted(ctx)) AppPrefs.clearMute(ctx) else AppPrefs.setMutedForMinutes(ctx, AppPrefs.getDefaultMuteMinutes(ctx))
            updateMuteUi(view)
        }

        val dirToggle = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_report_dir)
        fun updateScope(both: Boolean) {
            view.findViewById<TextView>(R.id.txt_report_scope).setText(
                if (both) R.string.report_scope_both else R.string.report_scope_one,
            )
        }
        dirToggle.check(if (AppPrefs.getReportDirectionality(ctx) == "BIDIRECTIONAL") R.id.btn_dir_both else R.id.btn_dir_one)
        updateScope(dirToggle.checkedButtonId == R.id.btn_dir_both)
        dirToggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val both = id == R.id.btn_dir_both
                AppPrefs.setReportDirectionality(ctx, if (both) "BIDIRECTIONAL" else "ONE_WAY")
                updateScope(both)
                view.findViewById<TextView>(R.id.txt_report_scope).announceForAccessibility(
                    getString(if (both) R.string.report_scope_both else R.string.report_scope_one),
                )
            }
        }

        view.findViewById<View>(R.id.btn_report_bump).setOnClickListener { quickReport(HazardType.SPEED_BUMP) }
        view.findViewById<View>(R.id.btn_report_pothole).setOnClickListener { quickReport(HazardType.POTHOLE) }
        view.findViewById<View>(R.id.btn_report_rumble).setOnClickListener { quickReport(HazardType.RUMBLE_STRIP) }
        // Zones need a start and end point, so they keep the sheet.
        view.findViewById<View>(R.id.btn_report_zone).setOnClickListener {
            DriverReportSheet.newInstance(HazardType.SPEED_LIMIT_ZONE).show(parentFragmentManager, "driver_report")
        }

        view.findViewById<View>(R.id.btn_alert_wrong).setOnClickListener {
            val id = DriveSession.state.lastAlert?.id ?: return@setOnClickListener
            ctx.startService(
                Intent(ctx, DriveModeService::class.java).setAction(DriveActions.FEEDBACK)
                    .putExtra(DriveActions.EXTRA_ALERT_ID, id).putExtra(DriveActions.EXTRA_VERDICT, "WRONG"),
            )
            view.findViewById<View>(R.id.alert_overlay).visibility = View.GONE
        }
        updateSpeedCardStyle(null)
    }

    // ---- observing the session --------------------------------------------------------------

    private fun render(s: DriveSession.State) {
        val v = view ?: return
        if (s.running) sawRunning = true
        if (sawRunning && !s.running) {
            // Stopped from the notification, by auto-stop, or an error.
            if (isAdded) parentFragmentManager.popBackStack()
            return
        }

        val fix = s.fix
        val kph = fix?.let { AlertText.kph(it.speedMps) } ?: 0
        v.findViewById<TextView>(R.id.txt_speed_value).apply {
            text = kph.toString()
            textSize = 48f
        }
        v.findViewById<TextView>(R.id.txt_bearing_value).text = s.courseDeg?.let { Geo.compass(it) } ?: "–"

        val next = s.upcoming.firstOrNull()
        val chip = v.findViewById<MaterialCardView>(R.id.next_chip)
        val subtitle = v.findViewById<TextView>(R.id.txt_next_subtitle)
        if (next != null) {
            val name = AlertText.capitalized(AlertText.name(next.hazard.type))
            v.findViewById<TextView>(R.id.txt_next_hazard).text = "$name in ${AlertText.shortDistance(next.alongM.toInt())}"
            chip.visibility = View.VISIBLE
            subtitle.text = if (s.night) "Night: earlier warnings on" else ""
        } else {
            chip.visibility = View.GONE
            subtitle.text = if (s.night) "Night: earlier warnings on" else ""
        }
        updateSpeedCardStyle(next?.alongM)

        s.lastAlert?.let { a ->
            if (a.id != lastShownAlertId) {
                lastShownAlertId = a.id
                showAlertCard(v, a.title, a.subtitle)
            }
        }

        val loc = s.location
        if (loc != null && !s.simulated) {
            googleMap?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude)).zoom(preferredZoom)
                        .bearing(s.courseDeg?.toFloat() ?: loc.bearing).tilt(45f).build(),
                ),
            )
        }
    }

    private fun showAlertCard(v: View, title: String, subtitle: String) {
        val card = v.findViewById<MaterialCardView>(R.id.alert_overlay)
        card.setCardBackgroundColor(ContextCompat.getColor(v.context, R.color.alert_warn))
        v.findViewById<TextView>(R.id.alert_text).text = if (subtitle.isBlank()) title else "$title • $subtitle"
        card.visibility = View.VISIBLE
        handler.removeCallbacks(hideAlertCard)
        handler.postDelayed(hideAlertCard, 8_000L)
    }

    private fun updateSpeedCardStyle(alongM: Double?) {
        val card = view?.findViewById<MaterialCardView>(R.id.speed_card) ?: return
        val ctx = card.context
        val (bg, fg, stroke) = when {
            alongM == null || alongM > CAUTION_DISTANCE_M -> Triple(R.color.surface, R.color.on_surface, R.color.outline)
            alongM > WARN_DISTANCE_M -> Triple(R.color.primary_container, R.color.on_primary_container, R.color.primary)
            else -> Triple(R.color.hazard_warn_background, R.color.hazard_warn_on_background, R.color.hazard_warn_stroke)
        }
        card.setCardBackgroundColor(ContextCompat.getColor(ctx, bg))
        val neutral = alongM == null || alongM > CAUTION_DISTANCE_M
        card.strokeWidth = if (neutral) 0 else dp(2)
        card.setStrokeColor(ContextCompat.getColor(ctx, stroke))
        val fgColor = ContextCompat.getColor(ctx, fg)
        listOf(R.id.txt_speed_value, R.id.txt_bearing_value, R.id.txt_speed_label, R.id.txt_direction_label, R.id.txt_next_subtitle)
            .forEach { id -> view?.findViewById<TextView>(id)?.setTextColor(fgColor) }
    }

    private fun updateMuteUi(view: View) {
        val ctx = requireContext()
        val chip = view.findViewById<MaterialCardView>(R.id.mute_chip)
        val btn = view.findViewById<MaterialButton>(R.id.btn_mute)
        if (AppPrefs.isMuted(ctx)) {
            val until = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(AppPrefs.getMutedUntilMillis(ctx)))
            view.findViewById<TextView>(R.id.txt_muted_until).text = "Muted until $until"
            chip.visibility = View.VISIBLE
            btn.text = getString(R.string.unmute)
        } else {
            chip.visibility = View.GONE
            btn.text = getString(R.string.mute_alerts_short)
        }
    }

    // ---- reporting ---------------------------------------------------------------------------

    private fun quickReport(type: HazardType) {
        val ctx = requireContext()
        val outcome = ReportActions.report(ctx, type)
        val r = outcome.result
        if (!outcome.ok || r == null) {
            UiAlerts.error(view, outcome.message)
            return
        }
        refreshMarkers()
        val bar = Snackbar.make(requireView(), outcome.message, 6_000)
        if (r.saved != null && r.result != SeedRepository.AddResult.DUPLICATE_NEARBY) {
            bar.setAction("UNDO") {
                if (SeedRepository(ctx).undo(r)) {
                    refreshMarkers()
                    Toast.makeText(ctx, "Report removed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        bar.setAnchorView(R.id.hazard_buttons_container)
        bar.show()
    }

    // ---- lifecycle helpers -------------------------------------------------------------------

    private fun startServiceIfReady() {
        if (DriveSession.state.running || !isAdded || !hasFinePermission()) return
        requireContext().startForegroundService(DriveModeService.createStartIntent(requireContext()))
    }

    private fun stopDrive() {
        requireContext().startService(DriveModeService.createStopIntent(requireContext()))
        parentFragmentManager.popBackStack()
    }

    private fun ensurePermissions() {
        val need = ArrayList<String>()
        if (!hasFinePermission()) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())

        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(requireContext())
                .setTitle("Turn on Location")
                .setMessage("Drive Mode needs GPS to know where hazards are.")
                .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Cancel") { _, _ -> parentFragmentManager.popBackStack() }
                .show()
        }
    }

    /** Offer the "Display over other apps" permission (once every few days) so alerts show above Maps/Waze. */
    private fun maybePromptOverlayPermission() {
        val ctx = requireContext()
        if (!AppPrefs.isOverlayEnabled(ctx) || Settings.canDrawOverlays(ctx)) return
        val prefs = ctx.getSharedPreferences("roadwatch_prompts", Context.MODE_PRIVATE)
        val last = prefs.getLong("overlay_prompt_at", 0L)
        if (System.currentTimeMillis() - last < 3 * 24 * 3600_000L) return
        prefs.edit().putLong("overlay_prompt_at", System.currentTimeMillis()).apply()
        AlertDialog.Builder(ctx)
            .setTitle("Show alerts over other apps?")
            .setMessage("So you see hazard warnings on top of Google Maps, Waze or music apps without opening RoadWatch. Voice alerts work either way.")
            .setPositiveButton("Allow") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(): android.location.Location? {
        DriveSession.state.location?.let { return it }
        if (!hasFinePermission()) return null
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.minByOrNull { it.accuracy }
    }

    @SuppressLint("MissingPermission")
    private fun showMyLocation() {
        if (!hasFinePermission()) return
        try { googleMap?.isMyLocationEnabled = true } catch (_: SecurityException) {}
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    // ---- map markers -------------------------------------------------------------------------

    private fun refreshMarkers() {
        val map = googleMap ?: return
        val ctx = context?.applicationContext ?: return
        lastMarkerRefresh = System.currentTimeMillis()
        val center = DriveSession.state.location
        io.execute {
            var hazards = SeedRepository(ctx).activeHazards()
            if (hazards.size > MAX_MARKERS && center != null) {
                hazards = hazards.filter { Geo.distanceMeters(center.latitude, center.longitude, it.lat, it.lng) < 10_000.0 }.take(MAX_MARKERS)
            }
            handler.post {
                if (googleMap == null || !isAdded) return@post
                map.clear()
                hazards.forEach { h ->
                    val res = when (h.type) {
                        HazardType.RUMBLE_STRIP -> R.drawable.ic_marker_rumble
                        HazardType.POTHOLE -> R.drawable.ic_marker_pothole
                        else -> R.drawable.ic_marker_bump
                    }
                    val scale = 1.0f + (CommunityVotes.getVotes(requireContext(), SeedOverrides.keyOf(h)) / 10.0f)
                    map.addMarker(MarkerOptions().position(LatLng(h.lat, h.lng)).title(h.type.name).icon(vectorToBitmap(res, scale)))?.tag = h
                }
            }
        }
    }

    private val iconCache = HashMap<Pair<Int, Int>, BitmapDescriptor>()

    private fun vectorToBitmap(resId: Int, scale: Float): BitmapDescriptor {
        val key = resId to (scale * 10).toInt()
        return iconCache.getOrPut(key) {
            val d = ResourcesCompat.getDrawable(resources, resId, null) ?: return BitmapDescriptorFactory.defaultMarker()
            val w = ((d.intrinsicWidth.takeIf { it > 0 } ?: 48) * scale).toInt().coerceAtLeast(24)
            val h = ((d.intrinsicHeight.takeIf { it > 0 } ?: 48) * scale).toInt().coerceAtLeast(24)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
            BitmapDescriptorFactory.fromBitmap(bmp)
        }
    }

    // ---- hazard editor (tap a marker) --------------------------------------------------------

    private fun openHazardEditor(h: Hazard) {
        if ((DriveSession.state.fix?.speedMps ?: 0f) > 2.5f) {
            UiAlerts.warn(view, "Park safely before editing a hazard")
            return
        }
        val ctx = requireContext()
        val store = HazardStore(ctx)
        val key = SeedOverrides.keyOf(h)
        val userMatch = store.list().find { SeedOverrides.keyOf(it.hazard) == key }
        if (userMatch != null) {
            showEditDialog(userMatch)
            return
        }
        val isActive = h.active && !SeedOverrides.isDisabled(ctx, key)
        AlertDialog.Builder(ctx)
            .setTitle("Manage: ${AlertText.capitalized(AlertText.name(h.type))}")
            .setMessage("This hazard comes from legacy seed data. You can hide/show it.")
            .setPositiveButton(if (isActive) "Hide (Mark Inactive)" else "Show (Mark Active)") { _, _ ->
                SeedOverrides.setDisabled(ctx, key, isActive)
                DriveSession.engine?.setHazards(SeedRepository(ctx).activeHazards())
                refreshMarkers()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(u: UserHazard) {
        val ctx = requireContext()
        val store = HazardStore(ctx)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_hazard, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radio_group_directionality)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_hazard_type)
        val quickLabel = dialogView.findViewById<TextView>(R.id.txt_quick_actions)
        val btnOpposite = dialogView.findViewById<MaterialButton>(R.id.btn_quick_opposite_lane)
        val btnDelete = dialogView.findViewById<MaterialButton>(R.id.btn_delete_hazard)
        val heading = dialogView.findViewById<android.widget.EditText>(R.id.edt_hazard_heading)
        heading.setText(if (u.hazard.headingKnown) u.hazard.reportedHeadingDeg.toString() else "")

        val types = HazardType.values().filter { it != HazardType.SPEED_LIMIT_ZONE }.map { it.name }
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, types)
        spinner.setSelection(types.indexOf(u.hazard.type.name).coerceAtLeast(0))
        radioGroup.check(if (u.hazard.directionality == "BIDIRECTIONAL") R.id.radio_two_way else R.id.radio_one_way)

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNeutralButton("Move Pin") { _, _ -> startMovePin(u) }
            .setNegativeButton("Cancel", null)
            .create()

        // Pilot phase: every tester can remove a wrong hazard; deletions are logged and undoable.
        btnDelete?.visibility = View.VISIBLE
        btnDelete?.setOnClickListener { confirmDelete(u) { dialog.dismiss() } }
        if (BuildConfig.IS_ADMIN) {
            quickLabel?.visibility = View.VISIBLE
            btnOpposite?.visibility = View.VISIBLE
            btnOpposite?.setOnClickListener {
                // Flip to the opposite carriageway: reverse the reported heading, then let the admin re-place the pin.
                if (!u.hazard.headingKnown) {
                    UiAlerts.error(view, "Set a known heading before reversing it")
                    return@setOnClickListener
                }
                val flipped = (u.hazard.reportedHeadingDeg + 180f) % 360f
                val ok = store.update(u.id) { it.copy(directionality = "ONE_WAY", reportedHeadingDeg = flipped, userBearing = flipped, headingValid = true, schemaVersion = 2) }
                if (ok) {
                    dialog.dismiss()
                    refreshMarkers()
                    startMovePin(u.copy(hazard = u.hazard.copy(reportedHeadingDeg = flipped)), getString(R.string.move_pin_opposite_prompt))
                } else UiAlerts.error(view, "Update failed")
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = heading.text.toString().trim()
            val bearing = value.toFloatOrNull()
            if (bearing == null || !bearing.isFinite() || bearing < 0f || bearing >= 360f) {
                heading.error = "Enter the applicable travel heading, from 0 to less than 360"
                return@setOnClickListener
            }
            val dir = if (radioGroup.checkedRadioButtonId == R.id.radio_two_way) "BIDIRECTIONAL" else "ONE_WAY"
            val type = HazardType.valueOf(spinner.selectedItem as String)
            if (store.update(u.id) { it.copy(
                    directionality = dir, type = type, reportedHeadingDeg = bearing,
                    headingValid = true, schemaVersion = 2,
                ) }) {
                refreshMarkers()
                UiAlerts.success(view, "Hazard updated")
                dialog.dismiss()
            } else UiAlerts.error(view, "Update failed")
        }
    }

    private fun confirmDelete(u: UserHazard, onDeleted: () -> Unit) {
        val ctx = requireContext()
        val name = AlertText.capitalized(AlertText.name(u.hazard.type))
        AlertDialog.Builder(ctx)
            .setTitle("Delete this $name?")
            .setMessage("It will stop alerting immediately on this phone. You can undo for a few seconds.")
            .setPositiveButton("Delete") { _, _ ->
                if (!HazardStore(ctx).delete(u.id)) {
                    UiAlerts.error(view, "Delete failed")
                    return@setPositiveButton
                }
                val h = u.hazard
                DriveSession.tripLog?.custom(
                    "edit",
                    mapOf(
                        "t" to System.currentTimeMillis(), "action" to "DELETE", "id" to u.id, "type" to h.type.name,
                        "lat" to h.lat, "lng" to h.lng, "dir" to h.directionality,
                        "hdg" to if (h.headingKnown) h.reportedHeadingDeg else null, "source" to h.source,
                    ),
                )
                onDeleted()
                refreshMarkers()
                val root = view ?: return@setPositiveButton
                Snackbar.make(root, "$name deleted", 8_000).setAction("UNDO") {
                    if (HazardStore(ctx).restore(u)) {
                        DriveSession.tripLog?.custom("edit", mapOf("t" to System.currentTimeMillis(), "action" to "UNDO_DELETE", "id" to u.id))
                        refreshMarkers()
                    } else UiAlerts.error(view, "Could not restore")
                }.show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMovePin(u: UserHazard, message: String? = null) {
        pendingMove = u
        UiAlerts.info(view, message ?: getString(R.string.tap_map_set_location))
        googleMap?.setOnMapClickListener { latLng ->
            val target = pendingMove ?: return@setOnMapClickListener
            HazardStore(requireContext()).update(target.id) { it.copy(lat = latLng.latitude, lng = latLng.longitude) }
            googleMap?.setOnMapClickListener(null)
            pendingMove = null
            refreshMarkers()
            UiAlerts.success(view, "Location updated")
        }
    }

    companion object {
        private const val WARN_DISTANCE_M = 75.0
        private const val CAUTION_DISTANCE_M = 200.0
        private const val MIN_MAP_ZOOM = 15.0f
        private const val MAX_MAP_ZOOM = 20.0f
        private const val DEFAULT_MAP_ZOOM = 18.5f
        private const val MAX_MARKERS = 800
    }
}
