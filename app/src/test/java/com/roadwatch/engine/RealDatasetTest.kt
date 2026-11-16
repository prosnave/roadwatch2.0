package com.roadwatch.engine

import com.roadwatch.data.HazardCsv
import com.roadwatch.data.HazardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the engine against the tester's real field dataset (assets/seeds.csv) rather than idealised
 * fixtures: real hazards are clustered, close to junctions and many are ONE_WAY with surveyed headings.
 */
class RealDatasetTest {
    private val hazards = HazardCsv.parse(File("src/main/assets/seeds.csv").readLines().asSequence())
        .mapIndexed { i, u -> u.hazard.copy(id = if (u.id.isBlank()) "r$i" else u.id) }
        .filter { it.type != HazardType.SPEED_LIMIT_ZONE }

    private fun approach(target: com.roadwatch.data.Hazard, courseDeg: Double, kph: Double = 50.0): List<AlertEvent> {
        val engine = AlertEngine().also { it.setHazards(hazards) }
        val speed = kph / 3.6
        val alerts = ArrayList<AlertEvent>()
        // Start 600 m before the hazard along the approach course, end 40 m after it.
        var d = -600.0
        var t = 1_700_000_000_000L
        while (d < 40.0) {
            val (lat, lng) = Geo.project(target.lat, target.lng, courseDeg, d)
            alerts += engine.onFix(Fix(t, lat, lng, speed.toFloat(), courseDeg.toFloat(), 5f)).alerts
            d += speed
            t += 1000
        }
        return alerts
    }

    @Test fun datasetLoadsAsExpected() {
        assertTrue("hazards=${hazards.size}", hazards.size > 380)
        assertTrue(hazards.count { it.directionality == "ONE_WAY" && it.headingKnown } > 100)
    }

    @Test fun everyOneWayHazard_alertsWhenApproachedInItsReportedDirection() {
        val misses = hazards.filter { it.directionality == "ONE_WAY" && it.headingKnown }.filter { h ->
            val alerts = approach(h, h.reportedHeadingDeg.toDouble())
            alerts.none { a -> a.hazards.any { it.key == h.key } }
        }
        assertTrue("missed ${misses.size}: ${misses.take(5).map { it.key }}", misses.isEmpty())
    }

    @Test fun oneWayHazards_neverAlertTrafficOnTheOppositeCarriageway() {
        // Drive straight at each ONE_WAY hazard from the opposite direction: that hazard itself must stay silent.
        val falsePositives = hazards.filter { it.directionality == "ONE_WAY" && it.headingKnown }.filter { h ->
            val opposite = Geo.normalize(h.reportedHeadingDeg + 180.0)
            // Real datasets contain a bidirectional neighbour within a few metres sometimes; ignore those.
            val alerts = approach(h, opposite)
            alerts.any { a -> a.hazards.any { it.key == h.key } }
        }
        assertEquals("false alerts: ${falsePositives.take(5).map { it.key }}", 0, falsePositives.size)
    }

    @Test fun bidirectionalHazards_alertInBothDirections() {
        val misses = hazards.filter { it.directionality == "BIDIRECTIONAL" && it.headingKnown }.filter { h ->
            listOf(h.reportedHeadingDeg.toDouble(), h.reportedHeadingDeg + 180.0).any { course ->
                approach(h, Geo.normalize(course)).none { a -> a.hazards.any { it.key == h.key } }
            }
        }
        assertTrue("missed ${misses.size}: ${misses.take(5).map { it.key }}", misses.size <= 2)
    }

    @Test fun crossStreetTraffic_isNotAlerted() {
        val falsePositives = hazards.filter { it.headingKnown }.filter { h ->
            val cross = Geo.normalize(h.reportedHeadingDeg + 90.0)
            approach(h, cross).any { a -> a.hazards.any { it.key == h.key } }
        }
        assertEquals("false alerts: ${falsePositives.take(5).map { it.key }}", 0, falsePositives.size)
    }

    /** Realistic GPS: ~4 m position jitter, ±8° bearing error, hazards themselves mis-placed by up to ~12 m sideways. */
    @Test fun withGpsNoise_recallStaysHighAndWrongDirectionStaysSilent() {
        val rnd = java.util.Random(42)
        fun noisyApproach(target: com.roadwatch.data.Hazard, courseDeg: Double): Boolean {
            val engine = AlertEngine().also { it.setHazards(hazards) }
            val speed = 50.0 / 3.6
            val lateralBias = (rnd.nextDouble() - 0.5) * 24.0
            var d = -600.0
            var t = 1_700_000_000_000L
            var hit = false
            while (d < 40.0) {
                val (lat0, lng0) = Geo.project(target.lat, target.lng, courseDeg, d)
                val (lat1, lng1) = Geo.project(lat0, lng0, courseDeg + 90.0, lateralBias + rnd.nextGaussian() * 4.0)
                val brg = courseDeg + rnd.nextGaussian() * 8.0
                val out = engine.onFix(Fix(t, lat1, lng1, speed.toFloat(), Geo.normalize(brg).toFloat(), 8f))
                if (out.alerts.any { a -> a.hazards.any { it.key == target.key } }) hit = true
                d += speed
                t += 1000
            }
            return hit
        }
        val oneWay = hazards.filter { it.directionality == "ONE_WAY" && it.headingKnown }
        val recall = oneWay.count { noisyApproach(it, it.reportedHeadingDeg.toDouble()) }.toDouble() / oneWay.size
        val falseAlerts = oneWay.count { noisyApproach(it, Geo.normalize(it.reportedHeadingDeg + 180.0)) }
        println("NOISY RESULT recall=$recall falseAlerts=$falseAlerts of ${oneWay.size}")
        assertTrue("recall=$recall", recall >= 0.93)
        assertTrue("false alerts opposite direction=$falseAlerts of ${oneWay.size}", falseAlerts <= oneWay.size * 0.02)
    }
}
