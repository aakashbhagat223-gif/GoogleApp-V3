package com.glassremote.di

import android.content.Context
import android.net.nsd.NsdManager
import com.glassremote.data.repository.TvRepositoryImpl
import com.glassremote.domain.repository.TvRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module @InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds abstract fun tvRepository(impl: TvRepositoryImpl): TvRepository
}

@Module @InstallIn(SingletonComponent::class)
object ProvidingModule {
    @Provides @Singleton
    fun nsdManager(@ApplicationContext c: Context): NsdManager = c.getSystemService(NsdManager::class.java)

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
