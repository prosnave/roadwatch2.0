package com.roadwatch.engine

import com.roadwatch.data.HazardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private val east = 90.0
    private val west = 270.0

    @Test fun oneWayHazard_alertsOnceWhenApproachingInReportedDirection() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        val run = Sim.drive(Sim.engineWith(h), east, -700.0, 100.0, speedKph = 50.0)
        assertEquals(1, run.alerts.size)
        val a = run.alerts.single()
        // (10 s reaction + 2 s audio delivery) at 50 km/h ≈ 167 m. Allow for 1 Hz sampling.
        assertTrue("distance=${a.distanceM}", a.distanceM in 150..170)
        assertTrue(a.speech, a.speech.startsWith("Speed bump in "))
    }

    @Test fun oneWayHazard_isIgnoredWhenDrivingTheOppositeWay() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        val run = Sim.drive(Sim.engineWith(h), east, 700.0, -100.0, speedKph = 50.0)
        assertEquals(0, run.alerts.size)
        assertTrue(run.trace.any { it.kind == "SKIP_WRONG_DIRECTION" })
    }

    @Test fun bidirectionalHazard_alertsInBothDirections() {
        val h = Sim.hazardAt(east, 0.0, directionality = "BIDIRECTIONAL", heading = 90f)
        val e = Sim.engineWith(h)
        assertEquals(1, Sim.drive(e, east, -700.0, 100.0, 50.0).alerts.size)
        e.reset()
        assertEquals(1, Sim.drive(e, east, 700.0, -100.0, 50.0, startTimeMs = 1_700_001_000_000L).alerts.size)
    }

    @Test fun hazardOnCrossStreet_doesNotAlertTrafficOnTheMainRoad() {
        // Bump on a north-south side street, 15 m from the east-west main road we're driving on.
        val h = Sim.hazardAt(0.0, 0.0, directionality = "BIDIRECTIONAL", heading = 1f)
        val run = Sim.drive(Sim.engineWith(h), east, -600.0, 100.0, 50.0, lateralM = 12.0)
        assertEquals(0, run.alerts.size)
    }

    @Test fun dualCarriageway_otherCarriagewayHazardDoesNotAlert() {
        // Bump reported on the southbound carriageway; we drive northbound 14 m away on the other side.
        val southbound = Sim.hazardAt(0.0, 0.0, heading = 180f)
        // Southbound traffic drives on the left in Kenya, so its carriageway is to the east... position it 14 m east.
        val (lat, lng) = Geo.project(southbound.lat, southbound.lng, 90.0, 14.0)
        val h = southbound.copy(lat = lat, lng = lng)
        val northbound = Sim.drive(Sim.engineWith(h), 0.0, -600.0, 100.0, 50.0)
        assertEquals(0, northbound.alerts.size)
        val southboundRun = Sim.drive(Sim.engineWith(h), 0.0, 600.0, -100.0, 50.0, lateralM = 0.0)
        // Southbound driver (travel bearing 180) passes within a few metres laterally of the hazard.
        assertEquals(1, southboundRun.alerts.size)
    }

    @Test fun legacyHazardWithUnknownHeading_doesNotGuessCarriageway() {
        val h = Sim.hazardAt(east, 0.0, heading = 0f)
        assertEquals(0, Sim.drive(Sim.engineWith(h), east, -700.0, 100.0, 50.0).alerts.size)
    }

    @Test fun oppositeCarriagewaysSixMetresApart_eachAlertOnlyItsOwnTraffic() {
        val eastbound = Sim.hazardAt(east, 0.0, heading = 90f, id = "eb")
        val (lat, lng) = Geo.project(eastbound.lat, eastbound.lng, 0.0, 6.0)
        val westbound = eastbound.copy(lat = lat, lng = lng, reportedHeadingDeg = 270f, id = "wb")
        val e = Sim.engineWith(eastbound, westbound)
        assertEquals(listOf("eb"), Sim.drive(e, east, -700.0, 100.0, 60.0).alerts.flatMap { a -> a.hazards.map { it.id } })
        e.reset()
        assertEquals(listOf("wb"), Sim.drive(e, east, 700.0, -100.0, 60.0, lateralM = 0.0, startTimeMs = 1_700_001_000_000L)
            .alerts.flatMap { a -> a.hazards.map { it.id } })
    }

    @Test fun highwaySpeed_leavesAtLeastFourteenSecondsBeforeSpeech() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        for (kph in listOf(80.0, 100.0)) {
            val a = Sim.drive(Sim.engineWith(h), east, -900.0, 100.0, kph).alerts.single()
            val seconds = a.distanceM / (kph / 3.6)
            assertTrue("kph=$kph seconds=$seconds", seconds >= 14.0)
        }
    }

    @Test fun closelySpacedRumbleStrips_areAnnouncedAsOneCluster() {
        val hs = (0..2).map { Sim.hazardAt(east, it * 30.0, HazardType.RUMBLE_STRIP, heading = 90f) }.toTypedArray()
        val run = Sim.drive(Sim.engineWith(*hs), east, -700.0, 200.0, 50.0)
        assertEquals(1, run.alerts.size)
        assertTrue(run.alerts.single().speech, run.alerts.single().speech.startsWith("3 rumble strips ahead"))
    }

    @Test fun slowCrawl_neverAlerts() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        val run = Sim.drive(Sim.engineWith(h), east, -100.0, 20.0, speedKph = 6.0)
        assertEquals(0, run.alerts.size)
    }

    @Test fun poorGpsAccuracy_suppressesAlerts() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        val run = Sim.drive(Sim.engineWith(h), east, -600.0, 100.0, 50.0, accuracyM = 120f)
        assertEquals(0, run.alerts.size)
    }

    @Test fun fastApproach_warnsEarlierInDistanceThanSlow() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        val fast = Sim.drive(Sim.engineWith(h), east, -900.0, 100.0, 100.0).alerts.single().distanceM
        val slow = Sim.drive(Sim.engineWith(h), east, -900.0, 100.0, 30.0).alerts.single().distanceM
        assertTrue("fast=$fast slow=$slow", fast > slow * 2)
    }

    @Test fun nightDriving_warnsEarlier() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        val day = Sim.drive(Sim.engineWith(h), east, -900.0, 100.0, 60.0).alerts.single().distanceM
        val night = Sim.drive(Sim.engineWith(h), east, -900.0, 100.0, 60.0, ctx = DriveContext(night = true)).alerts.single().distanceM
        assertTrue("day=$day night=$night", night > day + 20)
    }

    @Test fun hazardAlreadyUnderUs_isSkippedAsTooLate() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        // Start 15 m before the bump at 60 km/h (<1 s away).
        val run = Sim.drive(Sim.engineWith(h), east, -15.0, 60.0, 60.0)
        assertEquals(0, run.alerts.size)
        assertTrue(run.trace.any { it.kind == "SKIP_TOO_LATE" })
    }

    @Test fun passedHazard_doesNotRepeat_butAlertsAgainOnALaterTrip() {
        val h = Sim.hazardAt(east, 0.0, directionality = "BIDIRECTIONAL", heading = 90f)
        val e = Sim.engineWith(h)
        val outbound = Sim.drive(e, east, -700.0, 400.0, 50.0)
        assertEquals(1, outbound.alerts.size)
        // Turn around at 400 m and come back without resetting the engine.
        val back = Sim.drive(e, east, 400.0, -300.0, 50.0, startTimeMs = 1_700_000_100_000L)
        assertEquals(1, back.alerts.size)
    }

    @Test fun twoHazardsFarApart_getTwoAlerts() {
        val a = Sim.hazardAt(east, 0.0, heading = 90f)
        val b = Sim.hazardAt(east, 400.0, HazardType.POTHOLE, heading = 90f)
        val run = Sim.drive(Sim.engineWith(a, b), east, -700.0, 600.0, 50.0)
        assertEquals(2, run.alerts.size)
        assertEquals(HazardType.SPEED_BUMP, run.alerts[0].primary.type)
        assertEquals(HazardType.POTHOLE, run.alerts[1].primary.type)
    }

    @Test fun lossOfReceiverBearing_stillAlertsUsingDerivedCourse() {
        val h = Sim.hazardAt(east, 0.0, heading = 90f)
        val run = Sim.drive(Sim.engineWith(h), east, -700.0, 100.0, 50.0, withBearing = false)
        assertEquals(1, run.alerts.size)
    }

    @Test fun speedLimitZone_announcedOnApproachAndOnExit() {
        val (sLat, sLng) = Geo.project(Sim.LAT0, Sim.LNG0, east, 0.0)
        val (eLat, eLng) = Geo.project(Sim.LAT0, Sim.LNG0, east, 300.0)
        val zone = Sim.hazard(sLat, sLng, HazardType.SPEED_LIMIT_ZONE, "BIDIRECTIONAL", 0f, "zone1").copy(
            speedLimitKph = 50, zoneLengthMeters = 300,
            zoneStartLat = sLat, zoneStartLng = sLng, zoneEndLat = eLat, zoneEndLng = eLng,
        )
        val run = Sim.drive(Sim.engineWith(zone), east, -500.0, 600.0, 50.0)
        val kinds = run.alerts.map { it.kind }
        assertEquals(listOf(AlertEvent.Kind.ZONE_ENTER, AlertEvent.Kind.ZONE_EXIT), kinds)
        assertTrue(run.alerts[0].speech.contains("50"))
    }

    @Test fun zoneApproachAbandoned_warnsAgainOnALaterApproach() {
        val (sLat, sLng) = Geo.project(Sim.LAT0, Sim.LNG0, east, 0.0)
        val (eLat, eLng) = Geo.project(Sim.LAT0, Sim.LNG0, east, 300.0)
        val zone = Sim.hazard(sLat, sLng, HazardType.SPEED_LIMIT_ZONE, "BIDIRECTIONAL", 0f, "zone1").copy(
            speedLimitKph = 50, zoneLengthMeters = 300,
            zoneStartLat = sLat, zoneStartLng = sLng, zoneEndLat = eLat, zoneEndLng = eLng,
        )
        val e = Sim.engineWith(zone)
        // Approach to ~100 m, then turn away without entering (drive back west past the 600 m re-arm distance).
        val first = Sim.drive(e, east, -500.0, -100.0, 50.0)
        assertEquals(listOf(AlertEvent.Kind.ZONE_ENTER), first.alerts.map { it.kind })
        Sim.drive(e, east, -100.0, -900.0, 50.0, startTimeMs = 1_700_000_100_000L)
        val second = Sim.drive(e, east, -900.0, -100.0, 50.0, startTimeMs = 1_700_000_300_000L)
        assertEquals(listOf(AlertEvent.Kind.ZONE_ENTER), second.alerts.map { it.kind })
    }
}
