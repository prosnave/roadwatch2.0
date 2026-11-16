package com.roadwatch.trip

/** Digest of a recorded trip for the review screen and for offline analysis. */
data class TripSummary(
    val simulated: Boolean,
    val appBuild: String?,
    val device: String?,
    val startMs: Long?,
    val durationS: Long,
    val fixes: Int,
    val distanceKm: Double,
    val maxKph: Int,
    val alerts: List<AlertRow>,
    val reports: Int,
    val skips: Map<String, Int>,
) {
    data class AlertRow(
        val id: Long,
        val timeMs: Long,
        val kind: String,
        val type: String,
        val hazardKey: String?,
        val speech: String,
        val distanceM: Int,
        val speedKph: Int,
        /** Latest verdict recorded for this alert (WRONG / OK / LATE / EARLY) or null. */
        val verdict: String?,
    )

    companion object {
        fun parse(lines: Sequence<String>): TripSummary {
            var simulated = false
            var build: String? = null
            var device: String? = null
            var start: Long? = null
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            var fixes = 0
            var distance = 0.0
            var maxKph = 0
            var reports = 0
            var prevLat = Double.NaN
            var prevLng = Double.NaN
            val alerts = ArrayList<AlertRow>()
            val verdicts = HashMap<Long, String>()
            val skips = LinkedHashMap<String, Int>()

            for (line in lines) {
                val m = TripFormat.parse(line) ?: continue
                when (m["k"]) {
                    "meta" -> {
                        simulated = m["simulated"] == "true"
                        build = m["build"]
                        device = m["device"]
                        start = m["startedAt"]?.toLongOrNull()
                    }
                    "fix" -> {
                        val t = m["t"]?.toLongOrNull() ?: continue
                        val lat = m["lat"]?.toDoubleOrNull() ?: continue
                        val lng = m["lng"]?.toDoubleOrNull() ?: continue
                        fixes++
                        first = minOf(first, t); last = maxOf(last, t)
                        if (!prevLat.isNaN()) distance += com.roadwatch.engine.Geo.distanceMeters(prevLat, prevLng, lat, lng)
                        prevLat = lat; prevLng = lng
                        maxKph = maxOf(maxKph, ((m["spd"]?.toDoubleOrNull() ?: 0.0) * 3.6).toInt())
                    }
                    "alert" -> alerts += AlertRow(
                        id = m["id"]?.toLongOrNull() ?: -1,
                        timeMs = m["t"]?.toLongOrNull() ?: 0,
                        kind = m["kind"].orEmpty(),
                        type = m["type"].orEmpty(),
                        hazardKey = m["hazard"],
                        speech = m["speech"].orEmpty(),
                        distanceM = m["dist"]?.toIntOrNull() ?: 0,
                        speedKph = m["kph"]?.toIntOrNull() ?: 0,
                        verdict = null,
                    )
                    "feedback" -> {
                        val id = m["alert"]?.toLongOrNull()
                        val v = m["v"]
                        if (id != null && v != null) verdicts[id] = v
                    }
                    "report" -> reports++
                    "trace" -> m["ev"]?.takeIf { it.startsWith("SKIP_") }?.let { skips.merge(it, 1, Int::plus) }
                }
            }
            val dur = if (first <= last) (last - first) / 1000 else 0
            return TripSummary(
                simulated, build, device, start, dur, fixes, distance / 1000.0, maxKph,
                alerts.map { it.copy(verdict = verdicts[it.id]) }, reports, skips,
            )
        }
    }
}
