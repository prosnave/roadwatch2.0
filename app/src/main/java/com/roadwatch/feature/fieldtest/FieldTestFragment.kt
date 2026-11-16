package com.roadwatch.feature.fieldtest

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.roadwatch.app.R
import com.roadwatch.core.location.DriveModeService
import com.roadwatch.data.HazardTransfer
import com.roadwatch.drive.DriveSession
import com.roadwatch.trip.FieldExport
import com.roadwatch.trip.TripFormat
import com.roadwatch.trip.TripLog
import com.roadwatch.trip.TripSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Field-testing tools for a tester with no cable or laptop: run a simulated drive to verify the alert
 * pipeline while parked, review each alert from a real drive (mark the wrong ones), and send the
 * recorded data back as one zip.
 */
class FieldTestFragment : Fragment() {
    private val io = Executors.newSingleThreadExecutor()
    private var trips: List<File> = emptyList()
    private var current: File? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_field_test, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        view.findViewById<Button>(R.id.btn_simulate).setOnClickListener {
            if (DriveSession.state.running) {
                Toast.makeText(ctx, "Stop Drive Mode first", Toast.LENGTH_SHORT).show()
            } else {
                ctx.startForegroundService(DriveModeService.createSimulateIntent(ctx))
                Toast.makeText(ctx, "Simulation started. Switch apps to see the alert over them.", Toast.LENGTH_LONG).show()
            }
        }

        view.findViewById<Button>(R.id.btn_send).setOnClickListener {
            io.execute {
                val zip = FieldExport.createBundle(ctx)
                activity?.runOnUiThread { startActivity(HazardTransfer.share(ctx, zip, "application/zip", "Send field data")) }
            }
        }

        trips = TripLog.list(ctx).filter { it != DriveSession.tripLog?.file }.take(10)
        val spn = view.findViewById<Spinner>(R.id.spn_trips)
        if (trips.isEmpty()) {
            spn.visibility = View.GONE
        } else {
            spn.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, trips.map { label(it) })
            spn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) = show(trips[position])
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun label(f: File): String {
        val ts = f.name.removePrefix("trip_").removeSuffix(".jsonl").take(15)
        return runCatching {
            SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(ts)!!)
        }.getOrDefault(ts)
    }

    private fun show(file: File) {
        current = file
        io.execute {
            val s = TripSummary.parse(file.readLines().asSequence())
            activity?.runOnUiThread { render(file, s) }
        }
    }

    private fun render(file: File, s: TripSummary) {
        val v = view ?: return
        val mins = s.durationS / 60
        val skipText = if (s.skips.isEmpty()) "" else "\nIgnored on purpose: " + s.skips.entries.joinToString { "${it.key.removePrefix("SKIP_").lowercase().replace('_', ' ')} ×${it.value}" }
        v.findViewById<TextView>(R.id.txt_trip_summary).text =
            "${if (s.simulated) "SIMULATED · " else ""}${"%.1f".format(s.distanceKm)} km · $mins min · top ${s.maxKph} km/h\n" +
                "${s.alerts.size} alerts · ${s.reports} reports you made$skipText\nTap a verdict for each alert so it can be tuned:"
        val rows = v.findViewById<LinearLayout>(R.id.alert_rows)
        rows.removeAllViews()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        s.alerts.take(60).forEach { a ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 8) }
            val title = TextView(requireContext()).apply {
                text = "${fmt.format(Date(a.timeMs))}  ${a.speech}  (${a.speedKph} km/h)" + (a.verdict?.let { "  → $it" } ?: "")
                textSize = 14f
            }
            row.addView(title)
            val buttons = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("OK" to "Good", "WRONG" to "Wrong", "LATE" to "Too late", "EARLY" to "Too early").forEach { (code, text) ->
                buttons.addView(
                    Button(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        this.text = text
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            TripLog.append(file, TripFormat.custom("feedback", mapOf("t" to System.currentTimeMillis(), "alert" to a.id, "v" to code, "hazard" to a.hazardKey, "late" to true)))
                            title.text = "${fmt.format(Date(a.timeMs))}  ${a.speech}  → $code"
                        }
                    },
                )
            }
            row.addView(buttons)
            rows.addView(row)
        }
    }
}
