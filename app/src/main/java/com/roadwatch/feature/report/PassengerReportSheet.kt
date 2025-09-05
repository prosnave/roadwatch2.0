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
        val spnType = view.findViewById<Spinner>(R.id.spn_type)
        val spnLane = view.findViewById<Spinner>(R.id.spn_lane)
        val edtNotes = view.findViewById<EditText>(R.id.edt_notes)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit)
        val zoneBlock = view.findViewById<LinearLayout>(R.id.zone_block)
        val edtSpeed = view.findViewById<EditText>(R.id.edt_speed_kph)
        val btnStart = view.findViewById<Button>(R.id.btn_set_start)
        val btnEnd = view.findViewById<Button>(R.id.btn_set_end)
        val txtZoneStatus = view.findViewById<TextView>(R.id.txt_zone_status)

        spnType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, HazardType.entries.map { it.name })
        spnLane.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("LEFT","RIGHT","CENTER","UNKNOWN"))

        var start: android.location.Location? = null
        var end: android.location.Location? = null

        spnType.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val type = HazardType.valueOf(spnType.selectedItem as String)
                zoneBlock.visibility = if (type == HazardType.SPEED_LIMIT_ZONE) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

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
            val type = HazardType.valueOf(spnType.selectedItem as String)
            val lane = spnLane.selectedItem as String
            val notes = edtNotes.text?.toString()?.trim().orEmpty()
            val loc = lastKnown()
            if (loc == null) {
                Toast.makeText(requireContext(), "Location unavailable", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var h = Hazard(
                type = type,
                lat = loc.latitude,
                lng = loc.longitude,
                active = true,
                bearingSide = lane,
                directionality = "UNKNOWN",
                source = "USER",
                createdAt = Instant.now()
            )
            if (type == HazardType.SPEED_LIMIT_ZONE) {
                val kph = edtSpeed.text.toString().toIntOrNull()
                val length = if (start != null && end != null) distance(start!!, end!!).toInt() else null
                h = h.copy(speedLimitKph = kph, zoneLengthMeters = length)
            }
            val result = SeedRepository(requireContext()).addUserHazardWithDedup(h)
            when (result) {
                com.roadwatch.data.SeedRepository.AddResult.ADDED -> Toast.makeText(requireContext(), "Reported ${type.name}", Toast.LENGTH_SHORT).show()
                com.roadwatch.data.SeedRepository.AddResult.DUPLICATE_NEARBY -> Toast.makeText(requireContext(), "Similar ${type.name.lowercase().replace('_',' ')} within 30 m", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(requireContext(), "Report failed", Toast.LENGTH_SHORT).show()
            }
            dismissAllowingStateLoss()
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
