package com.glassremote.domain.repository

import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.MediaInfo
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TransportType
import com.glassremote.domain.model.TvDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TvRepository {
    val status: StateFlow<RemoteStatus>
    val mediaInfo: StateFlow<MediaInfo?>
    val activeTransport: StateFlow<TransportType>

    fun discover(): Flow<List<TvDevice>>
    suspend fun beginPairing(device: TvDevice)
    suspend fun submitPairingCode(device: TvDevice, code: String)
    suspend fun connect(device: TvDevice)
    suspend fun sendKey(keyCode: Int, direction: KeyDirection = KeyDirection.Short)
    suspend fun sendText(text: String)
    suspend fun launchApp(deepLink: String)
    suspend fun refreshMediaSession()
    fun disconnect()
    suspend fun resetPairing()
}
