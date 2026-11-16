package com.roadwatch.core.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import com.roadwatch.alerts.delivery.AlertPresenter
import com.roadwatch.app.BuildConfig
import com.roadwatch.data.HazardStore
import com.roadwatch.data.HazardType
import com.roadwatch.data.SeedRepository
import com.roadwatch.drive.DriveActions
import com.roadwatch.drive.DriveSession
import com.roadwatch.drive.ReportActions
import com.roadwatch.drive.SimulatedDrive
import com.roadwatch.engine.AlertEngine
import com.roadwatch.engine.AlertEvent
import com.roadwatch.engine.Daylight
import com.roadwatch.engine.DriveContext
import com.roadwatch.engine.EngineConfig
import com.roadwatch.engine.Fix
import com.roadwatch.notifications.NotificationHelper
import com.roadwatch.prefs.AppPrefs
import com.roadwatch.trip.TripLog

/**
 * The one place where GPS, the alert engine, the trip log and alert delivery meet. Runs as a
 * foreground service so it keeps working with the screen off and while Maps/Waze/YouTube are in
 * front. The HUD and Home screen only observe [DriveSession].
 */
class DriveModeService : Service(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private var lm: LocationManager? = null
    private var engine: AlertEngine? = null
    private var presenter: AlertPresenter? = null
    private var trip: TripLog? = null
    private var simulation: SimulatedDrive? = null
    private var running = false

    private var alertCount = 0
    private var lastNightCheck = 0L
    private var night = false
    private var loggedNight: Boolean? = null
    private var loggedCaution: Boolean? = null
    private var lastMoveAt = 0L
    private val recentAlerts = LinkedHashMap<Long, AlertEvent>()
    private var prevLocation: Location? = null

    private val hazardListener: () -> Unit = { handler.post { reloadHazards() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DriveActions.START -> startDrive(simulate = false)
            DriveActions.SIMULATE -> startDrive(simulate = true)
            DriveActions.STOP -> stopDrive()
            DriveActions.REPORT -> if (running) {
                val type = intent.getStringExtra(DriveActions.EXTRA_HAZARD_TYPE)?.let { HazardType.fromString(it) }
                if (type != null) quickReport(type)
            } else stopSelf()
            DriveActions.FEEDBACK -> if (running) {
                feedback(intent.getLongExtra(DriveActions.EXTRA_ALERT_ID, -1L), intent.getStringExtra(DriveActions.EXTRA_VERDICT) ?: "WRONG")
            } else stopSelf()
            else -> if (!running) stopSelf()
        }
        // Not sticky: Android 12+ forbids restarting a foreground service from the background, and a
        // silently-resurrected service with no visible HUD would be worse than a clean stop.
        return START_NOT_STICKY
    }

    private fun startDrive(simulate: Boolean) {
        if (running) {
            if (simulate) toast("Stop Drive Mode before running a simulation")
            return
        }
        NotificationHelper.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(
                this, NotificationHelper.ID_DRIVE,
                NotificationHelper.driveForegroundNotification(this, if (simulate) "Simulation running" else "Watching for hazards ahead"),
                type,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground (permission?)", e)
            toast("Cannot start Drive Mode: location permission needed")
            stopSelf()
            return
        }

        val cfg = engineConfig()
        val eng = AlertEngine(cfg)
        engine = eng
        presenter = AlertPresenter(this)
        alertCount = 0
        recentAlerts.clear()
        lastMoveAt = System.currentTimeMillis()
        prevLocation = null
        trip = TripLog.start(this).also { writeMeta(it, cfg, simulate) }
        running = true
        loggedNight = null
        loggedCaution = null

        if (simulate) {
            val anchor = lastKnownAnchor()
            val sim = SimulatedDrive(anchor.first, anchor.second, onFix = { f -> process(f, null) }, onFinished = { handler.postDelayed({ stopDrive() }, 4_000L) })
            eng.setHazards(sim.hazards)
            simulation = sim
            DriveSession.post { it.copy(running = true, simulated = true, alertCount = 0, lastAlert = null, upcoming = emptyList()) }
            sim.start()
        } else {
            DriveSession.engine = eng
            DriveSession.tripLog = trip
            reloadHazards()
            HazardStore.addListener(hazardListener)
            try {
                lm = getSystemService(LOCATION_SERVICE) as LocationManager
                lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.e(TAG, "No location permission", e)
                toast("Location permission is required")
                stopDrive()
                return
            }
            DriveSession.post { it.copy(running = true, simulated = false, alertCount = 0, lastAlert = null, upcoming = emptyList(), location = null, fix = null) }
        }
    }

    private fun stopDrive() {
        if (!running) { stopSelf(); return }
        running = false
        handler.removeCallbacksAndMessages(null)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        simulation?.stop()
        simulation = null
        HazardStore.removeListener(hazardListener)
        presenter?.shutdown()
        presenter = null
        trip?.close()
        trip = null
        DriveSession.engine = null
        DriveSession.tripLog = null
        engine = null
        DriveSession.post { it.copy(running = false, simulated = false, upcoming = emptyList()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopDrive()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        val prev = prevLocation
        prevLocation = location
        process(toFix(location, prev), location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun process(fix: Fix, loc: Location?) {
        val eng = engine ?: return
        if (fix.timeMs - lastNightCheck > 60_000L) {
            night = Daylight.isNight(fix.timeMs, fix.lat, fix.lng)
            lastNightCheck = fix.timeMs
        }
        val caution = AppPrefs.isExtraCaution(this)
        if (night != loggedNight || caution != loggedCaution) {
            // Recorded so an offline replay reproduces the exact context the engine saw.
            trip?.custom("ctx", mapOf("t" to fix.timeMs, "night" to night, "caution" to caution))
            loggedNight = night
            loggedCaution = caution
        }
        val out = eng.onFix(fix, DriveContext(night = night, extraCaution = caution))

        trip?.fix(fix)
        out.trace.forEach { trip?.trace(it) }
        out.alerts.forEach { a ->
            alertCount++
            trip?.alert(a)
            recentAlerts[a.id] = a
            while (recentAlerts.size > 20) recentAlerts.remove(recentAlerts.keys.first())
            presenter?.present(a)
        }
        presenter?.onProgress(out.upcoming)

        if (fix.speedMps >= 1.5f) lastMoveAt = fix.timeMs
        val limitMin = AppPrefs.getAutoStopMinutes(this)
        if (limitMin > 0 && simulation == null && fix.timeMs - lastMoveAt > limitMin * 60_000L) {
            AppPrefs.recordAutoStop(this, fix.lat, fix.lng)
            toast("No movement for $limitMin min – Drive Mode stopped")
            stopDrive()
            return
        }

        DriveSession.update { s ->
            s.copy(
                location = loc ?: s.location, fix = fix, courseDeg = out.courseDeg, upcoming = out.upcoming,
                lastAlert = out.alerts.lastOrNull() ?: s.lastAlert, alertCount = alertCount, night = night,
            )
        }
    }

    private fun toFix(l: Location, prev: Location?): Fix {
        val time = if (l.time > 0) l.time else System.currentTimeMillis()
        val speed = when {
            l.hasSpeed() -> l.speed
            prev != null && l.time > prev.time -> l.distanceTo(prev) / ((l.time - prev.time) / 1000f)
            else -> 0f
        }
        val bAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && l.hasBearingAccuracy()) l.bearingAccuracyDegrees else null
        return Fix(
            timeMs = time, lat = l.latitude, lng = l.longitude, speedMps = speed,
            bearingDeg = if (l.hasBearing()) l.bearing else null,
            accuracyM = if (l.hasAccuracy()) l.accuracy else 20f,
            bearingAccuracyDeg = bAcc,
        )
    }

    private fun quickReport(type: HazardType) {
        val outcome = ReportActions.report(this, type)
        toast(outcome.message)
        presenter?.confirm(outcome.message)
    }

    private fun feedback(alertId: Long, verdict: String) {
        val a = recentAlerts[alertId]
        val fix = DriveSession.state.fix
        trip?.custom(
            "feedback",
            mapOf("t" to (fix?.timeMs ?: System.currentTimeMillis()), "alert" to alertId, "v" to verdict, "hazard" to a?.primary?.key, "lat" to fix?.lat, "lng" to fix?.lng),
        )
        NotificationHelper.cancelAlert(this)
        toast("Thanks – noted as $verdict")
    }

    private fun reloadHazards() {
        if (simulation != null) return
        engine?.setHazards(SeedRepository(this).activeHazards())
    }

    private fun engineConfig(): EngineConfig = EngineConfig(
        leadSeconds = AppPrefs.getLeadSeconds(this),
        clusterSpanM = if (AppPrefs.isClusterEnabled(this)) 100.0 else 0.0,
        zoneRepeatMs = AppPrefs.getZoneRepeatMs(this).coerceAtLeast(30_000L),
    )

    private fun lastKnownAnchor(): Pair<Double, Double> {
        val lmLocal = getSystemService(LOCATION_SERVICE) as LocationManager
        val best = try {
            lmLocal.getProviders(true).mapNotNull { lmLocal.getLastKnownLocation(it) }.minByOrNull { it.accuracy }
        } catch (_: SecurityException) { null }
        return if (best != null) best.latitude to best.longitude else DEFAULT_ANCHOR
    }

    private fun writeMeta(t: TripLog, cfg: EngineConfig, simulated: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        t.meta(
            mapOf(
                "app" to BuildConfig.VERSION_NAME, "build" to BuildConfig.BUILD_ID, "flavor" to BuildConfig.FLAVOR,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}", "sdk" to Build.VERSION.SDK_INT,
                "lead" to cfg.leadSeconds, "cluster" to cfg.clusterSpanM, "simulated" to simulated,
                "overlayGranted" to android.provider.Settings.canDrawOverlays(this),
                "notifications" to nm.areNotificationsEnabled(),
                "audio" to AppPrefs.isAudioEnabled(this), "visual" to AppPrefs.isVisualEnabled(this),
                "haptics" to AppPrefs.isHapticsEnabled(this), "caution" to AppPrefs.isExtraCaution(this),
                "hazards" to SeedRepository(this).activeHazards().size,
                "startedAt" to System.currentTimeMillis(),
            ),
        )
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "DriveModeService"
        private val DEFAULT_ANCHOR = -1.2864 to 36.8172

        /** Last real GPS fix as a Location (null before the first fix). Kept for older call sites. */
        val lastKnownLocation: Location? get() = DriveSession.state.location

        fun createStartIntent(context: Context): Intent = Intent(context, DriveModeService::class.java).setAction(DriveActions.START)
        fun createStopIntent(context: Context): Intent = Intent(context, DriveModeService::class.java).setAction(DriveActions.STOP)
        fun createSimulateIntent(context: Context): Intent = Intent(context, DriveModeService::class.java).setAction(DriveActions.SIMULATE)
    }
}
