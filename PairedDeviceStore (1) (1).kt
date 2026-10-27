package com.glassremote.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glassremote.domain.model.TvDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pairedStore by preferencesDataStore("paired_devices")

@Singleton
class PairedDeviceStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val ID = stringPreferencesKey("id")
    private val NAME = stringPreferencesKey("name")
    private val HOST = stringPreferencesKey("host")
    private val PORT = intPreferencesKey("port")

    val lastDevice: Flow<TvDevice?> = ctx.pairedStore.data.map { p ->
        val host = p[HOST] ?: return@map null
        TvDevice(p[ID] ?: host, p[NAME] ?: host, host, p[PORT] ?: 6466)
    }

    suspend fun save(d: TvDevice) {
        ctx.pairedStore.edit { it[ID] = d.id; it[NAME] = d.name; it[HOST] = d.host; it[PORT] = d.port }
    }

    suspend fun clear() { ctx.pairedStore.edit { it.clear() } }
}
