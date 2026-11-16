package com.roadwatch.engine

import com.roadwatch.data.Hazard
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tracks speed-limit zones (road segments start→end). Announces once when approaching an end of the
 * zone in the right direction, reminds periodically while inside, and announces the exit.
 */
class ZoneTracker(private val cfg: EngineConfig) {
    data class Signal(val kind: AlertEvent.Kind, val zone: Hazard, val distanceM: Int)

    private var zones: List<Hazard> = emptyList()
    private val announced = HashSet<String>()
    private var activeKey: String? = null
    private var lastReminderMs = 0L
    private var outsideCount = 0

    fun setZones(list: List<Hazard>) {
        zones = list
        announced.retainAll(list.map { it.key }.toSet())
        if (activeKey != null && zones.none { it.key == activeKey }) activeKey = null
    }

    val activeZone: Hazard? get() = zones.firstOrNull { it.key == activeKey }

    fun reset() {
        announced.clear()
        activeKey = null
        outsideCount = 0
    }

    /** An approach announcement is forgotten once we've clearly gone elsewhere, so a later approach warns again. */
    private fun forgetAbandonedApproaches(fix: Fix) {
        val iter = announced.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key == activeKey) continue
            val zone = zones.firstOrNull { it.key == key }
            if (zone == null) { iter.remove(); continue }
            if (!zone.hasZoneSegment) continue
            val d = minOf(
                Geo.distanceMeters(fix.lat, fix.lng, zone.zoneStartLat!!, zone.zoneStartLng!!),
                Geo.distanceMeters(fix.lat, fix.lng, zone.zoneEndLat!!, zone.zoneEndLng!!),
            )
            if (d > cfg.rearmDistanceM) iter.remove()
        }
    }

    fun update(fix: Fix, courseDeg: Double?, leadM: Double): List<Signal> {
        if (zones.isEmpty()) return emptyList()
        forgetAbandonedApproaches(fix)
        val out = ArrayList<Signal>()

        val active = activeZone
        if (active != null) {
            if (inside(active, fix, courseDeg = null)) {
                outsideCount = 0
                if (fix.timeMs - lastReminderMs >= cfg.zoneRepeatMs) {
                    lastReminderMs = fix.timeMs
                    out += Signal(AlertEvent.Kind.ZONE_REMINDER, active, 0)
                }
            } else if (++outsideCount >= 2) {
                activeKey = null
                announced.remove(active.key)
                out += Signal(AlertEvent.Kind.ZONE_EXIT, active, 0)
            }
            return out
        }

        if (courseDeg == null) return out
        for (z in zones) {
            if (inside(z, fix, courseDeg)) {
                activeKey = z.key
                lastReminderMs = fix.timeMs
                outsideCount = 0
                if (announced.add(z.key)) out += Signal(AlertEvent.Kind.ZONE_ENTER, z, 0)
                return out
            }
            if (z.key in announced || !z.hasZoneSegment) continue
            val d = approachDistance(z, fix, courseDeg)
            if (d != null && d <= leadM) {
                announced.add(z.key)
                out += Signal(AlertEvent.Kind.ZONE_ENTER, z, d.toInt())
                return out
            }
        }
        return out
    }

    /** Distance to the zone end we're heading towards, if the course matches the zone axis. */
    private fun approachDistance(z: Hazard, fix: Fix, courseDeg: Double): Double? {
        val sLat = z.zoneStartLat!!; val sLng = z.zoneStartLng!!
        val eLat = z.zoneEndLat!!; val eLng = z.zoneEndLng!!
        val axis = Geo.bearingDeg(sLat, sLng, eLat, eLng)
        val (lat, lng, heading) = when {
            Geo.angleDiff(courseDeg, axis) <= cfg.courseTolDeg -> Triple(sLat, sLng, axis)
            Geo.angleDiff(courseDeg, axis + 180.0) <= cfg.courseTolDeg -> Triple(eLat, eLng, axis + 180.0)
            else -> return null
        }
        val probe = z.copy(lat = lat, lng = lng, directionality = "ONE_WAY", reportedHeadingDeg = heading.toFloat().coerceAtLeast(0.1f))
        val r = Applicability.check(fix, courseDeg, probe, maxAlongM = cfg.maxLeadM, courseTolDeg = cfg.courseTolDeg)
        return if (r.ok) r.alongM else null
    }

    private fun inside(z: Hazard, fix: Fix, courseDeg: Double?): Boolean {
        if (!z.hasZoneSegment) {
            // Legacy point-only zones: fixed 100 m radius.
            return Geo.distanceMeters(fix.lat, fix.lng, z.lat, z.lng) < 100.0
        }
        val sLat = z.zoneStartLat!!; val sLng = z.zoneStartLng!!
        val (ex, ey) = Geo.offsetMeters(sLat, sLng, z.zoneEndLat!!, z.zoneEndLng!!)
        val len = hypot(ex, ey)
        if (len < 1.0) return false
        val (px, py) = Geo.offsetMeters(sLat, sLng, fix.lat, fix.lng)
        val t = (px * ex + py * ey) / len
        val cross = (px * ey - py * ex) / len
        val slack = 10.0
        if (t < -slack || t > len + slack) return false
        if (abs(cross) > 30.0 + fix.accuracyM / 2.0) return false
        if (courseDeg != null) {
            val axis = Geo.bearingDeg(sLat, sLng, z.zoneEndLat, z.zoneEndLng)
            if (Geo.angleDiff(courseDeg, axis) > 55.0 && Geo.angleDiff(courseDeg, axis + 180.0) > 55.0) return false
        }
        return true
    }
}
