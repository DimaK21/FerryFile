package ru.kryu.ferryfile.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.kryu.ferryfile.data.files.SafFileStorageRepository
import ru.kryu.ferryfile.data.settings.SettingsRepositoryImpl
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindFileStorageRepository(impl: SafFileStorageRepository): FileStorageRepository
}
