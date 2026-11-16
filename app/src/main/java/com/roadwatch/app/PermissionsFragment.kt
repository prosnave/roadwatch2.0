package com.roadwatch.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.roadwatch.feature.home.HomeFragment

/**
 * Asks for location (required) and notifications (recommended), with a clear explanation. If location
 * is permanently denied it points to app settings instead of re-asking in a loop.
 */
class PermissionsFragment : Fragment() {
    private lateinit var status: TextView
    private lateinit var grant: Button
    private lateinit var appSettings: Button
    private var askedLocation = false

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { evaluate() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_permissions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.txt_perm_status)
        grant = view.findViewById(R.id.btn_grant)
        appSettings = view.findViewById(R.id.btn_app_settings)
        grant.setOnClickListener { request() }
        appSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
        }
    }

    override fun onResume() {
        super.onResume()
        evaluate()
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun request() {
        val need = ArrayList<String>()
        if (!hasLocation()) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            askedLocation = true
        }
        if (!hasNotifications()) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) evaluate() else launcher.launch(need.toTypedArray())
    }

    private fun evaluate() {
        if (!isAdded) return
        if (hasLocation()) {
            // Notifications are recommended, not required: voice alerts work without them.
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commitAllowingStateLoss()
            return
        }
        if (askedLocation) {
            status.text = "Location access is required for hazard alerts. Please allow it in app settings (Permissions → Location → Allow)."
            appSettings.visibility = View.VISIBLE
        }
    }
}
