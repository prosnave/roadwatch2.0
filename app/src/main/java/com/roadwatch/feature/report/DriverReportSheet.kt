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

        // Large hazard type buttons
        val btnBump = view.findViewById<Button>(R.id.btn_type_bump)
        val btnPothole = view.findViewById<Button>(R.id.btn_type_pothole)
        val btnRumble = view.findViewById<Button>(R.id.btn_type_rumble)
        val btnZone = view.findViewById<Button>(R.id.btn_type_zone)
        val zoneBlock = view.findViewById<android.widget.LinearLayout>(R.id.zone_block_driver)
        val edtSpeed = view.findViewById<android.widget.EditText>(R.id.edt_speed_kph_driver)
        val btnZStart = view.findViewById<Button>(R.id.btn_zone_start_driver)
        val btnZEnd = view.findViewById<Button>(R.id.btn_zone_end_driver)
        val txtZStatus = view.findViewById<android.widget.TextView>(R.id.txt_zone_status_driver)
        val btnZSubmit = view.findViewById<Button>(R.id.btn_zone_submit_driver)
        val typeHeader = view.findViewById<android.widget.TextView>(R.id.txt_choose_type)
        val typeContainer = view.findViewById<android.widget.LinearLayout>(R.id.type_buttons_container)

        // Show large type buttons for all users (no separate quick report)
        if (hazardType == HazardType.SPEED_LIMIT_ZONE) {
            typeHeader.visibility = View.GONE
            typeContainer.visibility = View.GONE
            zoneBlock.visibility = View.VISIBLE
        } else {
            typeHeader.visibility = View.VISIBLE
            typeContainer.visibility = View.VISIBLE
            zoneBlock.visibility = View.GONE
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
            val roadBearing = getRoadBearing(loc.latitude, loc.longitude) // Placeholder
            val bearingDifference = (userBearing - roadBearing + 360) % 360
            val directionality = if (selected == HazardType.SPEED_LIMIT_ZONE) {
                "BIDIRECTIONAL"
            } else if (bearingDifference <= 30 || bearingDifference >= 330) {
                "ONE_WAY"
            } else {
                "OPPOSITE"
            }

            val repo = SeedRepository(requireContext())
            val result = repo.addUserHazardWithDedup(
                Hazard(
                    type = selected,
                    lat = loc.latitude,
                    lng = loc.longitude,
                    reportedHeadingDeg = userBearing,
                    directionality = directionality,
                    userBearing = userBearing,
                    roadBearing = roadBearing,
                    active = true,
                    source = "USER",
                    createdAt = Instant.now()
                )
            )
            when (result) {
                SeedRepository.AddResult.ADDED -> {
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
                                "Road Bearing: $roadBearing\n" +
                                "Directionality: $directionality"
                        val reportIntent = android.content.Intent("com.roadwatch.HAZARD_REPORTED").apply {
                            putExtra("hazard_details", details)
                        }
                        requireContext().sendBroadcast(reportIntent)
                    } catch (_: Exception) {}
                    dismissAllowingStateLoss()
                }
                SeedRepository.AddResult.DUPLICATE_NEARBY -> com.roadwatch.ui.UiAlerts.warn(view, "Similar ${selected.name.lowercase().replace('_',' ')} within 30 m")
                else -> com.roadwatch.ui.UiAlerts.error(view, "Report failed")
            }
            // Only dismiss on success; keep sheet open for errors/duplicates so user can adjust
        }

        btnBump.setOnClickListener { report(HazardType.SPEED_BUMP) }
        btnPothole.setOnClickListener { report(HazardType.POTHOLE) }
        btnRumble.setOnClickListener { report(HazardType.RUMBLE_STRIP) }
        btnZone.setOnClickListener { zoneBlock.visibility = View.VISIBLE }
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
            validateZone()
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
            val repo = SeedRepository(requireContext())
            val userBearing = if (loc.hasBearing()) loc.bearing else 0.0f
            val roadBearing = getRoadBearing(loc.latitude, loc.longitude) // Placeholder
            val bearingDifference = (userBearing - roadBearing + 360) % 360
            val directionality = "BIDIRECTIONAL"

            val h = Hazard(
                type = HazardType.SPEED_LIMIT_ZONE,
                lat = loc.latitude,
                lng = loc.longitude,
                reportedHeadingDeg = userBearing,
                directionality = directionality,
                userBearing = userBearing,
                roadBearing = roadBearing,
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
                    com.roadwatch.ui.UiAlerts.success(view, "Zone reported")
                    try {
                        val refreshIntent = android.content.Intent("com.roadwatch.REFRESH_HAZARDS")
                        requireContext().sendBroadcast(refreshIntent)

                        val details = "Type: ${h.type.name}\n" +
                                "Location: (${h.lat}, ${h.lng})\n" +
                                "User Bearing: ${h.userBearing}\n" +
                                "Road Bearing: ${h.roadBearing}\n" +
                                "Directionality: ${h.directionality}"
                        val reportIntent = android.content.Intent("com.roadwatch.HAZARD_REPORTED").apply {
                            putExtra("hazard_details", details)
                        }
                        requireContext().sendBroadcast(reportIntent)
                    } catch (_: Exception) {}
                    dismissAllowingStateLoss()
                }
                SeedRepository.AddResult.DUPLICATE_NEARBY -> com.roadwatch.ui.UiAlerts.warn(view, "Similar zone nearby")
                else -> com.roadwatch.ui.UiAlerts.error(view, "Report failed")
            }
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

// Placeholder for road bearing service
private fun getRoadBearing(lat: Double, lng: Double): Float {
    // In a real app, this would call a mapping service API
    // For now, we'll return a random bearing for simulation
    return (0..360).random().toFloat()
}

private fun distance(a: android.location.Location, b: android.location.Location): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val x = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(x), sqrt(1 - x))
    return R * c
}
