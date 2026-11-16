package com.roadwatch

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.roadwatch.app.AppForeground
import com.roadwatch.data.SeedRepository
import com.roadwatch.notifications.NotificationHelper

class RoadWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        // First run only, and only into an empty store: field data on existing installs is never touched.
        val imported = SeedRepository(this).importBundledSeedsIfFirstRun()
        Log.i(TAG, "Application started (bundled hazards imported: $imported)")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { AppForeground.started() }
            override fun onActivityStopped(activity: Activity) { AppForeground.stopped() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        private const val TAG = "RoadWatchApplication"
    }
}
