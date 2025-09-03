package com.roadwatch.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.roadwatch.data.entities.Hazard
import kotlinx.coroutines.flow.Flow

@Dao
interface HazardDao {

    @Query("SELECT * FROM hazards WHERE active = 1")
    fun getAllActive(): Flow<List<Hazard>>

    @Query("SELECT * FROM hazards WHERE active = 1 AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    fun getInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<Hazard>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(hazard: Hazard): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(hazards: List<Hazard>)

    @Update
    suspend fun update(hazard: Hazard)

    @Query("DELETE FROM hazards WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM hazards WHERE source = 'SEED'")
    suspend fun deleteAllSeeds()

    @Query("SELECT COUNT(*) FROM hazards WHERE source = 'SEED'")
    suspend fun getSeedCount(): Int
}
