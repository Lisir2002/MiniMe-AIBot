package com.minime.code.di

import com.minime.code.feature.settings.domain.repository.AIProviderRepository
import com.minime.code.feature.settings.data.repository.AIProviderRepositoryImpl
import com.minime.code.feature.credentials.domain.repository.CredentialRepository
import com.minime.code.feature.credentials.data.repository.FileCredentialRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAIProviderRepository(
        aiProviderRepositoryImpl: AIProviderRepositoryImpl
    ): AIProviderRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        fileCredentialRepository: FileCredentialRepository
    ): CredentialRepository
}
