package com.roadwatch.drive

import android.content.Context
import com.roadwatch.data.Hazard
import com.roadwatch.data.HazardType
import com.roadwatch.data.SeedRepository
import com.roadwatch.engine.AlertText
import com.roadwatch.prefs.AppPrefs
import java.time.Instant

/**
 * One-tap hazard reporting shared by the HUD buttons and the notification actions.
 *
 * Position: a driver presses the button *after* seeing the hazard, typically 1–3 s later – at 50 km/h
 * that is 14–40 m past it. We therefore place the report where the car was [AppPrefs.getReactionSeconds]
 * ago, walking back along the actual breadcrumb path.
 *
 * Direction: stamped from the smoothed course, so the hazard only alerts traffic travelling that way
 * (or both ways when the driver marks it as such).
 */
object ReportActions {
    data class Outcome(
        val result: SeedRepository.ReportResult?,
        /** Human-readable status for toast/spoken confirmation. */
        val message: String,
        val ok: Boolean,
    )

    fun report(context: Context, type: HazardType, directionalityOverride: String? = null): Outcome {
        val state = DriveSession.state
        if (!state.running || state.simulated) return Outcome(null, "Start a real drive to report a hazard", false)
        val fix = state.fix ?: return Outcome(null, "No GPS fix yet", false)
        if (System.currentTimeMillis() - fix.timeMs > 15_000 && !state.simulated) {
            return Outcome(null, "GPS signal is stale", false)
        }
        val tracker = DriveSession.engine?.courseTracker
        val course = tracker?.courseDeg
        if (fix.accuracyM > 25f) return Outcome(null, "GPS is imprecise. Wait for a better fix", false)
        if (course == null) return Outcome(null, "Travel direction not ready. Move along the road before reporting", false)
        val offsetM = (fix.speedMps * AppPrefs.getReactionSeconds(context)).toDouble().coerceIn(0.0, 60.0)
        val placed = if (offsetM >= 2.0) tracker.reportPositionBehind(offsetM) else null
        val lat = placed?.lat ?: fix.lat
        val lng = placed?.lng ?: fix.lng

        val wanted = directionalityOverride ?: AppPrefs.getReportDirectionality(context)
        val directionality = wanted
        val heading = (placed?.headingDeg ?: course).toFloat()

        val hazard = Hazard(
            type = type, lat = lat, lng = lng,
            directionality = directionality,
            reportedHeadingDeg = heading,
            userBearing = heading,
            source = "USER",
            createdAt = Instant.now(),
            schemaVersion = 2,
            headingValid = true,
            reportAccuracyM = fix.accuracyM,
        )
        val r = SeedRepository(context).report(hazard)
        val name = AlertText.capitalized(AlertText.name(type))
        DriveSession.tripLog?.custom(
            "report",
            mapOf(
                "t" to fix.timeMs, "type" to type.name, "lat" to lat, "lng" to lng,
                "dir" to directionality, "hdg" to heading, "offsetM" to offsetM.toInt(), "res" to r.result.name,
            ),
        )
        return when (r.result) {
            SeedRepository.AddResult.ADDED -> Outcome(r, "$name saved: ${if (directionality == "ONE_WAY") "this direction only" else "both directions"}", true)
            SeedRepository.AddResult.MERGED_BIDIRECTIONAL -> Outcome(r, "$name confirmed for both directions", true)
            SeedRepository.AddResult.DUPLICATE_NEARBY -> Outcome(r, "$name already known here", true)
            SeedRepository.AddResult.ERROR -> Outcome(r, "Could not save report", false)
        }
    }
}
