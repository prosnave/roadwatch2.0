package com.roadwatch.feature.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import com.roadwatch.app.BuildConfig
import com.roadwatch.app.R
import com.roadwatch.data.HazardStore
import com.roadwatch.data.HazardTransfer
import com.roadwatch.data.SeedRepository
import com.roadwatch.feature.admin.AdminLocationsFragment
import com.roadwatch.prefs.AppPrefs
import java.util.concurrent.Executors

class SettingsFragment : Fragment() {
    private val io = Executors.newSingleThreadExecutor()

    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        bindSwitch(R.id.sw_audio, AppPrefs.isAudioEnabled(ctx)) { AppPrefs.setAlertChannels(ctx, it, AppPrefs.isVisualEnabled(ctx)) }
        bindSwitch(R.id.sw_visual, AppPrefs.isVisualEnabled(ctx)) { AppPrefs.setAlertChannels(ctx, AppPrefs.isAudioEnabled(ctx), it) }
        bindSwitch(R.id.sw_overlay, AppPrefs.isOverlayEnabled(ctx)) { AppPrefs.setOverlayEnabled(ctx, it) }
        bindSwitch(R.id.sw_haptics, AppPrefs.isHapticsEnabled(ctx)) { AppPrefs.setHapticsEnabled(ctx, it) }
        bindSwitch(R.id.sw_boost, AppPrefs.isBoostVolume(ctx)) { AppPrefs.setBoostVolume(ctx, it) }
        bindSwitch(R.id.sw_cluster, AppPrefs.isClusterEnabled(ctx)) { AppPrefs.setClusterEnabled(ctx, it) }
        bindSwitch(R.id.sw_caution, AppPrefs.isExtraCaution(ctx)) { AppPrefs.setExtraCaution(ctx, it) }

        setupLeadSpinner(view)

        view.findViewById<EditText>(R.id.edt_reaction).setText(AppPrefs.getReactionSeconds(ctx).toString())
        view.findViewById<EditText>(R.id.edt_mute_minutes).setText(AppPrefs.getDefaultMuteMinutes(ctx).toString())
        view.findViewById<EditText>(R.id.edt_autostop).setText(AppPrefs.getAutoStopMinutes(ctx).toString())
        view.findViewById<Button>(R.id.btn_save_numbers).setOnClickListener {
            AppPrefs.setReactionSeconds(ctx, view.findViewById<EditText>(R.id.edt_reaction).text.toString().toFloatOrNull() ?: 1.5f)
            AppPrefs.setDefaultMuteMinutes(ctx, view.findViewById<EditText>(R.id.edt_mute_minutes).text.toString().toIntOrNull() ?: 20)
            AppPrefs.setAutoStopMinutes(ctx, view.findViewById<EditText>(R.id.edt_autostop).text.toString().toIntOrNull() ?: 30)
            status("Saved")
        }

