package com.talent.animescrap.di

import com.talent.animescrap.repo.AnimeRepository
import com.talent.animescrap.repo.AnimeRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@ExperimentalCoroutinesApi
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAnimeRepository(repository: AnimeRepositoryImpl): AnimeRepository

}