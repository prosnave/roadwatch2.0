package com.roadwatch.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.roadwatch.core.tts.TextToSpeechManager
import com.roadwatch.core.util.ImportExportManager
import com.roadwatch.data.repository.HazardRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            corruptionHandler = null,
            migrations = emptyList(),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(context.filesDir, "roadwatch_preferences.preferences_pb") }
        )
    }

    @Provides
    @Singleton
    fun provideTextToSpeechManager(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>
    ): TextToSpeechManager {
        return TextToSpeechManager(context, dataStore)
    }

    @Provides
    @Singleton
    fun provideImportExportManager(
        @ApplicationContext context: Context,
        hazardRepository: HazardRepository
    ): ImportExportManager {
        return ImportExportManager(context, hazardRepository)
    }
}
