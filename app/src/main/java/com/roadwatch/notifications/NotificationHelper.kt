package com.roadwatch.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.app.NotificationCompat
import com.roadwatch.app.R
import com.roadwatch.prefs.AppPrefs

object NotificationHelper {
    // Bump channel id to force new attributes on devices that already created the old channel
    const val CHANNEL_ALERTS = "alerts_v2"
    const val CHANNEL_DRIVE = "drive"
    private val alertIds = AtomicInteger(2000)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alerts = NotificationChannel(
                CHANNEL_ALERTS,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RoadWatch hazard alerts"
                lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(false)
                }
                // Request bypass DND (takes effect only if user grants policy access)
                try { setBypassDnd(true) } catch (_: Throwable) {}
                // Use notification event sound (short, distinct), not the user's alarm tone
                try {
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
                } catch (_: Throwable) {}
            }
            val drive = NotificationChannel(
                CHANNEL_DRIVE,
                "Drive Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for Drive Mode"
            }
            nm.createNotificationChannel(alerts)
            nm.createNotificationChannel(drive)
        }
    }

    fun showTestAlert(context: Context, title: String, text: String, autoDismissMs: Long = 8_000L) {
        if (AppPrefs.isMuted(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n: Notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        val id = alertIds.getAndIncrement()
        nm.notify(id, n)
        if (autoDismissMs > 0) {
            try {
                Handler(Looper.getMainLooper()).postDelayed({
                    try { nm.cancel(id) } catch (_: Exception) {}
                }, autoDismissMs)
            } catch (_: Exception) {}
        }
    }

    fun driveForegroundNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_DRIVE)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle("RoadWatch")
            .setContentText("Drive mode active")
            .setOngoing(true)
            .build()
    }
}
