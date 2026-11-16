package com.roadwatch.trip

import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardCsv
import com.roadwatch.engine.AlertEngine
import com.roadwatch.engine.AlertEvent
import com.roadwatch.engine.DriveContext
import com.roadwatch.engine.EngineConfig
import com.roadwatch.engine.Fix
import com.roadwatch.engine.Geo
import com.roadwatch.engine.Sim
import com.roadwatch.data.HazardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline replay of recorded trips through the real engine.
 *
 * Usage (from android/), with a trip file and the hazards.csv from the tester's zip:
 *
 *   RW_TRIP=/path/trips/trip_20261112_081500.jsonl RW_HAZARDS=/path/hazards.csv \
 *     ./gradlew :app:testPublicDebugUnitTest --tests '*TripReplayTest.replayRecordedTrip' -i
 *
 * Prints what the phone alerted, what today's engine code would alert, and the tester's verdicts, so
 * a complaint ("wrong alert at 08:21") can be reproduced and a parameter change validated before shipping.
 */
object TripReplay {
    data class Result(val alerts: List<AlertEvent>, val fixes: Int, val config: EngineConfig, val night: Boolean)

    fun configFrom(meta: Map<String, String?>?): EngineConfig {
        val lead = meta?.get("lead")?.toDoubleOrNull() ?: EngineConfig().leadSeconds
        val cluster = meta?.get("cluster")?.toDoubleOrNull() ?: EngineConfig().clusterSpanM
        return EngineConfig(leadSeconds = lead, clusterSpanM = cluster)
    }

    fun replay(lines: List<String>, hazards: List<Hazard>, cfgOverride: EngineConfig? = null): Result {
        val parsed = lines.mapNotNull { TripFormat.parse(it) }
        val meta = parsed.firstOrNull { it["k"] == "meta" }
        val cfg = cfgOverride ?: configFrom(meta)
        val engine = AlertEngine(cfg).also { it.setHazards(hazards) }
        val alerts = ArrayList<AlertEvent>()
        var fixes = 0
        var night = false
        var caution = false
        for (m in parsed) {
            if (m["k"] == "ctx") {
                night = m["night"] == "true"
                caution = m["caution"] == "true"
                continue
            }
            if (m["k"] != "fix") continue
            val t = m["t"]?.toLongOrNull() ?: continue
            val lat = m["lat"]?.toDoubleOrNull() ?: continue
            val lng = m["lng"]?.toDoubleOrNull() ?: continue
            val fix = Fix(
                t, lat, lng, m["spd"]?.toFloatOrNull() ?: 0f, m["brg"]?.toFloatOrNull(),
                m["acc"]?.toFloatOrNull() ?: 10f, m["bacc"]?.toFloatOrNull(),
            )
            alerts += engine.onFix(fix, DriveContext(night = night, extraCaution = caution)).alerts
            fixes++
        }
        return Result(alerts, fixes, cfg, night)
    }
}

class TripReplayTest {
    private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    @Test fun simulatedTrip_replaysToIdenticalAlerts() {
        val bump = Sim.hazardAt(90.0, 0.0, heading = 90f)
        val pothole = Sim.hazardAt(90.0, 350.0, HazardType.POTHOLE, heading = 90f)
        val hazards = listOf(bump, pothole)

        // "Drive" and record exactly what the service would write.
        val engine = AlertEngine().also { it.setHazards(hazards) }
        val log = ArrayList<String>()
        log += TripFormat.meta(mapOf("lead" to 10.0, "cluster" to 100.0))
        // Night at this timestamp in Nairobi; the service logs context changes as a "ctx" line.
        log += TripFormat.custom("ctx", mapOf("t" to 1_700_000_000_000L, "night" to true, "caution" to false))
        val live = ArrayList<AlertEvent>()
        val speed = 50.0 / 3.6
        var pos = -600.0
        var t = 1_700_000_000_000L
        while (pos < 500.0) {
            val (lat, lng) = Geo.project(Sim.LAT0, Sim.LNG0, 90.0, pos)
            val fix = Fix(t, lat, lng, speed.toFloat(), 90f, 5f)
            log += TripFormat.fix(fix)
            val out = engine.onFix(fix, DriveContext(night = true))
            out.alerts.forEach { log += TripFormat.alert(it); live += it }
            pos += speed; t += 1000
        }

        val replayed = TripReplay.replay(log, hazards)
        assertEquals(2, live.size)
        assertEquals(live.map { it.timeMs to it.speech }, replayed.alerts.map { it.timeMs to it.speech })
        // And the recorded alert lines agree with the replay.
        val recorded = TripSummary.parse(log.asSequence())
        assertEquals(replayed.alerts.map { it.id }, recorded.alerts.map { it.id })
    }

    @Test fun replayRecordedTrip() {
        val tripPath = System.getenv("RW_TRIP")
        val hazardPath = System.getenv("RW_HAZARDS")
        assumeTrue("set RW_TRIP and RW_HAZARDS to replay a real trip", tripPath != null && hazardPath != null)
        val lines = File(tripPath!!).readLines()
        val hazards = HazardCsv.parse(File(hazardPath!!).readLines().asSequence()).map { it.hazard.copy(id = it.id) }

        val summary = TripSummary.parse(lines.asSequence())
        val result = TripReplay.replay(lines, hazards)
        println("TRIP ${File(tripPath).name}: ${summary.fixes} fixes, ${"%.1f".format(summary.distanceKm)} km, top ${summary.maxKph} km/h, build=${summary.appBuild} device=${summary.device}")
        println("config: lead=${result.config.leadSeconds}s cluster=${result.config.clusterSpanM}m")
        println("--- alerts on the phone (${summary.alerts.size}) ---")
        summary.alerts.forEach { println("${clock(it.timeMs)}  ${it.speech}  [${it.speedKph} km/h, ${it.distanceM} m]  verdict=${it.verdict ?: "-"}") }
        println("--- alerts with CURRENT code (${result.alerts.size}) ---")
        result.alerts.forEach { println("${clock(it.timeMs)}  ${it.speech}  [${it.speedKph} km/h, ${it.distanceM} m]") }
        assertTrue(result.fixes > 0)
    }
}
