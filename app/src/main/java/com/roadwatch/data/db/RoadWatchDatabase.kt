package com.roadwatch.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.roadwatch.data.dao.HazardDao
import com.roadwatch.data.entities.Hazard

@Database(
    entities = [Hazard::class],
    version = 1,
    exportSchema = false
)
abstract class RoadWatchDatabase : RoomDatabase() {
    abstract fun hazardDao(): HazardDao
}
