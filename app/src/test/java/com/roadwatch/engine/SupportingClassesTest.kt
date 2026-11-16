package com.roadwatch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SupportingClassesTest {
    @Test fun angleDiff_wrapsAround() {
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(0.0, 180.0), 1e-9)
        assertEquals(0.0, Geo.angleDiff(720.0, 0.0), 1e-9)
    }

    @Test fun projectAndBearing_roundTrip() {
        val (lat, lng) = Geo.project(Sim.LAT0, Sim.LNG0, 135.0, 500.0)
        assertEquals(500.0, Geo.distanceMeters(Sim.LAT0, Sim.LNG0, lat, lng), 0.5)
        assertEquals(135.0, Geo.bearingDeg(Sim.LAT0, Sim.LNG0, lat, lng), 0.2)
    }

    @Test fun daylight_nairobiNoonIsDayAndMidnightIsNight() {
        val nairobiLat = -1.286
        val nairobiLng = 36.817
        val noon = ZonedDateTime.of(2026, 3, 10, 12, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant().toEpochMilli()
        val night = ZonedDateTime.of(2026, 3, 10, 22, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant().toEpochMilli()
        val earlyEvening = ZonedDateTime.of(2026, 3, 10, 17, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant().toEpochMilli()
        assertTrue(Daylight.solarElevationDeg(noon, nairobiLat, nairobiLng) > 60.0)
        assertTrue(!Daylight.isNight(noon, nairobiLat, nairobiLng))
        assertTrue(Daylight.isNight(night, nairobiLat, nairobiLng))
        // Nairobi sunset is ~18:30 in March: 17:00 is still bright.
        assertTrue(!Daylight.isNight(earlyEvening, nairobiLat, nairobiLng))
    }

    @Test fun courseTracker_usesReceiverBearingWhenTrustworthy() {
        val c = CourseTracker()
        val course = c.update(Fix(0, Sim.LAT0, Sim.LNG0, 10f, 45f, 5f))
        assertEquals(45.0, course!!, 0.01)
    }

    @Test fun courseTracker_derivesCourseFromMovementWhenBearingMissing() {
        val c = CourseTracker()
        var course: Double? = null
        for (i in 0..5) {
            val (lat, lng) = Geo.project(Sim.LAT0, Sim.LNG0, 200.0, i * 10.0)
            course = c.update(Fix(i * 1000L, lat, lng, 10f, null, 5f))
        }
        assertEquals(200.0, course!!, 2.0)
    }

    @Test fun courseTracker_ignoresBearingAtLowSpeed() {
        val c = CourseTracker()
        assertNull(c.update(Fix(0, Sim.LAT0, Sim.LNG0, 0.5f, 123f, 5f)))
    }

    @Test fun courseTracker_followsRealTurns() {
        val c = CourseTracker()
        c.update(Fix(0, Sim.LAT0, Sim.LNG0, 12f, 0f, 5f))
        val after = c.update(Fix(1000, Sim.LAT0, Sim.LNG0, 12f, 90f, 5f))
        assertEquals(90.0, after!!, 0.01)
    }

    @Test fun courseTracker_positionBehind_walksTheTrail() {
        val c = CourseTracker()
        for (i in 0..10) {
            val (lat, lng) = Geo.project(Sim.LAT0, Sim.LNG0, 90.0, i * 10.0)
            c.update(Fix(i * 1000L, lat, lng, 10f, 90f, 5f))
        }
        val (lat, lng) = c.positionBehind(25.0)!!
        // Now at 100 m east of origin; 25 m behind is 75 m east.
        assertEquals(75.0, Geo.distanceMeters(Sim.LAT0, Sim.LNG0, lat, lng), 0.5)
        assertNotNull(c.positionBehind(5.0))
        assertNull(c.positionBehind(5_000.0))
    }

    @Test fun hazardIndex_findsNeighboursAndSkipsFarAway() {
        val near = Sim.hazardAt(0.0, 300.0, heading = 0f)
        val far = Sim.hazardAt(0.0, 5_000.0, heading = 0f)
        val idx = HazardIndex(listOf(near, far))
        val found = idx.candidates(Sim.LAT0, Sim.LNG0, 600.0)
        assertTrue(found.contains(near))
        assertTrue(!found.contains(far))
    }

    @Test fun alertText_roundsDistancesForSpeech() {
        assertEquals("150 meters", AlertText.spokenDistance(147))
        assertEquals("60 meters", AlertText.spokenDistance(63))
        assertEquals("1.2 kilometers", AlertText.spokenDistance(1234))
    }
}
