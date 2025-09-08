package com.roadwatch.feature.report

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.roadwatch.app.R
import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import com.roadwatch.data.SeedRepository
import java.time.Instant
import kotlin.math.*

class PassengerReportSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.sheet_passenger_report, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val btnBump = view.findViewById<Button>(R.id.btn_type_bump_p)
        val btnPothole = view.findViewById<Button>(R.id.btn_type_pothole_p)
        val btnRumble = view.findViewById<Button>(R.id.btn_type_rumble_p)
        val toggleLane = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggle_lane)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit)
        val zoneBlock = view.findViewById<LinearLayout>(R.id.zone_block)
        val edtSpeed = view.findViewById<EditText>(R.id.edt_speed_kph)
        val btnStart = view.findViewById<Button>(R.id.btn_set_start)
        val btnEnd = view.findViewById<Button>(R.id.btn_set_end)
        val txtZoneStatus = view.findViewById<TextView>(R.id.txt_zone_status)
        val chkBoth = view.findViewById<CheckBox>(R.id.chk_both_directions)

        var selectedType: HazardType = HazardType.SPEED_BUMP
        btnBump.setOnClickListener { selectedType = HazardType.SPEED_BUMP }
        btnPothole.setOnClickListener { selectedType = HazardType.POTHOLE }
        btnRumble.setOnClickListener { selectedType = HazardType.RUMBLE_STRIP }

        var start: android.location.Location? = null
        var end: android.location.Location? = null

        // All three types are spot hazards; keep zone UI hidden by default

        fun currentLoc(): android.location.Location? {
            val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val hasFine = requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasFine) return null
            val providers = lm.getProviders(true)
            var best: android.location.Location? = null
            for (p in providers) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.accuracy < best!!.accuracy) best = l
            }
            return best
        }

        btnStart.setOnClickListener {
            start = currentLoc()
            txtZoneStatus.text = "Start: ${start?.latitude?.let { String.format("%.5f", it) } ?: "—"}   End: ${end?.latitude?.let { String.format("%.5f", it) } ?: "—"}"
        }
        btnEnd.setOnClickListener {
            end = currentLoc()
            txtZoneStatus.text = "Start: ${start?.latitude?.let { String.format("%.5f", it) } ?: "—"}   End: ${end?.latitude?.let { String.format("%.5f", it) } ?: "—"}"
        }

        btnSubmit.setOnClickListener {
            val type = selectedType
            val loc = lastKnown()
            if (loc == null) { com.roadwatch.ui.UiAlerts.error(view, "Location unavailable"); return@setOnClickListener }
            fun defaultDirectionality(t: HazardType): String = when (t) {
                HazardType.SPEED_LIMIT_ZONE -> "BIDIRECTIONAL"
                else -> "ONE_WAY"
            }
            var h = Hazard(
                type = type,
                lat = loc.latitude,
                lng = loc.longitude,
                active = true,
                directionality = if (chkBoth.isChecked) "BIDIRECTIONAL" else defaultDirectionality(type),
                source = "USER",
                createdAt = Instant.now()
            )
            if (type == HazardType.SPEED_LIMIT_ZONE) {
                val kph = edtSpeed.text.toString().toIntOrNull()
                val length = if (start != null && end != null) distance(start!!, end!!).toInt() else null
                if (start == null || end == null) {
                    Toast.makeText(requireContext(), "Set start and end of zone", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (length != null && length < 100) {
                    Toast.makeText(requireContext(), "Zone too short (min 100m)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                h = h.copy(
                    speedLimitKph = kph,
                    zoneLengthMeters = length,
                    zoneStartLat = start?.latitude,
                    zoneStartLng = start?.longitude,
                    zoneEndLat = end?.latitude,
                    zoneEndLng = end?.longitude,
                )
            }
            val result = SeedRepository(requireContext()).addUserHazardWithDedup(h)
            when (result) {
                com.roadwatch.data.SeedRepository.AddResult.ADDED -> {
                    com.roadwatch.ui.UiAlerts.success(view, "Reported ${type.name}")
                    if (com.roadwatch.prefs.AppPrefs.isHapticsEnabled(requireContext())) {
                        try { com.roadwatch.core.util.Haptics.tap(requireContext()) } catch (_: Exception) {}
                    }
                }
                com.roadwatch.data.SeedRepository.AddResult.DUPLICATE_NEARBY -> com.roadwatch.ui.UiAlerts.warn(view, "Similar ${type.name.lowercase().replace('_',' ')} within 30 m")
                else -> com.roadwatch.ui.UiAlerts.error(view, "Report failed")
            }
            dismissAllowingStateLoss()
        }
    }

    override fun onResume() {
        super.onResume()
        // Defensive: if this were ever opened and location is unavailable, close it
        val hasFine = requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!hasFine || !enabled) {
            try { dismissAllowingStateLoss() } catch (_: Exception) {}
        }
    }

    private fun lastKnown(): Location? {
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val hasFine = requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) return null
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            val l = lm.getLastKnownLocation(p) ?: continue
            if (best == null || l.accuracy < best!!.accuracy) best = l
        }
        return best
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
