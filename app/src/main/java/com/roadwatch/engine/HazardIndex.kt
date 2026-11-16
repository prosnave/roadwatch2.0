package com.roadwatch.engine

import com.roadwatch.data.Hazard
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Uniform-grid spatial index (~440 m cells). The engine asks for "everything within R metres of the
 * car" once per second, so this keeps per-fix work independent of the total dataset size (the old
 * code re-parsed CSV files on every fix).
 */
class HazardIndex(hazards: List<Hazard>) {
    private val cells = HashMap<Long, MutableList<Hazard>>()
    val size: Int = hazards.size

    init {
        hazards.forEach { h ->
            val (lat, lng) = anchor(h)
            cells.getOrPut(cellKey(lat, lng)) { mutableListOf() }.add(h)
        }
    }

    /** Hazards whose anchor point lies in cells overlapping the search circle (callers refine). */
    fun candidates(lat: Double, lng: Double, radiusM: Double): List<Hazard> {
        if (cells.isEmpty()) return emptyList()
        val dLat = radiusM / METERS_PER_DEG
        val dLng = radiusM / (METERS_PER_DEG * max(0.2, cos(Math.toRadians(lat))))
        val minI = floor((lat - dLat) / CELL_DEG).toLong()
        val maxI = floor((lat + dLat) / CELL_DEG).toLong()
        val minJ = floor((lng - dLng) / CELL_DEG).toLong()
        val maxJ = floor((lng + dLng) / CELL_DEG).toLong()
        val out = ArrayList<Hazard>()
        for (i in minI..maxI) for (j in minJ..maxJ) {
            cells[pack(i, j)]?.let { out.addAll(it) }
        }
        return out
    }

    private fun cellKey(lat: Double, lng: Double): Long =
        pack(floor(lat / CELL_DEG).toLong(), floor(lng / CELL_DEG).toLong())

    private fun pack(i: Long, j: Long): Long = (i shl 32) xor (j and 0xffffffffL)

    companion object {
        private const val CELL_DEG = 0.004
        private const val METERS_PER_DEG = 111_195.0

        /** Zones are indexed by their start point (their extent is handled by the zone tracker). */
        fun anchor(h: Hazard): Pair<Double, Double> =
            if (h.isZone && h.zoneStartLat != null && h.zoneStartLng != null) h.zoneStartLat to h.zoneStartLng
            else h.lat to h.lng
    }
}
