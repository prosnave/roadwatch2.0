package com.roadwatch.feature.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.roadwatch.app.BuildConfig
import com.roadwatch.app.R
import com.roadwatch.core.location.DriveModeService
import com.roadwatch.data.SeedRepository
import com.roadwatch.drive.DriveSession
import com.roadwatch.engine.Geo
import com.roadwatch.feature.drive.DriveHudFragment
import com.roadwatch.feature.fieldtest.FieldTestFragment
import com.roadwatch.feature.settings.SettingsFragment
import com.roadwatch.prefs.AppPrefs
import java.util.concurrent.Executors

class HomeFragment : Fragment() {
    private val io = Executors.newSingleThreadExecutor()
    private var hazardCount = 0
    private var headingReviewCount = 0

    private val sessionListener: (DriveSession.State) -> Unit = { s -> view?.post { render(s) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.txt_version).text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_ID}"

        view.findViewById<Button>(R.id.btn_start_drive).setOnClickListener {
            ensureLocationEnabled { open(DriveHudFragment()) }
        }
        view.findViewById<Button>(R.id.btn_stop_drive).setOnClickListener {
            requireContext().startService(DriveModeService.createStopIntent(requireContext()))
        }
        view.findViewById<Button>(R.id.btn_open_settings).setOnClickListener { open(SettingsFragment()) }
        view.findViewById<Button>(R.id.btn_field_test).setOnClickListener { open(FieldTestFragment()) }

        io.execute {
            val hazards = SeedRepository(requireContext().applicationContext).activeHazards()
            hazardCount = hazards.size
            headingReviewCount = hazards.count { !it.isZone && !it.headingKnown }
            view.post { render(DriveSession.state) }
        }
        view.post { suggestResume(view) }
    }

    override fun onStart() {
        super.onStart()
        DriveSession.addListener(sessionListener)
    }

    override fun onStop() {
        super.onStop()
        DriveSession.removeListener(sessionListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun open(f: Fragment) {
        parentFragmentManager.beginTransaction().replace(R.id.fragment_container, f).addToBackStack(null).commit()
    }

    private fun render(s: DriveSession.State) {
        val v = view ?: return
        val start = v.findViewById<Button>(R.id.btn_start_drive)
        val stop = v.findViewById<Button>(R.id.btn_stop_drive)
        val status = v.findViewById<TextView>(R.id.status_text)
        if (s.running) {
            start.text = "Open Drive Mode"
            stop.visibility = View.VISIBLE
            status.text = if (s.simulated) "Simulation running • ${s.alertCount} alerts so far"
            else "Drive Mode running • ${s.alertCount} alerts so far\nAlerts keep working if you switch to Maps or lock the screen."
        } else {
            start.text = getString(R.string.start_drive_mode)
            stop.visibility = View.GONE
            status.text = "Ready • $hazardCount hazards on this phone" +
                if (headingReviewCount > 0) "\n$headingReviewCount need heading review and will not warn. Park, open Drive Mode and tap their map pins to edit." else ""
        }
    }

    /** After an automatic stop, offer to resume if the phone has since moved a good distance (e.g. you drove off again). */
    private fun suggestResume(view: View) {
        val ctx = context ?: return
        if (DriveSession.state.running || !AppPrefs.isAutoResumeEnabled(ctx)) return
        val lat = AppPrefs.getLastAutoStopLat(ctx) ?: return
        val lng = AppPrefs.getLastAutoStopLng(ctx) ?: return
        val at = AppPrefs.getLastAutoStopAt(ctx) ?: return
        if (System.currentTimeMillis() - at > 2 * 3600_000L) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.minByOrNull { it.accuracy } ?: return
        val d = Geo.distanceMeters(lat, lng, best.latitude, best.longitude)
        if (d > 100.0) {
            AlertDialog.Builder(ctx)
                .setTitle("Resume Drive Mode?")
                .setMessage("You've moved ${d.toInt()} m since Drive Mode stopped.")
                .setPositiveButton("Resume") { _, _ ->
                    AppPrefs.clearAutoStop(ctx)
                    view.findViewById<Button>(R.id.btn_start_drive).performClick()
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun ensureLocationEnabled(onReady: () -> Unit) {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(requireContext())
                .setTitle("Turn on Location")
                .setMessage("Drive Mode needs GPS. Please switch Location on.")
                .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        onReady()
    }
}
