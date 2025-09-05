package com.roadwatch

import android.app.Application
import android.util.Log
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
    }
}
