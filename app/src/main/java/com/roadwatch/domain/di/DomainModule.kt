package com.roadwatch.domain.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    // ProximityAlertEngine is provided directly by Hilt via @Inject constructor
}
