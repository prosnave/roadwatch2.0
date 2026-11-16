package com.roadwatch.engine

import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure, deterministic alert decision engine: feed it GPS fixes, get alerts out. No Android, no I/O,
 * no wall clock (time comes from the fixes) so a recorded trip replays identically on a laptop.
 *
 * Decision rules (see docs/02-alert-engine.md for the rationale):
 *  - Only alert while genuinely driving (speed and GPS accuracy gates).
 *  - A hazard must pass [Applicability]: right direction, ahead, on our path.
 *  - Warn a fixed number of *seconds* before arrival (distance scales with speed), earlier at night.
 *  - Announce hazards that are close together as one cluster, once.
 *  - Never repeat a hazard until we've passed it and got well clear (or come back later).
 *  - If an alert would arrive too late to be useful, skip it silently and log why.
 */
class AlertEngine(private val cfg: EngineConfig = EngineConfig()) {
    private class State {
        var alerted = false
        var passed = false
        val loggedReasons = HashSet<String>()
    }

    private var index = HazardIndex(emptyList())
    private val zoneTracker = ZoneTracker(cfg)
    private val states = HashMap<String, State>()
    private val course = CourseTracker()
    private var lastAlertMs = Long.MIN_VALUE / 2
    private var nextAlertId = 1L
    private var hazardCount = 0

    /** Smoothed course tracker; exposed so reports can be placed using the breadcrumb trail. */
    val courseTracker: CourseTracker get() = course

    val knownHazards: Int get() = hazardCount

    /** Replace the dataset. Per-hazard alert state is kept for hazards that still exist. */
    fun setHazards(all: List<Hazard>) {
        val active = all.filter { it.active }
        val (zones, points) = active.partition { it.type == HazardType.SPEED_LIMIT_ZONE }
        index = HazardIndex(points)
        zoneTracker.setZones(zones)
        hazardCount = active.size
        val keys = active.mapTo(HashSet()) { it.key }
        states.keys.retainAll(keys)
    }

    /** Forget all trip state (call when a new drive starts). */
    fun reset() {
        states.clear()
        course.reset()
        zoneTracker.reset()
        lastAlertMs = Long.MIN_VALUE / 2
    }

