package com.roadwatch.data.di

import android.content.Context
import androidx.room.Room
import com.roadwatch.data.dao.HazardDao
import com.roadwatch.data.db.RoadWatchDatabase
import com.roadwatch.data.repository.HazardRepository
import com.roadwatch.data.repository.SeedLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoadWatchDatabase {
        return Room.databaseBuilder(
            context,
            RoadWatchDatabase::class.java,
            "roadwatch.db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideHazardDao(database: RoadWatchDatabase): HazardDao {
        return database.hazardDao()
    }

    @Provides
    @Singleton
    fun provideSeedLoader(
        @ApplicationContext context: Context,
        hazardDao: HazardDao
    ): SeedLoader {
        return SeedLoader(context, hazardDao)
    }

    @Provides
    @Singleton
    fun provideHazardRepository(
        hazardDao: HazardDao,
        seedLoader: SeedLoader
    ): HazardRepository {
        return HazardRepository(hazardDao, seedLoader)
    }
}
