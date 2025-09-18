package com.roadwatch.feature.report

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.roadwatch.app.R
import com.roadwatch.core.location.DriveModeService
import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import com.roadwatch.data.SeedRepository
import java.time.Instant
import kotlin.math.*

class DriverReportSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_HAZARD_TYPE = "hazard_type"

        fun newInstance(hazardType: HazardType? = null): DriverReportSheet {
            val fragment = DriverReportSheet()
            val args = Bundle()
            hazardType?.let { args.putString(ARG_HAZARD_TYPE, it.name) }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.sheet_driver_report, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hazardTypeName = arguments?.getString(ARG_HAZARD_TYPE)
        val hazardType = hazardTypeName?.let { HazardType.valueOf(it) }

        val toggleDir = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggle_direction)
        val roadTypeLabel = view.findViewById<android.widget.TextView>(R.id.txt_road_type_label)
        // Submit button for non-zone hazards (hazard type comes from card used to open the sheet)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit_driver)
        val zoneBlock = view.findViewById<android.widget.LinearLayout>(R.id.zone_block_driver)
        val edtSpeed = view.findViewById<android.widget.EditText>(R.id.edt_speed_kph_driver)
        val btnZStart = view.findViewById<Button>(R.id.btn_zone_start_driver)
        val btnZEnd = view.findViewById<Button>(R.id.btn_zone_end_driver)
        val txtZStatus = view.findViewById<android.widget.TextView>(R.id.txt_zone_status_driver)
        val btnZSubmit = view.findViewById<Button>(R.id.btn_zone_submit_driver)
        // Only show zone UI when reporting a speed limit zone; otherwise show a single Submit
        if (hazardType == HazardType.SPEED_LIMIT_ZONE) {
            zoneBlock.visibility = View.VISIBLE
            btnSubmit.visibility = View.GONE
            // Hide road type controls for zones; they default to BIDIRECTIONAL
            roadTypeLabel.visibility = View.GONE
            toggleDir.visibility = View.GONE
        } else {
            zoneBlock.visibility = View.GONE
            btnSubmit.visibility = View.VISIBLE
            roadTypeLabel.visibility = View.VISIBLE
            toggleDir.visibility = View.VISIBLE
        }

        var zoneStart: android.location.Location? = null
        var zoneEnd: android.location.Location? = null

        fun report(selected: HazardType) {
            val loc = DriveModeService.lastKnownLocation
            if (loc == null) {
                com.roadwatch.ui.UiAlerts.error(view, "Location unavailable")
                return
            }

            val userBearing = if (loc.hasBearing()) loc.bearing else 0.0f
            val directionality = if (toggleDir?.checkedButtonId == R.id.btn_two_way) "BIDIRECTIONAL" else "ONE_WAY"

            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val email = com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext())
            val password = com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext())
            if (baseUrl.isNotEmpty() && !email.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val payload = org.json.JSONObject().apply {
                    put("type", selected.name)
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("directionality", directionality)
                    put("reported_heading_deg", userBearing)
                }
                val result = com.roadwatch.network.ApiClient.createHazardWithBasic(baseUrl, email, password, payload)
                if (result.isSuccess) {
                    com.roadwatch.ui.UiAlerts.success(view, "Reported ${selected.name}")
                    if (com.roadwatch.prefs.AppPrefs.isHapticsEnabled(requireContext())) {
                        try { com.roadwatch.core.util.Haptics.tap(requireContext()) } catch (_: Exception) {}
                    }
                    try {
                        val refreshIntent = android.content.Intent("com.roadwatch.REFRESH_HAZARDS")
                        requireContext().sendBroadcast(refreshIntent)

                        val details = "Type: ${selected.name}\n" +
                                "Location: (${loc.latitude}, ${loc.longitude})\n" +
                                "User Bearing: $userBearing\n" +
                                "Directionality: $directionality"
                        val reportIntent = android.content.Intent("com.roadwatch.HAZARD_REPORTED").apply {
                            putExtra("hazard_details", details)
                        }
                        requireContext().sendBroadcast(reportIntent)
                    } catch (_: Exception) {}
                    dismissAllowingStateLoss()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "error"
                    if (msg.contains("409")) com.roadwatch.ui.UiAlerts.warn(view, "Similar ${selected.name.lowercase().replace('_',' ')} nearby")
                    else com.roadwatch.ui.UiAlerts.error(view, "Report failed: $msg")
                }
            } else {
                val repo = SeedRepository(requireContext())
                val hazard = Hazard(
                    type = selected,
                    lat = loc.latitude,
                    lng = loc.longitude,
                    reportedHeadingDeg = userBearing,
                    directionality = directionality,
                    userBearing = userBearing,
                    active = true,
                    source = "USER",
                    createdAt = Instant.now()
                )
                val result = repo.addUserHazardWithDedup(hazard)
                when (result) {
                    SeedRepository.AddResult.ADDED -> {
                        handleHazardAdded(view, hazard, "Reported ${selected.name}", allowDirectionConfirm = true)
                    }
                    SeedRepository.AddResult.DUPLICATE_NEARBY -> com.roadwatch.ui.UiAlerts.warn(view, "Similar ${selected.name.lowercase().replace('_',' ')} nearby")
                    else -> com.roadwatch.ui.UiAlerts.error(view, "Report failed")
                }
            }
        }

        // Non-zone flow: just submit with the hazard type passed when opening the sheet
        btnSubmit.setOnClickListener {
            val selected = hazardType
            if (selected == null) {
                com.roadwatch.ui.UiAlerts.error(view, "No hazard type specified.")
                return@setOnClickListener
            }
            if (selected == HazardType.SPEED_LIMIT_ZONE) {
                // Shouldn't happen (zone uses its own block)
                return@setOnClickListener
            }
            report(selected)
        }

        // Non-zone flow is handled via btnSubmit; zone flow via btnZSubmit
        fun validateZone(): String? {
            val kph = edtSpeed.text.toString().toIntOrNull()
            if (kph == null) {
                btnZSubmit.isEnabled = false
                return "Please enter a valid speed limit."
            }
            if (zoneStart == null) {
                btnZSubmit.isEnabled = false
                return "Please set a start point."
            }
            if (zoneEnd == null) {
                btnZSubmit.isEnabled = false
                return "Please set an end point."
            }
            val distance = distance(zoneStart!!, zoneEnd!!)
            if (distance < 100) {
                btnZSubmit.isEnabled = false
                return "Zone is too short (min 100m)."
            }
            if (distance > 2000) {
                btnZSubmit.isEnabled = false
                return "Zone is too long (max 2 km)."
            }
            btnZSubmit.isEnabled = true
            return null
        }

        edtSpeed.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { validateZone() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnZStart.setOnClickListener {
            val loc = DriveModeService.lastKnownLocation
            if (loc == null) {
                com.roadwatch.ui.UiAlerts.error(view, "Location unavailable.")
                return@setOnClickListener
            }
            zoneStart = loc
            val startText = zoneStart?.let { String.format("%.5f, %.5f", it.latitude, it.longitude) } ?: "—"
            val endText = zoneEnd?.let { String.format("%.5f, %.5f", it.latitude, it.longitude) } ?: "—"
            txtZStatus.text = "Start: $startText   End: $endText"
            validateZone()
        }
        btnZEnd.setOnClickListener {
            val loc = DriveModeService.lastKnownLocation
            if (loc == null) {
                com.roadwatch.ui.UiAlerts.error(view, "Location unavailable.")
                return@setOnClickListener
            }
            zoneEnd = loc
            val startText = zoneStart?.let { String.format("%.5f, %.5f", it.latitude, it.longitude) } ?: "—"
            val endText = zoneEnd?.let { String.format("%.5f, %.5f", it.latitude, it.longitude) } ?: "—"
            txtZStatus.text = "Start: $startText   End: $endText"
            val msg = validateZone()
            if (msg != null) {
                com.roadwatch.ui.UiAlerts.error(view, msg)
            }
        }
        btnZSubmit.setOnClickListener {
            val errorMessage = validateZone()
            if (errorMessage != null) {
                com.roadwatch.ui.UiAlerts.error(view, errorMessage)
                if (errorMessage.contains("Zone is too short")) {
                    zoneEnd = null
                    val startText = zoneStart?.let { String.format("%.5f, %.5f", it.latitude, it.longitude) } ?: "—"
                    txtZStatus.text = "Start: $startText   End: —"
                    validateZone()
                }
                return@setOnClickListener
            }

            val loc = DriveModeService.lastKnownLocation
            if (loc == null) { com.roadwatch.ui.UiAlerts.error(view, "Location unavailable"); return@setOnClickListener }
            val kph = edtSpeed.text.toString().toInt()
            val len = distance(zoneStart!!, zoneEnd!!).toInt()
            val userBearing = if (loc.hasBearing()) loc.bearing else 0.0f
            val directionality = "BIDIRECTIONAL"
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val email = com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext())
            val password = com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext())
            if (baseUrl.isNotEmpty() && !email.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val payload = org.json.JSONObject().apply {
                    put("type", HazardType.SPEED_LIMIT_ZONE.name)
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("directionality", directionality)
                    put("reported_heading_deg", userBearing)
                    put("speed_limit_kph", kph)
                    put("zone_start_lat", zoneStart?.latitude)
                    put("zone_start_lng", zoneStart?.longitude)
                    put("zone_end_lat", zoneEnd?.latitude)
                    put("zone_end_lng", zoneEnd?.longitude)
                }
                val result = com.roadwatch.network.ApiClient.createHazardWithBasic(baseUrl, email, password, payload)
                if (result.isSuccess) {
                    com.roadwatch.ui.UiAlerts.success(view, "Zone reported")
                    try {
                        val refreshIntent = android.content.Intent("com.roadwatch.REFRESH_HAZARDS")
                        requireContext().sendBroadcast(refreshIntent)

                        val details = "Type: SPEED_LIMIT_ZONE\n" +
                                "Location: (${loc.latitude}, ${loc.longitude})\n" +
                                "User Bearing: ${userBearing}\n" +
                                "Directionality: ${directionality}"
                        val reportIntent = android.content.Intent("com.roadwatch.HAZARD_REPORTED").apply {
                            putExtra("hazard_details", details)
                        }
                        requireContext().sendBroadcast(reportIntent)
                    } catch (_: Exception) {}
                    dismissAllowingStateLoss()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "error"
                    if (msg.contains("409")) com.roadwatch.ui.UiAlerts.warn(view, "Similar zone nearby")
                    else com.roadwatch.ui.UiAlerts.error(view, "Report failed: $msg")
                }
            } else {
                val repo = SeedRepository(requireContext())
                val h = Hazard(
                    type = HazardType.SPEED_LIMIT_ZONE,
                    lat = loc.latitude,
                    lng = loc.longitude,
                    reportedHeadingDeg = userBearing,
                    directionality = directionality,
                    userBearing = userBearing,
                    active = true,
                    source = "USER",
                    createdAt = Instant.now(),
                    speedLimitKph = kph,
                    zoneLengthMeters = len,
                    zoneStartLat = zoneStart?.latitude,
                    zoneStartLng = zoneStart?.longitude,
                    zoneEndLat = zoneEnd?.latitude,
                    zoneEndLng = zoneEnd?.longitude,
                )
                when (repo.addUserHazardWithDedup(h)) {
                    SeedRepository.AddResult.ADDED -> {
                        handleHazardAdded(view, h, "Zone reported", allowDirectionConfirm = false)
                    }
                    SeedRepository.AddResult.DUPLICATE_NEARBY -> com.roadwatch.ui.UiAlerts.warn(view, "Similar zone nearby")
                    else -> com.roadwatch.ui.UiAlerts.error(view, "Report failed")
                }
            }
        }
    }

    private fun handleHazardAdded(view: View, hazard: Hazard, successMessage: String, allowDirectionConfirm: Boolean) {
        val proceed: (Hazard) -> Unit = { finalHazard -> finalizeHazardReport(view, finalHazard, successMessage) }
        if (allowDirectionConfirm) {
            maybeConfirmDirection(view, hazard, proceed)
        } else {
            proceed(hazard)
        }
    }

    private fun maybeConfirmDirection(view: View, hazard: Hazard, complete: (Hazard) -> Unit) {
        val ctx = requireContext()
        val lastDirection = com.roadwatch.prefs.AppPrefs.getLastHazardDirection(ctx)
        if (lastDirection == null || hazard.directionality.equals(lastDirection, ignoreCase = true)) {
            complete(hazard)
            return
        }

        val choices = listOf(
            "One-way" to "ONE_WAY",
            "Two-way" to "BIDIRECTIONAL"
        )
        val labels = choices.map { it.first }.toTypedArray()
        val idxDefault = choices.indexOfFirst { it.second.equals(hazard.directionality, true) }.let { if (it >= 0) it else 0 }
        var selectedIndex = idxDefault
        val message = "Previous hazard was ${niceDirectionLabel(lastDirection)}. Confirm road type for this hazard."

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Confirm road type")
            .setMessage(message)
            .setSingleChoiceItems(labels, idxDefault) { _, which -> selectedIndex = which }
            .setPositiveButton("Save") { _, _ ->
                val chosen = choices.getOrNull(selectedIndex)?.second ?: hazard.directionality
                if (!chosen.equals(hazard.directionality, true)) {
                    val updated = hazard.copy(directionality = chosen)
                    val store = com.roadwatch.data.HazardStore(ctx)
                    val key = com.roadwatch.data.SeedOverrides.keyOf(hazard)
                    if (store.upsertByKey(key, updated)) {
                        complete(updated)
                    } else {
                        com.roadwatch.ui.UiAlerts.error(view, "Couldn't update road type")
                        complete(hazard)
                    }
                } else {
                    complete(hazard)
                }
            }
            .setNegativeButton("Keep") { _, _ -> complete(hazard) }
            .setOnCancelListener { complete(hazard) }
            .show()
    }

    private fun finalizeHazardReport(view: View, hazard: Hazard, successMessage: String) {
        com.roadwatch.ui.UiAlerts.success(view, successMessage)
        if (com.roadwatch.prefs.AppPrefs.isHapticsEnabled(requireContext())) {
            try { com.roadwatch.core.util.Haptics.tap(requireContext()) } catch (_: Exception) {}
        }
        try {
            val refreshIntent = android.content.Intent("com.roadwatch.REFRESH_HAZARDS")
            requireContext().sendBroadcast(refreshIntent)

            val details = "Type: ${hazard.type.name}\n" +
                    "Location: (${hazard.lat}, ${hazard.lng})\n" +
                    "User Bearing: ${hazard.userBearing}\n" +
                    "Directionality: ${hazard.directionality}"
            val reportIntent = android.content.Intent("com.roadwatch.HAZARD_REPORTED").apply {
                putExtra("hazard_details", details)
            }
            requireContext().sendBroadcast(reportIntent)
        } catch (_: Exception) {}

        try { com.roadwatch.prefs.AppPrefs.setLastHazardDirection(requireContext(), hazard.directionality) } catch (_: Exception) {}
        dismissAllowingStateLoss()
    }

    private fun niceDirectionLabel(direction: String?): String {
        return when (direction?.uppercase()) {
            "ONE_WAY" -> "One-way"
            "BIDIRECTIONAL" -> "Two-way"
            "OPPOSITE" -> getString(com.roadwatch.app.R.string.mark_wrong_direction)
            else -> "Unknown"
        }
    }

    override fun onResume() {
        super.onResume()
        // Close sheet if location permission revoked or providers disabled while open
        val hasFine = requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!hasFine || !enabled) {
            try { dismissAllowingStateLoss() } catch (_: Exception) {}
        }
    }
}

private fun distance(a: android.location.Location, b: android.location.Location): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val x = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(x), sqrt(1 - x))
    return R * c
}
