package com.roadwatch.data.repository

import com.roadwatch.data.dao.HazardDao
import com.roadwatch.data.entities.Hazard
import com.roadwatch.data.entities.HazardSource
import com.roadwatch.data.entities.HazardType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HazardRepository @Inject constructor(
    private val hazardDao: HazardDao,
    private val seedLoader: SeedLoader
) {

    fun getAllActiveHazards(): Flow<List<Hazard>> {
        return hazardDao.getAllActive()
    }

    fun getHazardsInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<Hazard>> {
        return hazardDao.getInBounds(minLat, maxLat, minLon, maxLon)
    }

    suspend fun addHazard(hazard: Hazard): Long {
        return hazardDao.upsert(hazard.copy(source = HazardSource.USER))
    }

    suspend fun updateHazard(hazard: Hazard) {
        hazardDao.update(hazard.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteHazard(id: Long) {
        hazardDao.delete(id)
    }

    suspend fun reloadSeeds(replaceExisting: Boolean = false) {
        seedLoader.loadSeedsIfEmpty(replaceExisting)
    }

    suspend fun importHazards(hazards: List<Hazard>): ImportResult {
        val existingHazards = hazardDao.getAllActive()
        // This is a simplified implementation - in a real app you'd want to handle conflicts properly
        val added = hazards.size
        val updated = 0
        val skipped = 0

        hazardDao.upsertAll(hazards.map { it.copy(source = HazardSource.IMPORT) })

        return ImportResult(added, updated, skipped)
    }

    fun exportHazards(): Flow<List<Hazard>> {
        return hazardDao.getAllActive().map { hazards ->
            hazards.filter { it.source != HazardSource.SEED }
        }
    }

    data class ImportResult(
        val added: Int,
        val updated: Int,
        val skipped: Int
    )
}
