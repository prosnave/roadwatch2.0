package com.roadwatch.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.roadwatch.app.MainActivity
import com.roadwatch.app.R
import com.roadwatch.core.location.DriveModeService
import com.roadwatch.data.HazardType
import com.roadwatch.drive.DriveActions
import com.roadwatch.engine.AlertEvent
import com.roadwatch.prefs.AppPrefs

object NotificationHelper {
    // Channel settings are frozen once created, so behaviour changes need new ids.
    const val CHANNEL_ALERTS = "alerts_v3"
    const val CHANNEL_ALERTS_QUIET = "alerts_quiet_v3"
    const val CHANNEL_DRIVE = "drive_v2"
    const val ID_DRIVE = 42
    private const val ID_ALERT = 3001
    private const val ID_TEST = 3002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Retire channels from earlier builds so their stale settings don't linger in system UI.
        listOf("alerts_v2", "drive").forEach { runCatching { nm.deleteNotificationChannel(it) } }

        // Speech + our own haptics carry the alert; these channels only supply the heads-up popup.
        val alerts = NotificationChannel(CHANNEL_ALERTS, "Hazard alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pop-up shown when approaching a reported hazard"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 130, 90, 130)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val quiet = NotificationChannel(CHANNEL_ALERTS_QUIET, "Hazard alerts (no vibration)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Same pop-up without vibration"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val drive = NotificationChannel(CHANNEL_DRIVE, "Drive Mode", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while RoadWatch is watching the road; quick-report buttons"
        }
        nm.createNotificationChannel(alerts)
        nm.createNotificationChannel(quiet)
        nm.createNotificationChannel(drive)
    }

    private fun immutable() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun serviceIntent(context: Context, action: String, block: Intent.() -> Unit = {}): Intent =
        Intent(context, DriveModeService::class.java).setAction(action).apply(block)

    private fun reportAction(context: Context, label: String, type: HazardType): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            context, type.ordinal + 10,
            serviceIntent(context, DriveActions.REPORT) { putExtra(DriveActions.EXTRA_HAZARD_TYPE, type.name) },
            immutable(),
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    fun driveForegroundNotification(context: Context, status: String = "Watching for hazards ahead"): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            immutable(),
        )
        val stop = PendingIntent.getService(context, 1, serviceIntent(context, DriveActions.STOP), immutable())
        return NotificationCompat.Builder(context, CHANNEL_DRIVE)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle("RoadWatch Drive Mode")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(reportAction(context, "Report bump", HazardType.SPEED_BUMP))
            .addAction(reportAction(context, "Report rumble", HazardType.RUMBLE_STRIP))
            .addAction(NotificationCompat.Action.Builder(0, "Stop", stop).build())
            .build()
    }

    fun updateDriveStatus(context: Context, status: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_DRIVE, driveForegroundNotification(context, status))
    }

    /** Heads-up pop-up used when the over-other-apps banner isn't available. */
    fun postAlert(context: Context, e: AlertEvent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = if (AppPrefs.isHapticsEnabled(context)) CHANNEL_ALERTS else CHANNEL_ALERTS_QUIET
        val wrong = PendingIntent.getService(
            context, 100 + (e.id % 1000).toInt(),
            serviceIntent(context, DriveActions.FEEDBACK) {
                putExtra(DriveActions.EXTRA_ALERT_ID, e.id)
                putExtra(DriveActions.EXTRA_VERDICT, "WRONG")
            },
            immutable(),
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(e.title)
            .setContentText(e.subtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(10_000L)
            .setAutoCancel(true)
            .addAction(0, "Wrong alert", wrong)
            .build()
        try { nm.notify(ID_ALERT, n) } catch (_: SecurityException) {}
    }

    fun cancelAlert(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_ALERT)
    }

    fun showTestAlert(context: Context, title: String, text: String, autoDismissMs: Long = 8_000L) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setTimeoutAfter(autoDismissMs)
            .setAutoCancel(true)
            .build()
        try { nm.notify(ID_TEST, n) } catch (_: SecurityException) {}
    }
}
