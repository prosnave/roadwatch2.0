package com.roadwatch.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
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

    // File picker for CSV imports
    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            ioScope.launch { importFromUri(uri) }
        }
    }

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
        val btnReset = view.findViewById<Button>(R.id.btn_reset_data)
        val btnShare = view.findViewById<Button>(R.id.btn_share_export)
        val switchBg = view.findViewById<android.widget.Switch>(R.id.switch_bg_alerts)
        val switchHaptics = view.findViewById<android.widget.Switch>(R.id.switch_haptics)
        val spnFocus = view.findViewById<android.widget.Spinner>(R.id.spn_audio_focus)
        val switchAutoResume = view.findViewById<android.widget.Switch>(R.id.switch_auto_resume)
        val spnCurve = view.findViewById<android.widget.Spinner>(R.id.spn_speed_curve)
        val edtZoneEnter = view.findViewById<android.widget.EditText>(R.id.edt_zone_enter)
        val edtZoneExit = view.findViewById<android.widget.EditText>(R.id.edt_zone_exit)
        val edtZoneRepeat = view.findViewById<android.widget.EditText>(R.id.edt_zone_repeat)
        val btnZoneSave = view.findViewById<Button>(R.id.btn_zone_save)
        val switchCluster = view.findViewById<android.widget.Switch>(R.id.switch_cluster_enabled)
        val edtClusterSpeed = view.findViewById<android.widget.EditText>(R.id.edt_cluster_speed)

        // Admin/Public: show button with different label
        val isAdmin = BuildConfig.IS_ADMIN
        btnAdmin.text = if (isAdmin) "Manage Locations" else "View Locations"
        btnExport.visibility = if (isAdmin) View.VISIBLE else View.GONE
        btnImport.visibility = if (isAdmin) View.VISIBLE else View.GONE
        btnShare.visibility = if (isAdmin) View.VISIBLE else View.GONE
        btnReset.visibility = if (isAdmin) View.VISIBLE else View.GONE

        fun refreshDataButtons() {
            ioScope.launch {
                val repo = SeedRepository(requireContext())
                val (seedResult, seeds) = repo.loadSeeds()
                val users = repo.loadUserHazards()
                val seedsCount = seeds.size
                val userCount = users.size
                requireActivity().runOnUiThread {
                    btnReload.visibility = if (isAdmin && seedsCount > 0) View.VISIBLE else View.GONE
                    val anyData = (seedsCount + userCount) > 0
                    btnExport.visibility = if (isAdmin && anyData) View.VISIBLE else View.GONE
                    btnShare.visibility = if (isAdmin && anyData) View.VISIBLE else View.GONE
                    // Hide Manage/View Locations when no data at all
                    btnAdmin.visibility = if (anyData) View.VISIBLE else View.GONE
                }
            }
        }

        // Initialize data-driven visibilities
        refreshDataButtons()

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
                            refreshDataButtons()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnTestAlert.setOnClickListener {
            ensureTts()
            if (ttsReady) {
                tts?.speak("RoadWatch test alert: hazard ahead", TextToSpeech.QUEUE_FLUSH, null, "rw_test")
            }
            com.roadwatch.ui.UiAlerts.info(view, "Test alert: hazard ahead")
            status.text = getString(R.string.test_alert)
        }

        btnReset.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Reset Local Data")
                .setMessage("This will delete local user hazards, clear seed overrides and votes. Seed data stays.")
                .setPositiveButton("Reset") { _, _ ->
                    ioScope.launch {
                        // Delete user hazards CSV
                        try {
                            val f = java.io.File(requireContext().filesDir, "user_hazards.csv")
                            if (f.exists()) f.delete()
                        } catch (_: Exception) {}
                        // Clear overrides and votes
                        try { requireContext().getSharedPreferences("seed_overrides", android.content.Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
                        try { requireContext().getSharedPreferences("community_votes", android.content.Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
                        requireActivity().runOnUiThread {
                            status.text = "Local data reset."
                            android.widget.Toast.makeText(requireContext(), "Local data reset", android.widget.Toast.LENGTH_SHORT).show()
                            refreshDataButtons()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Removed duplicate clear-all button; Reset Local Data covers local cleanup.

        btnExport.setOnClickListener {
            ioScope.launch {
                val repo = SeedRepository(requireContext())
                val (seedLoad, seeds) = repo.loadSeeds()
                val users = repo.loadUserHazards().map { it.hazard }
                val exportDir = File(requireContext().filesDir, "exports").apply { mkdirs() }
                val out = File(exportDir, "hazards_export.csv")
                out.bufferedWriter().use { w ->
                    w.appendLine("type,lat,lng,source,active,votes,directionality,createdAt,speedLimitKph,zoneLengthMeters,zoneStartLat,zoneStartLng,zoneEndLat,zoneEndLng")
                    // Seeds with active based on overrides
                    seeds.forEach { h ->
                        val key = com.roadwatch.data.SeedOverrides.keyOf(h)
                        val active = h.active && !com.roadwatch.data.SeedOverrides.isDisabled(requireContext(), key)
                        val votes = com.roadwatch.data.CommunityVotes.getVotes(requireContext(), key)
                        w.appendLine("${h.type},${h.lat},${h.lng},SEED,${active},${votes},${h.directionality},,${h.speedLimitKph ?: ""},${h.zoneLengthMeters ?: ""},${h.zoneStartLat ?: ""},${h.zoneStartLng ?: ""},${h.zoneEndLat ?: ""},${h.zoneEndLng ?: ""}")
                    }
                    // User hazards
                    users.forEach { h ->
                        val key = com.roadwatch.data.SeedOverrides.keyOf(h)
                        val votes = com.roadwatch.data.CommunityVotes.getVotes(requireContext(), key)
                        w.appendLine("${h.type},${h.lat},${h.lng},USER,${h.active},${votes},${h.directionality},${h.createdAt},${h.speedLimitKph ?: ""},${h.zoneLengthMeters ?: ""},${h.zoneStartLat ?: ""},${h.zoneStartLng ?: ""},${h.zoneEndLat ?: ""},${h.zoneEndLng ?: ""}")
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
                    val total = seeds.size + users.size
                    status.text = if (seedLoad.loaded) "Exported ${total} hazards (seeds + user)" else "Export completed (seed load status: failed)"
                    refreshDataButtons()
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
            // Prompt user to pick a CSV; fallback to internal export file if canceled
            try {
                importCsvLauncher.launch(arrayOf("text/csv", "text/*", "application/csv", "application/vnd.ms-excel"))
            } catch (_: Exception) {
                // Fallback: import from internal default path
                ioScope.launch {
                    val exportDir = File(requireContext().filesDir, "exports")
                    val file = File(exportDir, "hazards_export.csv")
                    if (file.exists()) importFromReader(file.bufferedReader()) else requireActivity().runOnUiThread {
                        status.text = "No export file found."
                    }
                }
            }
        }

        // ...
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
        switchHaptics.isChecked = com.roadwatch.prefs.AppPrefs.isHapticsEnabled(requireContext())
        switchAutoResume.isChecked = com.roadwatch.prefs.AppPrefs.isAutoResumeEnabled(requireContext())
        spnFocus.setSelection(if (com.roadwatch.prefs.AppPrefs.getAudioFocusMode(requireContext()) == "EXCLUSIVE") 1 else 0)
        val curve = com.roadwatch.prefs.AppPrefs.getSpeedCurve(requireContext())
        spnCurve.setSelection(when (curve) { "CONSERVATIVE" -> 0; "AGGRESSIVE" -> 2; else -> 1 })
        edtZoneEnter.setText(com.roadwatch.prefs.AppPrefs.getZoneEnter(requireContext()))
        edtZoneExit.setText(com.roadwatch.prefs.AppPrefs.getZoneExit(requireContext()))
        edtZoneRepeat.setText(com.roadwatch.prefs.AppPrefs.getZoneRepeatMs(requireContext()).toString())

        // Cluster controls
        switchCluster.isChecked = com.roadwatch.prefs.AppPrefs.isClusterEnabled(requireContext())
        edtClusterSpeed.setText(com.roadwatch.prefs.AppPrefs.getClusterSpeedThreshold(requireContext()).toString())

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
        switchCluster.setOnCheckedChangeListener { _, checked -> com.roadwatch.prefs.AppPrefs.setClusterEnabled(requireContext(), checked) }
        switchHaptics.setOnCheckedChangeListener { _, checked -> com.roadwatch.prefs.AppPrefs.setHapticsEnabled(requireContext(), checked) }
        switchAutoResume.setOnCheckedChangeListener { _, checked -> com.roadwatch.prefs.AppPrefs.setAutoResumeEnabled(requireContext(), checked) }
        edtClusterSpeed.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val kph = edtClusterSpeed.text.toString().toIntOrNull() ?: 50
                com.roadwatch.prefs.AppPrefs.setClusterSpeedThreshold(requireContext(), kph)
                edtClusterSpeed.setText(com.roadwatch.prefs.AppPrefs.getClusterSpeedThreshold(requireContext()).toString())
            }
        }
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

// Import helpers
private fun SettingsFragment.importFromReader(reader: java.io.Reader) {
    var imported = 0
    reader.useLines { lines ->
        val iter = lines.iterator()
        if (iter.hasNext()) iter.next() // header
        while (iter.hasNext()) {
            val raw = iter.next().trim()
            if (raw.isEmpty()) continue
            val cols = raw.split(',')
            try {
                val type = com.roadwatch.data.HazardType.valueOf(cols[0])
                val lat = cols[1].toDouble()
                val lng = cols[2].toDouble()
                val source = cols.getOrNull(3) ?: "USER"
                val active = cols.getOrNull(4)?.toBooleanStrictOrNull() ?: true
                val votes = cols.getOrNull(5)?.toIntOrNull() ?: 0
                val directionality = cols.getOrNull(6) ?: "UNKNOWN"
                val createdAtStr = cols.getOrNull(7) ?: ""
                val speedKph = cols.getOrNull(8)?.toIntOrNull()
                val zoneLen = cols.getOrNull(9)?.toIntOrNull()
                val zoneStartLat = cols.getOrNull(10)?.toDoubleOrNull()
                val zoneStartLng = cols.getOrNull(11)?.toDoubleOrNull()
                val zoneEndLat = cols.getOrNull(12)?.toDoubleOrNull()
                val zoneEndLng = cols.getOrNull(13)?.toDoubleOrNull()
                val createdAt = try {
                    if (createdAtStr.isNotBlank()) java.time.Instant.parse(createdAtStr) else java.time.Instant.now()
                } catch (_: Exception) { java.time.Instant.now() }
                val h = com.roadwatch.data.Hazard(
                    type = type,
                    lat = lat,
                    lng = lng,
                    active = active,
                    directionality = directionality,
                    reportedHeadingDeg = 0.0f,
                    speedLimitKph = speedKph,
                    zoneLengthMeters = zoneLen,
                    zoneStartLat = zoneStartLat,
                    zoneStartLng = zoneStartLng,
                    zoneEndLat = zoneEndLat,
                    zoneEndLng = zoneEndLng,
                    createdAt = createdAt,
                )
                val key = com.roadwatch.data.SeedOverrides.keyOf(h)
                val localVotes = com.roadwatch.data.CommunityVotes.getVotes(requireContext(), key)
                if (votes > localVotes) com.roadwatch.data.CommunityVotes.setVotes(requireContext(), key, votes)
                if (source == "USER") {
                    com.roadwatch.data.HazardStore(requireContext()).upsertByKey(key, h)
                } else {
                    com.roadwatch.data.SeedOverrides.setDisabled(requireContext(), key, !active)
                }
                imported++
            } catch (_: Exception) {}
        }
    }
    requireActivity().runOnUiThread {
        val status = view?.findViewById<TextView>(R.id.txt_status)
        status?.text = if (imported > 0) "Imported $imported rows (merged)" else "No rows imported"
    }
}

private suspend fun SettingsFragment.importFromUri(uri: Uri) {
    val cr = requireContext().contentResolver
    cr.openInputStream(uri)?.bufferedReader()?.use {
        importFromReader(it)
    } ?: requireActivity().runOnUiThread {
        val status = view?.findViewById<TextView>(R.id.txt_status)
        status?.text = "Failed to open selected file"
    }
}