    fun onFix(fix: Fix, ctx: DriveContext = DriveContext()): EngineOutput {
        if (!fix.lat.isFinite() || !fix.lng.isFinite() || !fix.speedMps.isFinite() ||
            !fix.accuracyM.isFinite() || fix.accuracyM < 0 || fix.accuracyM > cfg.maxAccuracyM) {
            return EngineOutput.EMPTY
        }
        val courseDeg = course.update(fix)

        val speed = fix.speedMps.toDouble()
        val trace = ArrayList<TraceEvent>()
        val alerts = ArrayList<AlertEvent>()
        val moving = speed >= cfg.minSpeedMps

        val factor = (if (ctx.night) cfg.nightLeadFactor else 1.0) * (if (ctx.extraCaution) cfg.cautionLeadFactor else 1.0)
        val speedKph = AlertText.kph(fix.speedMps)

        // Zones: also evaluated at low speed so exits are noticed in slow traffic.
        if (moving || zoneTracker.activeZone != null) {
            val zoneLead = (speed * cfg.leadSeconds * factor).coerceIn(cfg.minLeadM, cfg.maxLeadM)
            zoneTracker.update(fix, if (moving) courseDeg else null, zoneLead).forEach { s ->
                alerts += zoneAlert(s, fix, speedKph, zoneLead.toInt())
                trace += TraceEvent("ZONE_" + s.kind.name, fix.timeMs, s.zone.key, "d=${s.distanceM}")
            }
        }

        if (courseDeg == null || !moving) return EngineOutput(alerts, emptyList(), trace, courseDeg)

        data class Cand(val h: Hazard, val key: String, val along: Double, val cross: Double, val lead: Double)

        val ok = ArrayList<Cand>()
        val upcoming = ArrayList<Upcoming>()

        for (h in index.candidates(fix.lat, fix.lng, UPCOMING_RADIUS_M)) {
            val key = h.key
            val res = Applicability.check(fix, courseDeg, h, UPCOMING_RADIUS_M, cfg.courseTolDeg)
            val st = states[key]
            val lead = leadFor(h, speed, factor)

            if (st != null) {
                val dist = hypot(res.alongM, res.crossM)
                if (st.alerted && res.reason == Applicability.Reason.BEHIND) st.passed = true
                if ((st.alerted && st.passed && dist > REARM_AFTER_PASS_M) || dist > cfg.rearmDistanceM) {
                    states.remove(key)
                    continue
                }
            }

            if (!res.ok) {
                val interesting = (res.reason == Applicability.Reason.WRONG_DIRECTION || res.reason == Applicability.Reason.OFF_PATH ||
                    res.reason == Applicability.Reason.UNKNOWN_DIRECTION) &&
                    res.alongM <= lead * 1.2 && abs(res.crossM) < 45.0
                if (interesting) {
                    val s = states.getOrPut(key) { State() }
                    if (s.loggedReasons.add(res.reason.name)) {
                        trace += TraceEvent(
                            "SKIP_" + res.reason.name, fix.timeMs, key,
                            "along=${res.alongM.toInt()} cross=${res.crossM.toInt()} course=${courseDeg.toInt()} reported=${h.reportedHeadingDeg.toInt()} ${h.directionality}",
                        )
                    }
                }
                continue
            }

            upcoming += Upcoming(h, key, res.alongM, res.crossM)
            if (st == null || !st.alerted) ok += Cand(h, key, res.alongM, res.crossM, lead)
        }
        upcoming.sortBy { it.alongM }

        var due = ok.filter { it.along <= it.lead }.sortedBy { it.along }

        // Too late to be useful: log and swallow so we don't startle the driver with a bump already under the wheels.
        val late = due.filter { it.along / speed < cfg.minTimeToHazardSec }
        late.forEach {
            states.getOrPut(it.key) { State() }.alerted = true
            trace += TraceEvent("SKIP_TOO_LATE", fix.timeMs, it.key, "along=${it.along.toInt()} speedKph=$speedKph")
        }
        due = due - late.toSet()

        if (due.isNotEmpty()) {
            if (fix.timeMs - lastAlertMs < cfg.minGapMs) {
                trace += TraceEvent("DEFER_GAP", fix.timeMs, due.first().key, "waiting for min gap")
            } else {
                val first = due.first()
                val cluster = ok.filter { it.along >= first.along && it.along <= first.along + cfg.clusterSpanM && it.along <= cfg.maxLeadM }
                    .sortedBy { it.along }
                cluster.forEach { c ->
                    val st = states.getOrPut(c.key) { State() }
                    st.alerted = true
                    st.passed = false
                }
                lastAlertMs = fix.timeMs
                val distance = first.along.toInt()
                val text = AlertText.hazards(cluster.map { it.h }, distance, speedKph)
                val severity = if (cluster.any { it.h.type == HazardType.SPEED_BUMP } || cluster.size > 1) Severity.WARNING else Severity.CAUTION
                val event = AlertEvent(
                    id = nextAlertId++, timeMs = fix.timeMs, kind = AlertEvent.Kind.HAZARD,
                    hazards = cluster.map { it.h }, distanceM = distance, speedKph = speedKph, leadM = first.lead.toInt(),
                    severity = severity, speech = text.speech, title = text.title, subtitle = text.subtitle,
                )
                alerts += event
                trace += TraceEvent(
                    "ALERT", fix.timeMs, first.key,
                    "id=${event.id} n=${cluster.size} along=$distance lead=${first.lead.toInt()} speedKph=$speedKph course=${courseDeg.toInt()} cross=${first.cross.toInt()}",
                )
            }
        }

        // Anything we just alerted about is no longer "upcoming" news but still useful on the HUD.
        return EngineOutput(alerts, upcoming, trace, courseDeg)
    }

    private fun leadFor(h: Hazard, speedMps: Double, factor: Double): Double {
        val typeFactor = when (h.type) {
            HazardType.POTHOLE -> 0.9
            else -> 1.0
        }
        // At highway speed reserve a longer reaction window even for an old "Later" preference.
        val reactionSeconds = maxOf(cfg.leadSeconds * typeFactor, if (speedMps >= 70.0 / 3.6) 14.0 else 0.0)
        return (speedMps * (reactionSeconds * factor + cfg.audioAllowanceSeconds)).coerceIn(cfg.minLeadM, cfg.maxLeadM)
    }

    private fun zoneAlert(s: ZoneTracker.Signal, fix: Fix, speedKph: Int, lead: Int): AlertEvent {
        val text = when (s.kind) {
            AlertEvent.Kind.ZONE_ENTER -> AlertText.zoneAhead(s.zone)
            AlertEvent.Kind.ZONE_EXIT -> AlertText.zoneEnd()
            else -> AlertText.zoneReminder(s.zone)
        }
        return AlertEvent(
            id = nextAlertId++, timeMs = fix.timeMs, kind = s.kind, hazards = listOf(s.zone),
            distanceM = s.distanceM, speedKph = speedKph, leadM = lead, severity = Severity.INFO,
            speech = text.speech, title = text.title, subtitle = text.subtitle,
        )
    }

    companion object {
        private const val UPCOMING_RADIUS_M = 900.0
        private const val REARM_AFTER_PASS_M = 150.0
    }
}
