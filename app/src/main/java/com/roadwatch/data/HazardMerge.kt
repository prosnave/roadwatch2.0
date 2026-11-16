package com.roadwatch.data

import com.roadwatch.engine.Geo

/**
 * Decides what to do when a driver reports a hazard near an existing one.
 *
 * Old behaviour rejected any same-type report within 30 m, which silently dropped legitimate
 * neighbouring hazards and made it impossible to record "this bump is on both directions" from the
 * other lane. Opposite-direction reports remain separate, even a few metres apart. Only an explicit
 * BOTH-directions report may broaden an existing same-direction observation.
 */
object HazardMerge {
    const val RADIUS_M = 15.0
    const val ZONE_RADIUS_M = 30.0

    sealed class Decision {
        object Add : Decision()
        data class Duplicate(val existing: Hazard) : Decision()
        data class UpgradeToBidirectional(val existing: Hazard) : Decision()
    }

    fun decide(existing: List<Hazard>, incoming: Hazard): Decision {
        val radius = if (incoming.isZone) ZONE_RADIUS_M else RADIUS_M
        val near = existing
            .filter { it.active && it.type == incoming.type }
            .map { it to Geo.distanceMeters(it.lat, it.lng, incoming.lat, incoming.lng) }
            .filter { it.second <= radius }
            .sortedBy { it.second }
        for ((c, _) in near) {
            if (incoming.isZone) return Decision.Duplicate(c)
            if (!incoming.headingKnown && !c.headingKnown && incoming.lat == c.lat && incoming.lng == c.lng &&
                incoming.directionality == c.directionality) return Decision.Duplicate(c)
            if (!incoming.headingKnown || !c.headingKnown) continue
            val diff = Geo.angleDiff(c.reportedHeadingDeg.toDouble(), incoming.reportedHeadingDeg.toDouble())
            when {
                diff <= 40.0 -> return if (incoming.directionality == "BIDIRECTIONAL" && c.directionality == "ONE_WAY")
                    Decision.UpgradeToBidirectional(c) else Decision.Duplicate(c)
                diff >= 140.0 && incoming.directionality == "BIDIRECTIONAL" && c.directionality == "BIDIRECTIONAL" ->
                    return Decision.Duplicate(c)
            }
        }
        return Decision.Add
    }
}
