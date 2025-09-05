package com.roadwatch.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.roadwatch.app.BuildConfig
import com.roadwatch.app.R
import com.roadwatch.data.SeedRepository
import com.roadwatch.notifications.NotificationHelper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class SettingsFragment : Fragment() {
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val status = view.findViewById<TextView>(R.id.txt_status)
        val btnReload = view.findViewById<Button>(R.id.btn_reload)
        val btnTestAlert = view.findViewById<Button>(R.id.btn_test_alert)
        val btnExport = view.findViewById<Button>(R.id.btn_export)
        val btnImport = view.findViewById<Button>(R.id.btn_import)
        val btnAdmin = view.findViewById<Button>(R.id.btn_admin)
        val btnShare = view.findViewById<Button>(R.id.btn_share_export)
        val switchBg = view.findViewById<android.widget.Switch>(R.id.switch_bg_alerts)
        val spnFocus = view.findViewById<android.widget.Spinner>(R.id.spn_audio_focus)
        val switchPassenger = view.findViewById<android.widget.Switch>(R.id.switch_passenger)
        val spnCurve = view.findViewById<android.widget.Spinner>(R.id.spn_speed_curve)
        val edtZoneEnter = view.findViewById<android.widget.EditText>(R.id.edt_zone_enter)
        val edtZoneExit = view.findViewById<android.widget.EditText>(R.id.edt_zone_exit)
        val edtZoneRepeat = view.findViewById<android.widget.EditText>(R.id.edt_zone_repeat)
        val btnZoneSave = view.findViewById<Button>(R.id.btn_zone_save)

        // Admin/Public: show button with different label
        val isAdmin = BuildConfig.IS_ADMIN
        btnAdmin.text = if (isAdmin) "Manage Locations" else "View Locations"
        btnExport.visibility = if (isAdmin) View.VISIBLE else View.GONE
        btnImport.visibility = if (isAdmin) View.VISIBLE else View.GONE
        btnShare.visibility = if (isAdmin) View.VISIBLE else View.GONE

        btnReload.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Reload Seed Hazards")
                .setMessage("This will reload seed hazards. User-added hazards are kept.")
                .setPositiveButton("Continue") { _, _ ->
                    ioScope.launch {
                        val repo = SeedRepository(requireContext())
                        val (result, _) = repo.loadSeeds()
                        requireActivity().runOnUiThread {
                            status.text = if (result.loaded) {
                                "Seeds reloaded: ${result.count}"
                            } else {
                                "Seed reload failed"
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnTestAlert.setOnClickListener {
            ensureNotificationPermission { granted ->
                if (granted) {
                    NotificationHelper.ensureChannels(requireContext())
                    NotificationHelper.showTestAlert(requireContext(), "Test Alert", "Road hazard ahead")
                }
                ensureTts()
                if (ttsReady) {
                    tts?.speak("RoadWatch test alert: hazard ahead", TextToSpeech.QUEUE_FLUSH, null, "rw_test")
                }
                status.text = getString(R.string.test_alert)
            }
        }

        btnExport.setOnClickListener {
            ioScope.launch {
                val repo = SeedRepository(requireContext())
                val (result, hazards) = repo.loadSeeds()
                val exportDir = File(requireContext().filesDir, "exports").apply { mkdirs() }
                val out = File(exportDir, "hazards_export.csv")
                out.bufferedWriter().use { w ->
                    w.appendLine("type,lat,lng")
                    hazards.forEach { h ->
                        w.appendLine("${h.type},${h.lat},${h.lng}")
                    }
                }
                // Also copy to public Downloads/RoadWatch (Android Q+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        val resolver = requireContext().contentResolver
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "hazards_export.csv")
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/RoadWatch")
                        }
                        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { outStream ->
                                out.inputStream().use { it.copyTo(outStream) }
                            }
                        }
                    } catch (_: Exception) {}
                }
                requireActivity().runOnUiThread {
                    status.text = if (result.loaded) "Exported ${hazards.size} hazards (and copied to Downloads/RoadWatch if available)" else "Export failed"
                }
            }
        }

        btnShare.setOnClickListener {
            val exportDir = File(requireContext().filesDir, "exports").apply { mkdirs() }
            val out = File(exportDir, "hazards_export.csv")
            if (!out.exists()) {
                status.text = "No export file found. Tap Export first."
                return@setOnClickListener
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", out)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, "Share hazards export"))
        }

        btnImport.setOnClickListener {
            ioScope.launch {
                val exportDir = File(requireContext().filesDir, "exports")
                val file = File(exportDir, "hazards_export.csv")
                val count = if (file.exists()) file.readLines().drop(1).size else 0
                requireActivity().runOnUiThread {
                    status.text = if (count > 0) "Imported $count hazards" else "No export file found"
                }
            }
        }
        btnAdmin.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(com.roadwatch.app.R.id.fragment_container, com.roadwatch.feature.admin.AdminLocationsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Populate and bind settings
        spnFocus.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("DUCK", "EXCLUSIVE"))
        spnCurve.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("CONSERVATIVE", "NORMAL", "AGGRESSIVE"))
        switchBg.isChecked = com.roadwatch.prefs.AppPrefs.isBackgroundAlerts(requireContext())
        switchPassenger.isChecked = com.roadwatch.prefs.AppPrefs.isPassengerEnabled(requireContext())
        spnFocus.setSelection(if (com.roadwatch.prefs.AppPrefs.getAudioFocusMode(requireContext()) == "EXCLUSIVE") 1 else 0)
        val curve = com.roadwatch.prefs.AppPrefs.getSpeedCurve(requireContext())
        spnCurve.setSelection(when (curve) { "CONSERVATIVE" -> 0; "AGGRESSIVE" -> 2; else -> 1 })
        edtZoneEnter.setText(com.roadwatch.prefs.AppPrefs.getZoneEnter(requireContext()))
        edtZoneExit.setText(com.roadwatch.prefs.AppPrefs.getZoneExit(requireContext()))
        edtZoneRepeat.setText(com.roadwatch.prefs.AppPrefs.getZoneRepeatMs(requireContext()).toString())

        // Add audio/visual alert toggles and default mute duration controls
        val audioSwitch = view.findViewById<android.widget.Switch>(R.id.switch_audio)
        val visualSwitch = view.findViewById<android.widget.Switch>(R.id.switch_visual)
        val muteInput = view.findViewById<android.widget.EditText>(R.id.edt_mute_default)
        val muteSave = view.findViewById<Button>(R.id.btn_mute_default_save)

        audioSwitch.isChecked = com.roadwatch.prefs.AppPrefs.isAudioEnabled(requireContext())
        visualSwitch.isChecked = com.roadwatch.prefs.AppPrefs.isVisualEnabled(requireContext())
        muteInput.setText(com.roadwatch.prefs.AppPrefs.getDefaultMuteMinutes(requireContext()).toString())
        audioSwitch.setOnCheckedChangeListener { _, checked -> com.roadwatch.prefs.AppPrefs.setAlertChannels(requireContext(), checked, com.roadwatch.prefs.AppPrefs.isVisualEnabled(requireContext())) }
        visualSwitch.setOnCheckedChangeListener { _, checked -> com.roadwatch.prefs.AppPrefs.setAlertChannels(requireContext(), com.roadwatch.prefs.AppPrefs.isAudioEnabled(requireContext()), checked) }
        muteSave.setOnClickListener {
            val v = muteInput.text.toString().toIntOrNull() ?: 20
            com.roadwatch.prefs.AppPrefs.setDefaultMuteMinutes(requireContext(), v)
            status.text = "Default mute set to $v minutes"
        }

        spnFocus.onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                com.roadwatch.prefs.AppPrefs.setAudioFocusMode(requireContext(), if (position == 1) "EXCLUSIVE" else "DUCK")
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        spnCurve.onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val value = when (position) { 0 -> "CONSERVATIVE"; 2 -> "AGGRESSIVE"; else -> "NORMAL" }
                com.roadwatch.prefs.AppPrefs.setSpeedCurve(requireContext(), value)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        switchPassenger.setOnCheckedChangeListener { _, isChecked ->
            com.roadwatch.prefs.AppPrefs.setPassengerEnabled(requireContext(), isChecked)
        }
        btnZoneSave.setOnClickListener {
            val repeat = edtZoneRepeat.text.toString().toLongOrNull() ?: 60000L
            com.roadwatch.prefs.AppPrefs.setZoneConfig(requireContext(), edtZoneEnter.text.toString(), edtZoneExit.text.toString(), repeat)
            status.text = "Zone settings saved"
        }
        switchBg.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ensureBackgroundLocationPermission { granted ->
                    if (granted) {
                        com.roadwatch.prefs.AppPrefs.setBackgroundAlerts(requireContext(), true)
                        val intent = com.roadwatch.core.location.DriveModeService.createStartIntent(requireContext())
                        requireContext().startForegroundService(intent)
                        status.text = "Background alerts enabled"
                    } else {
                        switchBg.isChecked = false
                        status.text = "Background permission denied"
                    }
                }
            } else {
                com.roadwatch.prefs.AppPrefs.setBackgroundAlerts(requireContext(), false)
                val intent = com.roadwatch.core.location.DriveModeService.createStopIntent(requireContext())
                requireContext().startService(intent)
                status.text = "Background alerts disabled"
            }
        }
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(requireContext()) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    private fun ensureNotificationPermission(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback(true)
            return
        }
        val granted = requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            callback(true)
        } else {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            // Defer callback; we'll call it from onRequestPermissionsResult
            pendingPermissionCallback = callback
        }
    }

    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermissionCallback?.invoke(granted)
            pendingPermissionCallback = null
        }
        if (requestCode == REQ_BG) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingBgCallback?.invoke(granted)
            pendingBgCallback = null
        }
    }

    companion object {
        private const val REQ_NOTIF = 1002
        private const val REQ_BG = 1004
    }

    private fun ensureBackgroundLocationPermission(callback: (Boolean) -> Unit) {
        val ctx = requireContext()
        val fgGranted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fgGranted) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_BG)
            pendingBgCallback = callback
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (bgGranted) { callback(true); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Direct user to app settings for "Allow all the time"
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)))
                pendingBgCallback = callback
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
                pendingBgCallback = callback
            }
        } else {
            callback(true)
        }
    }
    private var pendingBgCallback: ((Boolean) -> Unit)? = null
}
