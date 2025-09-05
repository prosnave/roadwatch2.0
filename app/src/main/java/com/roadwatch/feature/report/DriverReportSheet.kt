package com.roadwatch.feature.report

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.roadwatch.app.R
import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import com.roadwatch.data.SeedRepository
import java.time.Instant

class DriverReportSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.sheet_driver_report, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val typeSpinner = view.findViewById<Spinner>(R.id.spn_type)
        val btnQuick = view.findViewById<Button>(R.id.btn_quick)
        val btnReport = view.findViewById<Button>(R.id.btn_report)

        val isAdmin = com.roadwatch.app.BuildConfig.IS_ADMIN
        val items = if (isAdmin) HazardType.entries.filter { it != HazardType.SPEED_LIMIT_ZONE }.map { it.name } else listOf(HazardType.SPEED_BUMP.name)
        typeSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
        if (!isAdmin) {
            typeSpinner.isEnabled = false
        }

        fun report(selected: HazardType) {
            val loc = lastKnown() ?: run {
                Toast.makeText(requireContext(), "Location unavailable", Toast.LENGTH_SHORT).show()
                return
            }
            val repo = SeedRepository(requireContext())
            val result = repo.addUserHazardWithDedup(
                    Hazard(
                        type = selected,
                        lat = loc.latitude,
                        lng = loc.longitude,
                        active = true,
                        source = "USER",
                        createdAt = Instant.now()
                    )
                )
            when (result) {
                SeedRepository.AddResult.ADDED -> Toast.makeText(requireContext(), "Reported ${selected.name}", Toast.LENGTH_SHORT).show()
                SeedRepository.AddResult.DUPLICATE_NEARBY -> Toast.makeText(requireContext(), "Similar ${selected.name.lowercase().replace('_',' ')} within 30 m", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(requireContext(), "Report failed", Toast.LENGTH_SHORT).show()
            }
            dismissAllowingStateLoss()
        }

        btnQuick.setOnClickListener {
            // Default type is SPEED_BUMP as a sensible default
            report(HazardType.SPEED_BUMP)
        }
        btnReport.setOnClickListener {
            val selected = HazardType.valueOf(typeSpinner.selectedItem as String)
            report(selected)
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
