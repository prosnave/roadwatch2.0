package com.roadwatch.engine

import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import java.time.Instant

/** Test helpers: build hazards and simulate a vehicle driving a straight line at constant speed. */
object Sim {
    // Roughly Nairobi; any point works, the maths is local.
    const val LAT0 = -1.2800
    const val LNG0 = 36.8200

    fun hazard(
        lat: Double,
        lng: Double,
        type: HazardType = HazardType.SPEED_BUMP,
        directionality: String = "ONE_WAY",
        heading: Float = 0f,
        id: String = "h_${lat}_${lng}",
    ) = Hazard(
        type = type, lat = lat, lng = lng, directionality = directionality,
        reportedHeadingDeg = heading, userBearing = heading, source = "USER",
        createdAt = Instant.EPOCH, id = id,
    )

    /** Hazard placed [metres] along [bearing] from the origin. */
    fun hazardAt(bearing: Double, metres: Double, type: HazardType = HazardType.SPEED_BUMP,
                 directionality: String = "ONE_WAY", heading: Float, id: String? = null): Hazard {
        val (lat, lng) = Geo.project(LAT0, LNG0, bearing, metres)
        return hazard(lat, lng, type, directionality, heading, id ?: "h_${bearing.toInt()}_${metres.toInt()}_${type.name}")
    }

    data class Run(val alerts: List<AlertEvent>, val trace: List<TraceEvent>, val fixes: Int)

    /**
     * Drives from [startM] to [endM] along [bearing] through the origin (positive = beyond the origin),
     * optionally offset sideways by [lateralM] (right of travel), one fix per second.
     */
    fun drive(
        engine: AlertEngine,
        bearing: Double,
        startM: Double,
        endM: Double,
        speedKph: Double,
        lateralM: Double = 0.0,
        startTimeMs: Long = 1_700_000_000_000L,
        accuracyM: Float = 5f,
        ctx: DriveContext = DriveContext(),
        withBearing: Boolean = true,
    ): Run {
        val speed = speedKph / 3.6
        val alerts = ArrayList<AlertEvent>()
        val trace = ArrayList<TraceEvent>()
        var pos = startM
        var t = startTimeMs
        var n = 0
        val dir = if (endM >= startM) 1.0 else -1.0
        val travelBearing = Geo.normalize(if (dir > 0) bearing else bearing + 180.0)
        while ((endM - pos) * dir > 0) {
            val (lat0, lng0) = Geo.project(LAT0, LNG0, bearing, pos)
            val (lat, lng) = Geo.project(lat0, lng0, travelBearing + 90.0, lateralM)
            val out = engine.onFix(
                Fix(t, lat, lng, speed.toFloat(), if (withBearing) travelBearing.toFloat() else null, accuracyM),
                ctx,
            )
            alerts += out.alerts
            trace += out.trace
            pos += dir * speed
            t += 1000
            n++
        }
        return Run(alerts, trace, n)
    }

    fun engineWith(vararg hazards: Hazard, cfg: EngineConfig = EngineConfig()): AlertEngine =
        AlertEngine(cfg).also { it.setHazards(hazards.toList()) }
}