        view.findViewById<Button>(R.id.btn_overlay_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        view.findViewById<Button>(R.id.btn_notif_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        view.findViewById<Button>(R.id.btn_battery_settings).setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { startActivity(intent) }.onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }

        val isAdmin = BuildConfig.IS_ADMIN
        view.findViewById<Button>(R.id.btn_admin).apply {
            text = if (isAdmin) "Manage locations" else "View locations"
            setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, AdminLocationsFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
        view.findViewById<Button>(R.id.btn_export_csv).setOnClickListener { exportCsv() }
        view.findViewById<Button>(R.id.btn_import_csv).setOnClickListener {
            importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel"))
        }
        view.findViewById<Button>(R.id.btn_restore_bundled).apply {
            visibility = if (isAdmin) View.VISIBLE else View.GONE
            setOnClickListener { confirmDestructive("Restore bundled data", "Replaces ALL hazards on this phone with the bundled dataset. A backup CSV is saved first.") { restoreBundled() } }
        }
        view.findViewById<Button>(R.id.btn_clear_data).apply {
            visibility = if (isAdmin) View.VISIBLE else View.GONE
            setOnClickListener { confirmDestructive("Clear all hazards", "Deletes ALL hazards on this phone. A backup CSV is saved first.") { clearAll() } }
        }

        view.findViewById<TextView>(R.id.txt_about).text =
            "RoadWatch ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · build ${BuildConfig.BUILD_ID} · ${BuildConfig.FLAVOR}"
        refreshStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        requireView().findViewById<SwitchCompat>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun setupLeadSpinner(view: View) {
        val ctx = requireContext()
        val spn = view.findViewById<Spinner>(R.id.spn_lead)
        val hint = view.findViewById<TextView>(R.id.txt_lead_hint)
        val labels = listOf("Later (about 7 s before)", "Normal (about 10 s before)", "Earlier (about 14 s before)")
        val values = listOf("CONSERVATIVE", "NORMAL", "AGGRESSIVE")
        spn.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
        spn.setSelection(values.indexOf(AppPrefs.getSpeedCurve(ctx)).coerceAtLeast(1))
        hint.text = "Distance scales with your speed: faster driving is warned about from further away."
        spn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                AppPrefs.setSpeedCurve(ctx, values[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun refreshStatuses() {
        val v = view ?: return
        val ctx = requireContext()
        val overlay = Settings.canDrawOverlays(ctx)
        v.findViewById<TextView>(R.id.txt_overlay_status).text =
            if (overlay) "✔ Alerts can show over other apps" else "✖ Not allowed yet: alerts will use a pop-up notification instead"
        v.findViewById<Button>(R.id.btn_overlay_settings).visibility = if (overlay) View.GONE else View.VISIBLE
        val notif = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        v.findViewById<TextView>(R.id.txt_notif_status).text =
            if (notif) "✔ Notifications on" else "✖ Notifications are off: pop-ups can't appear (voice still works)"
        v.findViewById<Button>(R.id.btn_notif_settings).visibility = if (notif) View.GONE else View.VISIBLE
        val count = HazardStore(ctx).count()
        v.findViewById<TextView>(R.id.txt_data_summary).text = "$count hazards on this phone"
    }

    private fun status(msg: String) {
        view?.findViewById<TextView>(R.id.txt_status)?.text = msg
    }

    private fun exportCsv() {
        val ctx = requireContext()
        io.execute {
            val file = HazardTransfer.exportCsv(ctx)
            activity?.runOnUiThread {
                status("Exported ${HazardStore(ctx).count()} hazards")
                startActivity(HazardTransfer.share(ctx, file, "text/csv", "Share hazards"))
            }
        }
    }

    private fun importFrom(uri: Uri) {
        val ctx = requireContext()
        io.execute {
            val summary = try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text == null) "Could not open the selected file" else HazardTransfer.importCsv(ctx, text).toString()
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
            activity?.runOnUiThread {
                status(summary)
                AlertDialog.Builder(ctx).setTitle("Import").setMessage(summary).setPositiveButton("OK", null).show()
                refreshStatuses()
            }
        }
    }

    private fun confirmDestructive(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(requireContext()).setTitle(title).setMessage(message)
            .setPositiveButton("Continue") { _, _ -> action() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun restoreBundled() {
        val ctx = requireContext()
        io.execute {
            HazardTransfer.backup(ctx, "before_restore")
            val n = SeedRepository(ctx).resetToBundledSeeds()
            activity?.runOnUiThread { status(if (n >= 0) "Restored $n bundled hazards" else "Restore failed"); refreshStatuses() }
        }
    }

    private fun clearAll() {
        val ctx = requireContext()
        io.execute {
            HazardTransfer.backup(ctx, "before_clear")
            val ok = HazardStore(ctx).replaceAll(emptyList())
            ctx.getSharedPreferences("seed_overrides", Context.MODE_PRIVATE).edit().clear().apply()
            ctx.getSharedPreferences("community_votes", Context.MODE_PRIVATE).edit().clear().apply()
            activity?.runOnUiThread {
                status(if (ok) "All hazards cleared (backup saved in exports)" else "Clear failed")
                if (!ok) Toast.makeText(ctx, "Clear failed", Toast.LENGTH_SHORT).show()
                refreshStatuses()
            }
        }
    }
}
