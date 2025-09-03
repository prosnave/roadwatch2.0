package com.roadwatch

import android.app.Application
import android.util.Log
import com.roadwatch.core.util.RemoteLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RoadWatchApplication : Application() {

    private val TAG = "RoadWatchApplication"

    override fun onCreate() {
        super.onCreate()

        // Initialize remote logging
        RemoteLogger.initialize(this)

        Log.d(TAG, "RoadWatchApplication onCreate called")
        RemoteLogger.logAppEvent("Application", "onCreate called")
    }
}
