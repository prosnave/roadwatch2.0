package com.roadwatch.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.roadwatch.R
import com.roadwatch.app.MainActivity
import com.roadwatch.domain.usecases.ProximityAlertEngine
import com.roadwatch.core.tts.TextToSpeechManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

interface ProximityAlertEngineInterface {
    val activeAlerts: StateFlow<List<ProximityAlertEngine.AlertData>>
    fun processLocationUpdate(locationData: LocationData)
    fun clearAlert(hazardId: Long)
}

interface TextToSpeechManagerInterface {
    val alertDistance: StateFlow<Float>
    suspend fun speakAlert(message: String, hazardId: Long)
    suspend fun setAlertDistance(distance: Float)
    fun shutdown()
}

@AndroidEntryPoint
class DriveModeService : Service() {

    @Inject
    lateinit var proximityAlertEngine: ProximityAlertEngine

    @Inject
    lateinit var textToSpeechManager: TextToSpeechManager

    @Inject
    lateinit var locationClient: FusedLocationProviderClient

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                val locationData = LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedKmh = location.speed * 3.6f, // m/s to km/h
                    bearing = location.bearing,
                    accuracy = location.accuracy,
                    timestamp = location.time
                )

                serviceScope.launch {
                    _currentLocation.value = locationData
                    proximityAlertEngine.processLocationUpdate(locationData)
                }
            }
        }
    }

    private val _currentLocation = MutableStateFlow<LocationData?>(null)
    val currentLocation: StateFlow<LocationData?> = _currentLocation.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // locationClient is provided by Hilt and injected
        // Dependencies injected by Hilt (ProximityAlertEngine, TextToSpeechManager)

        // Observe active alerts to update notification and UI
        serviceScope.launch {
            proximityAlertEngine.activeAlerts.collect { alerts ->
                updateNotificationWithAlerts(alerts)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DRIVE_MODE -> startDriveMode()
            ACTION_STOP_DRIVE_MODE -> stopDriveMode()
        }
        return START_STICKY
    }

    private fun startDriveMode() {
        _isActive.value = true

        // Persist state so UI and other components can observe service status
        try {
            val prefs = applicationContext.getSharedPreferences("roadwatch_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("drive_mode_active", true).apply()
        } catch (e: Exception) {
            // ignore persistence errors
        }

        val notification = createNotification()
        val notificationId = 1

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(notificationId, notification)
        }

        startLocationUpdates()
    }

    private fun stopDriveMode() {
        _isActive.value = false

        // Persist state change
        try {
            val prefs = applicationContext.getSharedPreferences("roadwatch_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("drive_mode_active", false).apply()
        } catch (e: Exception) {
            // ignore persistence errors
        }

        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        try {
            locationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
        } catch (e: SecurityException) {
            // Handle permission denied
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        locationClient.removeLocationUpdates(locationCallback)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DriveModeService::class.java).apply {
            action = ACTION_STOP_DRIVE_MODE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoadWatch Drive Mode")
            .setContentText("Monitoring hazards ahead")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drive Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for RoadWatch drive mode"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotificationWithAlerts(alerts: List<com.roadwatch.domain.usecases.ProximityAlertEngine.AlertData>) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val title = if (alerts.isNotEmpty()) {
            "${alerts.size} hazard(s) ahead"
        } else {
            "Monitoring hazards ahead"
        }
        val content = alerts.firstOrNull()?.message ?: "Monitoring hazards ahead"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // Update the existing foreground notification
        notificationManager?.notify(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopLocationUpdates()
        try {
            textToSpeechManager.shutdown()
        } catch (_: Exception) {
            // Ignore shutdown errors
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_DRIVE_MODE = "com.roadwatch.START_DRIVE_MODE"
        const val ACTION_STOP_DRIVE_MODE = "com.roadwatch.STOP_DRIVE_MODE"
        const val CHANNEL_ID = "drive_mode_channel"

        fun createStartIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(context, DriveModeService::class.java).apply {
                action = ACTION_START_DRIVE_MODE
            }
        }

        fun createStopIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(context, DriveModeService::class.java).apply {
                action = ACTION_STOP_DRIVE_MODE
            }
        }
    }
}

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val bearing: Float,
    val accuracy: Float,
    val timestamp: Long
)
