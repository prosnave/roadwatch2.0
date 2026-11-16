package com.roadwatch.drive

import android.os.Handler
import android.os.Looper
import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import com.roadwatch.engine.Fix
import com.roadwatch.engine.Geo
import java.time.Instant

/**
 * Parked-at-home test of the whole alert pipeline (speech, ducking, banner over other apps,
 * notification, vibration) without driving. Feeds a synthetic eastbound drive through the real
 * engine and presenter: a speed bump about 20 s in, then a pothole. It uses its own throw-away
 * hazards, so the real dataset is untouched.
 */
class SimulatedDrive(
    anchorLat: Double,
    anchorLng: Double,
    private val onFix: (Fix) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val heading = 90.0
    private val speedMps = 50.0 / 3.6
    private val startLat = anchorLat
    private val startLng = anchorLng
    private var travelled = 0.0
    private var running = false

    val hazards: List<Hazard> = run {
        fun at(m: Double, type: HazardType, id: String): Hazard {
            val (lat, lng) = Geo.project(startLat, startLng, heading, m)
            return Hazard(
                type = type, lat = lat, lng = lng, directionality = "ONE_WAY",
                reportedHeadingDeg = heading.toFloat(), source = "SIM", createdAt = Instant.EPOCH, id = id,
            )
        }
        listOf(at(420.0, HazardType.SPEED_BUMP, "sim-bump"), at(700.0, HazardType.POTHOLE, "sim-pothole"))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val (lat, lng) = Geo.project(startLat, startLng, heading, travelled)
            onFix(Fix(System.currentTimeMillis(), lat, lng, speedMps.toFloat(), heading.toFloat(), 4f))
            travelled += speedMps
            if (travelled > 780.0) {
                running = false
                onFinished()
            } else {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }
}
