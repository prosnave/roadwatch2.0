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

    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private var pendingBgCallback: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            pendingPermissionCallback?.invoke(isGranted)
            pendingPermissionCallback = null
        }

    private val requestBgPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            pendingBgCallback?.invoke(isGranted)
            pendingBgCallback = null
        }

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
        val edtBaseUrl = view.findViewById<android.widget.EditText>(R.id.edt_base_url)
        val btnSaveBaseUrl = view.findViewById<Button>(R.id.btn_save_base_url)
        // Account (Basic) credentials and registration
        val edtAccountEmail = view.findViewById<android.widget.EditText?>(R.id.edt_account_email)
        val edtAccountPassword = view.findViewById<android.widget.EditText?>(R.id.edt_account_password)
        val btnSaveAccount = view.findViewById<Button?>(R.id.btn_save_account)
        val btnRegisterAccount = view.findViewById<Button?>(R.id.btn_register_account)
        val edtSyncRadius = view.findViewById<android.widget.EditText>(R.id.edt_sync_radius)
        val btnSaveSyncRadius = view.findViewById<Button>(R.id.btn_save_sync_radius)
        // Admin auth controls (added below the Server section if present)
        val edtAdminEmail = view.findViewById<android.widget.EditText?>(R.id.edt_admin_email)
        val edtAdminPassword = view.findViewById<android.widget.EditText?>(R.id.edt_admin_password)
        val btnAdminLogin = view.findViewById<Button?>(R.id.btn_admin_login)
        val btnAdminLogout = view.findViewById<Button?>(R.id.btn_admin_logout)
        val txtAdminStatus = view.findViewById<android.widget.TextView?>(R.id.txt_admin_status)

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

        // Initialize server settings
        edtBaseUrl.setText(com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()))
        edtAccountEmail?.setText(com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext()) ?: "")
        edtAccountPassword?.setText(com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext()) ?: "")
        edtSyncRadius.setText(com.roadwatch.prefs.AppPrefs.getSyncRadiusMeters(requireContext()).toString())
        txtAdminStatus?.text = if (com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext()).isNullOrEmpty()) "Admin: Logged out" else "Admin: Logged in"

        btnSaveBaseUrl.setOnClickListener {
            val url = edtBaseUrl.text.toString().trim()
            com.roadwatch.prefs.AppPrefs.setBaseUrl(requireContext(), url)
            status.text = if (url.isNotEmpty()) "Base URL saved" else "Base URL cleared"
        }
        btnSaveSyncRadius.setOnClickListener {
            val meters = edtSyncRadius.text.toString().toIntOrNull() ?: 3000
            com.roadwatch.prefs.AppPrefs.setSyncRadiusMeters(requireContext(), meters)
            edtSyncRadius.setText(com.roadwatch.prefs.AppPrefs.getSyncRadiusMeters(requireContext()).toString())
            status.text = "Sync radius saved"
        }
        btnSaveAccount?.setOnClickListener {
            val email = edtAccountEmail?.text?.toString()?.trim().orEmpty()
            val password = edtAccountPassword?.text?.toString()?.trim().orEmpty()
            com.roadwatch.prefs.AppPrefs.setAccountCredentials(requireContext(), email.ifBlank { null }, password.ifBlank { null })
            status.text = if (email.isNotBlank()) "Account saved" else "Account cleared"
        }
        btnRegisterAccount?.setOnClickListener {
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            if (baseUrl.isEmpty()) { status.text = "Set Base URL first"; return@setOnClickListener }
            val email = edtAccountEmail?.text?.toString()?.trim().orEmpty()
            val password = edtAccountPassword?.text?.toString()?.trim().orEmpty()
            if (email.isEmpty() || password.isEmpty()) { status.text = "Enter email and password"; return@setOnClickListener }
            ioScope.launch {
                val result = com.roadwatch.network.ApiClient.registerAccount(baseUrl, email, password)
                requireActivity().runOnUiThread {
                    status.text = if (result.isSuccess) "Account registered" else "Register failed: ${result.exceptionOrNull()?.message ?: "error"}"
                }
            }
        }

        // Settings sync (push/pull)
        val btnPushSettings = view.findViewById<Button?>(R.id.btn_push_settings)
        val btnPullSettings = view.findViewById<Button?>(R.id.btn_pull_settings)
        btnPushSettings?.setOnClickListener {
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val email = com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext()) ?: return@setOnClickListener.also { status.text = "Save account first" }
            val password = com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext()) ?: return@setOnClickListener.also { status.text = "Save account first" }
            val settings = collectSettings()
            ioScope.launch {
                val res = com.roadwatch.network.ApiClient.putAccountSettings(baseUrl, email, password, settings)
                requireActivity().runOnUiThread { status.text = if (res.isSuccess) "Settings saved to account" else "Save failed" }
            }
        }
        btnPullSettings?.setOnClickListener {
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val email = com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext()) ?: return@setOnClickListener.also { status.text = "Save account first" }
            val password = com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext()) ?: return@setOnClickListener.also { status.text = "Save account first" }
            ioScope.launch {
                val res = com.roadwatch.network.ApiClient.getAccountSettings(baseUrl, email, password)
                requireActivity().runOnUiThread {
                    if (res.isSuccess) {
                        applySettings(res.getOrNull()!!)
                        status.text = "Settings loaded from account"
                    } else status.text = "Load failed"
                }
            }
        }
        btnAdminLogin?.setOnClickListener {
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            if (baseUrl.isEmpty()) { status.text = "Set Base URL first"; return@setOnClickListener }
            val email = edtAdminEmail?.text?.toString()?.trim().orEmpty()
            val password = edtAdminPassword?.text?.toString()?.trim().orEmpty()
            if (email.isEmpty() || password.isEmpty()) { status.text = "Enter admin credentials"; return@setOnClickListener }
            ioScope.launch {
                val res = com.roadwatch.network.ApiClient.adminLogin(baseUrl, email, password)
                requireActivity().runOnUiThread {
                    if (res.isSuccess) {
                        val (access, refresh, _) = res.getOrNull()!!
                        com.roadwatch.prefs.AppPrefs.setAdminTokens(requireContext(), access, refresh)
                        txtAdminStatus?.text = "Admin: Logged in"
                        status.text = "Admin login successful"
                    } else {
                        status.text = "Admin login failed: ${res.exceptionOrNull()?.message ?: "error"}"
                    }
                }
            }
        }
        btnAdminLogout?.setOnClickListener {
            com.roadwatch.prefs.AppPrefs.setAdminTokens(requireContext(), null, null)
            txtAdminStatus?.text = "Admin: Logged out"
            status.text = "Admin logged out"
        }

        btnReload.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Reload Seed Hazards")
                .setMessage("This will reload seed hazards. User-added hazards are kept.")
                .setPositiveButton("Continue") { _, _ ->
                    ioScope.launch {
                        val repo = SeedRepository(requireContext())
                        repo.loadSeeds()
                        requireActivity().runOnUiThread {
                            status.text = "Seeds reloaded"
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
                    val header = listOf(
                        "type",
                        "lat",
                        "lng",
                        "source",
                        "active",
                        "directionality",
                        "reportedHeadingDeg",
                        "userBearing",
                        "createdAt",
                        "updatedAt",
                        "speedLimitKph",
                        "zoneLengthMeters",
                        "zoneStartLat",
                        "zoneStartLng",
                        "zoneEndLat",
                        "zoneEndLng",
                        "id",
                        "votes",
                        "votesCount"
                    ).joinToString(",")
                    w.appendLine(header)

                    fun writeHazardRow(hazard: com.roadwatch.data.Hazard, activeOverride: Boolean?, votes: Int) {
                        val row = listOf(
                            hazard.type.name,
                            hazard.lat.toString(),
                            hazard.lng.toString(),
                            hazard.source,
                            (activeOverride ?: hazard.active).toString(),
                            hazard.directionality,
                            hazard.reportedHeadingDeg.toString(),
                            hazard.userBearing?.toString().orEmpty(),
                            hazard.createdAt.toString(),
                            hazard.updatedAt.toString(),
                            hazard.speedLimitKph?.toString().orEmpty(),
                            hazard.zoneLengthMeters?.toString().orEmpty(),
                            hazard.zoneStartLat?.toString().orEmpty(),
                            hazard.zoneStartLng?.toString().orEmpty(),
                            hazard.zoneEndLat?.toString().orEmpty(),
                            hazard.zoneEndLng?.toString().orEmpty(),
                            hazard.id.orEmpty(),
                            votes.toString(),
                            hazard.votesCount.toString()
                        )
                        w.appendLine(row.joinToString(","))
                    }

                    // Seeds with active status based on overrides
                    seeds.forEach { hazard ->
                        val key = com.roadwatch.data.SeedOverrides.keyOf(hazard)
                        val active = hazard.active && !com.roadwatch.data.SeedOverrides.isDisabled(requireContext(), key)
                        val votes = com.roadwatch.data.CommunityVotes.getVotes(requireContext(), key)
                        writeHazardRow(hazard, active, votes)
                    }
                    // User hazards
                    users.forEach { hazard ->
                        val key = com.roadwatch.data.SeedOverrides.keyOf(hazard)
                        val votes = com.roadwatch.data.CommunityVotes.getVotes(requireContext(), key)
                        writeHazardRow(hazard, null, votes)
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
            pendingPermissionCallback = callback
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureBackgroundLocationPermission(callback: (Boolean) -> Unit) {
        val ctx = requireContext()
        val fgGranted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fgGranted) {
            pendingBgCallback = callback
            requestBgPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
                pendingBgCallback = callback
                requestBgPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            callback(true)
        }
    }

    // Serialize current preferences into a JSON object for account sync
    private fun collectSettings(): org.json.JSONObject {
        val ctx = requireContext()
        return org.json.JSONObject().apply {
            put("audio_enabled", com.roadwatch.prefs.AppPrefs.isAudioEnabled(ctx))
            put("visual_enabled", com.roadwatch.prefs.AppPrefs.isVisualEnabled(ctx))
            put("haptics_enabled", com.roadwatch.prefs.AppPrefs.isHapticsEnabled(ctx))
            put("background_alerts", com.roadwatch.prefs.AppPrefs.isBackgroundAlerts(ctx))
            put("audio_focus", com.roadwatch.prefs.AppPrefs.getAudioFocusMode(ctx))
            put("speed_curve", com.roadwatch.prefs.AppPrefs.getSpeedCurve(ctx))
            put("zone_enter_msg", com.roadwatch.prefs.AppPrefs.getZoneEnter(ctx))
            put("zone_exit_msg", com.roadwatch.prefs.AppPrefs.getZoneExit(ctx))
            put("zone_repeat_ms", com.roadwatch.prefs.AppPrefs.getZoneRepeatMs(ctx))
            put("cluster_enabled", com.roadwatch.prefs.AppPrefs.isClusterEnabled(ctx))
            put("cluster_speed_kph", com.roadwatch.prefs.AppPrefs.getClusterSpeedThreshold(ctx))
            put("default_mute_min", com.roadwatch.prefs.AppPrefs.getDefaultMuteMinutes(ctx))
        }
    }

    // Apply JSON settings into local preferences
    private fun applySettings(json: org.json.JSONObject) {
        val ctx = requireContext()
        fun optBool(key: String, def: Boolean) = if (json.has(key)) json.optBoolean(key, def) else def
        fun optInt(key: String, def: Int) = if (json.has(key)) json.optInt(key, def) else def
        fun optLong(key: String, def: Long) = if (json.has(key)) json.optLong(key, def) else def
        fun optString(key: String, def: String) = if (json.has(key)) json.optString(key, def) else def

        com.roadwatch.prefs.AppPrefs.setAlertChannels(ctx, optBool("audio_enabled", true), optBool("visual_enabled", true))
        com.roadwatch.prefs.AppPrefs.setHapticsEnabled(ctx, optBool("haptics_enabled", true))
        com.roadwatch.prefs.AppPrefs.setBackgroundAlerts(ctx, optBool("background_alerts", false))
        com.roadwatch.prefs.AppPrefs.setAudioFocusMode(ctx, optString("audio_focus", "DUCK"))
        com.roadwatch.prefs.AppPrefs.setSpeedCurve(ctx, optString("speed_curve", "NORMAL"))
        com.roadwatch.prefs.AppPrefs.setZoneConfig(ctx, optString("zone_enter_msg", com.roadwatch.prefs.AppPrefs.getZoneEnter(ctx)), optString("zone_exit_msg", com.roadwatch.prefs.AppPrefs.getZoneExit(ctx)), optLong("zone_repeat_ms", com.roadwatch.prefs.AppPrefs.getZoneRepeatMs(ctx)))
        com.roadwatch.prefs.AppPrefs.setClusterEnabled(ctx, optBool("cluster_enabled", true))
        com.roadwatch.prefs.AppPrefs.setClusterSpeedThreshold(ctx, optInt("cluster_speed_kph", com.roadwatch.prefs.AppPrefs.getClusterSpeedThreshold(ctx)))
        com.roadwatch.prefs.AppPrefs.setDefaultMuteMinutes(ctx, optInt("default_mute_min", com.roadwatch.prefs.AppPrefs.getDefaultMuteMinutes(ctx)))
    }
}

// Import helpers
private fun SettingsFragment.importFromReader(reader: java.io.Reader) {
    var imported = 0
    reader.useLines { lines ->
        val iter = lines.iterator()
        if (!iter.hasNext()) return@useLines
        val header = iter.next().split(',', ignoreCase = false, limit = -1).map { it.trim() }
        val indexByName = header.mapIndexed { index, name -> name.lowercase(Locale.US) to index }.toMap()
        while (iter.hasNext()) {
            val raw = iter.next().trim()
            if (raw.isEmpty()) continue
            val cols = raw.split(',', ignoreCase = false, limit = -1)
            fun valueFor(vararg keys: String): String? {
                for (key in keys) {
                    val idx = indexByName[key.lowercase(Locale.US)] ?: continue
                    if (idx in cols.indices) {
                        val value = cols[idx].trim()
                        if (value.isNotEmpty()) return value
                    }
                }
                return null
            }
            try {
                val typeName = valueFor("type") ?: continue
                val type = com.roadwatch.data.HazardType.valueOf(typeName.uppercase(Locale.US))
                val lat = valueFor("lat")?.toDoubleOrNull() ?: continue
                val lng = valueFor("lng", "lon", "long")?.toDoubleOrNull() ?: continue
                val source = valueFor("source")?.uppercase(Locale.US) ?: "USER"
                val active = valueFor("active")?.lowercase(Locale.US)?.toBooleanStrictOrNull() ?: true
                val votes = valueFor("votes")?.toIntOrNull() ?: 0
                val directionality = valueFor("directionality")?.uppercase(Locale.US) ?: "UNKNOWN"
                val reportedHeading = valueFor("reportedheadingdeg", "reported_heading_deg")?.toFloatOrNull() ?: 0f
                val userBearing = valueFor("userbearing")?.toFloatOrNull()
                val createdAt = valueFor("createdat", "created_at")?.let {
                    try { java.time.Instant.parse(it) } catch (_: Exception) { java.time.Instant.now() }
                } ?: java.time.Instant.now()
                val updatedAt = valueFor("updatedat", "updated_at")?.let {
                    try { java.time.Instant.parse(it) } catch (_: Exception) { createdAt }
                } ?: createdAt
                val speedKph = valueFor("speedlimitkph", "speed_limit_kph")?.toIntOrNull()
                val zoneLen = valueFor("zonelengthmeters", "zone_length_meters")?.toIntOrNull()
                val zoneStartLat = valueFor("zonestartlat", "zone_start_lat")?.toDoubleOrNull()
                val zoneStartLng = valueFor("zonestartlng", "zone_start_lng")?.toDoubleOrNull()
                val zoneEndLat = valueFor("zoneendlat", "zone_end_lat")?.toDoubleOrNull()
                val zoneEndLng = valueFor("zoneendlng", "zone_end_lng")?.toDoubleOrNull()
                val id = valueFor("id")
                val votesCount = valueFor("votescount", "votes_count")?.toIntOrNull() ?: votes
                val h = com.roadwatch.data.Hazard(
                    type = type,
                    lat = lat,
                    lng = lng,
                    source = source,
                    active = active,
                    directionality = directionality,
                    reportedHeadingDeg = reportedHeading,
                    userBearing = userBearing,
                    speedLimitKph = speedKph,
                    zoneLengthMeters = zoneLen,
                    zoneStartLat = zoneStartLat,
                    zoneStartLng = zoneStartLng,
                    zoneEndLat = zoneEndLat,
                    zoneEndLng = zoneEndLng,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    id = id,
                    votesCount = votesCount,
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
