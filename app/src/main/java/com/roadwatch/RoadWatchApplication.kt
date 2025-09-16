package com.roadwatch

import android.app.Application
import android.util.Log
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
// import com.roadwatch.core.util.RemoteLogger
// import com.roadwatch.data.repository.HazardRepository
// import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// import javax.inject.Inject

// @HiltAndroidApp
class RoadWatchApplication : Application() {

    // @Inject
    // lateinit var hazardRepository: HazardRepository

    private val applicationScope = CoroutineScope(Dispatchers.IO)

    private val TAG = "RoadWatchApplication"

    override fun onCreate() {
        super.onCreate()

        // Initialize remote logging
        // RemoteLogger.initialize(this)

        Log.d(TAG, "RoadWatchApplication onCreate called")
        // RemoteLogger.logAppEvent("Application", "onCreate called")

        // Schedule periodic sync to keep remote cache warm (every 15 minutes)
        try {
            schedulePeriodicSync()
        } catch (_: Exception) {}
    }

    private fun schedulePeriodicSync() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, com.roadwatch.sync.SyncReceiver::class.java)
        val pi = PendingIntent.getBroadcast(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val interval = 15 * 60 * 1000L
        val triggerAt = System.currentTimeMillis() + interval
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, interval, pi)
    }
}
