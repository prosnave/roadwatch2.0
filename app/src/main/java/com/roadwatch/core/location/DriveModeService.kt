package com.roadwatch.core.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.roadwatch.notifications.NotificationHelper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.roadwatch.alerts.AlertManager

class DriveModeService : Service(), LocationListener {
    private var lm: LocationManager? = null
    private var alerts: AlertManager? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DriveModeService onStartCommand action=${intent?.action}")
        NotificationHelper.ensureChannels(this)
        // If started with explicit start action, elevate to foreground
        if (intent?.action == ACTION_START) {
            val n = NotificationHelper.driveForegroundNotification(this)
            startForeground(42, n)
            startLocation()
        }
        if (intent?.action == ACTION_STOP) {
            stopLocation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startLocation() {
        if (lm == null) lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (alerts == null) alerts = AlertManager(this)
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    private fun stopLocation() {
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location
        alerts?.onLocationUpdate(location)
    }

    companion object {
        var lastKnownLocation: Location? = null
            private set

        private const val TAG = "DriveModeService"
        private const val ACTION_START = "com.roadwatch.core.location.action.START"
        private const val ACTION_STOP = "com.roadwatch.core.location.action.STOP"

        fun createStartIntent(context: Context): Intent = Intent(context, DriveModeService::class.java).apply {
            action = ACTION_START
        }

        fun createStopIntent(context: Context): Intent = Intent(context, DriveModeService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
