package com.roadwatch.data

import com.roadwatch.trip.TripFormat
import com.roadwatch.trip.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DataLayerTest {
    private fun h(
        lat: Double, lng: Double, heading: Float, dir: String = "ONE_WAY",
        type: HazardType = HazardType.SPEED_BUMP, id: String = "x",
    ) = Hazard(type, lat, lng, dir, heading, source = "USER", createdAt = Instant.EPOCH, id = id)

    @Test fun csv_roundTripsAndToleratesPandasStyleExports() {
        val original = UserHazard("42", h(-1.25, 36.7, 112.5f, "BIDIRECTIONAL", HazardType.RUMBLE_STRIP))
        val parsed = HazardCsv.parse(HazardCsv.formatAll(listOf(original)).lineSequence()).single()
        assertEquals(original.id, parsed.id)
        assertEquals(original.hazard.copy(id = ""), parsed.hazard)

        val pandas = """
            lat,lng,type,source,active,directionality,reportedHeadingDeg,speedLimitKph,id
            -1.25099,36.68655,SPEED_LIMIT_ZONE,USER,True,BIDIRECTIONAL,0.0,50.0,1757409863199
        """.trimIndent()
        val z = HazardCsv.parse(pandas.lineSequence()).single()
        assertEquals(HazardType.SPEED_LIMIT_ZONE, z.hazard.type)
        assertEquals(50, z.hazard.speedLimitKph)
        assertTrue(z.hazard.active)
    }

    @Test fun csv_skipsBrokenRows() {
        val text = "id,type,lat,lng\n1,SPEED_BUMP,-1.2,36.8\n2,NOT_A_TYPE,1,1\n3,POTHOLE,abc,36\n4,POTHOLE,95.0,36.0"
        assertEquals(1, HazardCsv.parse(text.lineSequence()).size)
    }

    @Test fun merge_sameDirectionNearby_isDuplicate() {
        val existing = listOf(h(-1.2800, 36.8200, 90f))
        val (lat, lng) = com.roadwatch.engine.Geo.project(-1.2800, 36.8200, 90.0, 8.0)
        assertTrue(HazardMerge.decide(existing, h(lat, lng, 95f)) is HazardMerge.Decision.Duplicate)
    }

    @Test fun merge_oppositeDirectionNearby_staysSeparateForDividedRoads() {
        // Opposite carriageways a few metres apart must never be merged into "both directions".
        val existing = listOf(h(-1.2800, 36.8200, 90f, id = "e1"))
        assertTrue(HazardMerge.decide(existing, h(-1.28002, 36.82002, 270f)) is HazardMerge.Decision.Add)
    }

    @Test fun merge_explicitBothDirectionsReport_broadensSameDirectionHazard() {
        val existing = listOf(h(-1.2800, 36.8200, 90f, id = "e1"))
        assertTrue(HazardMerge.decide(existing, h(-1.28002, 36.82002, 92f, "BIDIRECTIONAL")) is HazardMerge.Decision.UpgradeToBidirectional)
    }

    @Test fun csv_legacyRowsKeepUnknownHeadingAndNewRowsKeepTrueNorth() {
        val legacy = "id,type,lat,lng,active,directionality,reportedHeadingDeg\n1,SPEED_BUMP,-1.2,36.8,true,ONE_WAY,0.0"
        val old = HazardCsv.parse(legacy.lineSequence()).single().hazard
        assertTrue(!old.headingKnown)
        val north = UserHazard("2", h(-1.2, 36.8, 0f).copy(id = "", schemaVersion = 2, headingValid = true, reportAccuracyM = 4f))
        val parsed = HazardCsv.parse(HazardCsv.formatAll(listOf(north)).lineSequence()).single().hazard
        assertTrue(parsed.headingKnown)
        assertEquals(north.hazard, parsed)
    }

    @Test fun merge_perpendicularOrFarOrOtherType_isAdded() {
        val existing = listOf(h(-1.2800, 36.8200, 90f))
        assertTrue(HazardMerge.decide(existing, h(-1.28002, 36.82002, 1f)) is HazardMerge.Decision.Add)
        assertTrue(HazardMerge.decide(existing, h(-1.2800, 36.8210, 90f)) is HazardMerge.Decision.Add)
        assertTrue(HazardMerge.decide(existing, h(-1.2800, 36.8200, 90f, type = HazardType.POTHOLE)) is HazardMerge.Decision.Add)
    }

    @Test fun merge_adjacentStripsBeyondRadiusAreKept() {
        // The old 30 m rule would have rejected this second bump 20 m further on.
        val existing = listOf(h(-1.2800, 36.8200, 90f))
        val (lat, lng) = com.roadwatch.engine.Geo.project(-1.2800, 36.8200, 90.0, 20.0)
        assertTrue(HazardMerge.decide(existing, h(lat, lng, 90f)) is HazardMerge.Decision.Add)
    }

    @Test fun tripFormat_roundTripsEscapesAndNulls() {
        val line = TripFormat.custom("feedback", mapOf("v" to "WRONG \"quoted\" \\ path", "alert" to 7, "lat" to null))
        val m = TripFormat.parse(line)!!
        assertEquals("feedback", m["k"])
        assertEquals("WRONG \"quoted\" \\ path", m["v"])
        assertEquals("7", m["alert"])
        assertNull(m["lat"])
        assertNull(TripFormat.parse("not json"))
        // Truncated escape sequence from a half-written line must not throw.
        assertNull(TripFormat.parse("{\"k\":\"alert\",\"speech\":\"bad \\u12"))
    }

    @Test fun tripSummary_countsAlertsReportsAndLatestVerdict() {
        val lines = listOf(
            TripFormat.meta(mapOf("build" to "b1", "device" to "Tecno", "simulated" to false, "startedAt" to 1000)),
            TripFormat.custom("fix", mapOf("t" to 1000, "lat" to -1.28, "lng" to 36.82, "spd" to 10.0)),
            TripFormat.custom("fix", mapOf("t" to 61000, "lat" to -1.281, "lng" to 36.82, "spd" to 12.0)),
            TripFormat.custom("alert", mapOf("t" to 5000, "id" to 1, "kind" to "HAZARD", "type" to "SPEED_BUMP", "speech" to "Speed bump ahead.", "dist" to 120, "kph" to 50)),
            TripFormat.custom("feedback", mapOf("alert" to 1, "v" to "WRONG")),
            TripFormat.custom("feedback", mapOf("alert" to 1, "v" to "OK")),
            TripFormat.custom("report", mapOf("t" to 6000)),
            TripFormat.custom("trace", mapOf("ev" to "SKIP_WRONG_DIRECTION")),
        )
        val s = TripSummary.parse(lines.asSequence())
        assertEquals(1, s.alerts.size)
        assertEquals("OK", s.alerts.single().verdict)
        assertEquals(1, s.reports)
        assertEquals(60L, s.durationS)
        assertEquals(1, s.skips["SKIP_WRONG_DIRECTION"])
        assertNotNull(s.build())
    }

    private fun TripSummary.build() = appBuild
}
