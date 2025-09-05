package com.roadwatch.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.roadwatch.app.R
import com.roadwatch.prefs.AppPrefs

object NotificationHelper {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_DRIVE = "drive"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alerts = NotificationChannel(
                CHANNEL_ALERTS,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val drive = NotificationChannel(
                CHANNEL_DRIVE,
                "Drive Mode",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(alerts)
            nm.createNotificationChannel(drive)
        }
    }

    fun showTestAlert(context: Context, title: String, text: String) {
        if (AppPrefs.isMuted(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n: Notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(1001, n)
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
