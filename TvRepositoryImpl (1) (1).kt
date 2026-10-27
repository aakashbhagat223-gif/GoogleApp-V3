package com.glassremote.data.repository

import android.view.KeyEvent
import com.glassremote.data.adb.AdbClient
import com.glassremote.data.bluetooth.BluetoothTvManager
import com.glassremote.data.haptics.HapticsManager
import com.glassremote.data.network.PairingClient
import com.glassremote.data.network.PairingSession
import com.glassremote.data.network.RemoteSession
import com.glassremote.data.network.TvDiscovery
import com.glassremote.data.security.CertificateManager
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.MediaInfo
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TransportType
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import com.glassremote.proto.remote.RemoteDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvRepositoryImpl @Inject constructor(
    private val discovery: TvDiscovery,
    private val certs: CertificateManager,
    private val store: PairedDeviceStore,
    private val adb: AdbClient,
    private val bt: BluetoothTvManager,
    private val haptics: HapticsManager,
) : TvRepository {

    private val repoScope = CoroutineScope(Dispatchers.IO + Job())

    private val _status = MutableStateFlow(RemoteStatus())
    override val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    private val _mediaInfo = MutableStateFlow<MediaInfo?>(null)
    override val mediaInfo: StateFlow<MediaInfo?> = _mediaInfo.asStateFlow()

    private val _activeTransport = MutableStateFlow(TransportType.WiFi)
    override val activeTransport: StateFlow<TransportType> = _activeTransport.asStateFlow()

    @Volatile private var pairing: PairingSession? = null
    @Volatile private var remote: RemoteSession? = null
    @Volatile private var currentDevice: TvDevice? = null
    private var syncJob: Job? = null
    private var latencyMonitorJob: Job? = null

    override fun discover(): Flow<List<TvDevice>> = discovery.discover()

    // ---------- Pairing ----------
    override suspend fun beginPairing(device: TvDevice) {
        pairing?.close()
        pairing = PairingClient(certs).begin(device.host)
    }

    override suspend fun submitPairingCode(device: TvDevice, code: String) {
        val session = pairing ?: throw IllegalStateException("Pairing not started")
        session.submitCode(code)
        session.close()
        pairing = null
        store.save(device)
    }

    // ---------- Control Channel & Transport Fallback ----------
    override suspend fun connect(device: TvDevice) = withContext(Dispatchers.IO) {
        disconnect()
        currentDevice = device
        _status.value = RemoteStatus(LinkPhase.Connecting, transport = TransportType.WiFi)

        lateinit var session: RemoteSession
        session = RemoteSession(certs, device.host) { newStatus ->
            if (remote === session) {
                _status.update {
                    it.copy(
                        phase = newStatus.phase,
                        poweredOn = newStatus.poweredOn ?: it.poweredOn,
                        volume = newStatus.volume ?: it.volume,
                        volumeMax = newStatus.volumeMax ?: it.volumeMax,
                        muted = newStatus.muted ?: it.muted,
                        error = newStatus.error
                    )
                }
            }
        }
        remote = session

        try {
            session.connect()
            startBackgroundSync(device)
            startLatencyMonitor(device)
        } catch (t: Throwable) {
            remote = null
            session.close()
            _status.value = RemoteStatus(LinkPhase.Disconnected, error = t.message ?: "Connection failed")
            // Attempt Bluetooth fallback if Wi-Fi connection fails
            tryBluetoothFallback(device)
            throw t
        }
    }

    private fun startLatencyMonitor(device: TvDevice) {
        latencyMonitorJob?.cancel()
        latencyMonitorJob = repoScope.launch {
            while (isActive && remote != null) {
                val ping = measurePing(device.host)
                _status.update { it.copy(latencyMs = ping) }

                // Fallback rule: If Wi-Fi latency exceeds 65 ms threshold or drops, engage Bluetooth fallback
                if (ping == null || ping > 65) {
                    if (_activeTransport.value == TransportType.WiFi) {
                        val btConnected = bt.connect(device.bluetoothAddress ?: device.name)
                        if (btConnected) {
                            _activeTransport.value = TransportType.BluetoothFallback
                            _status.update { it.copy(transport = TransportType.BluetoothFallback) }
                        }
                    }
                } else if (ping <= 65 && _activeTransport.value == TransportType.BluetoothFallback) {
                    // Recover back to primary Wi-Fi when latency normalizes
                    _activeTransport.value = TransportType.WiFi
                    _status.update { it.copy(transport = TransportType.WiFi) }
                }
                delay(3_000)
            }
        }
    }

    private suspend fun tryBluetoothFallback(device: TvDevice) {
        val success = bt.connect(device.bluetoothAddress ?: device.name)
        if (success) {
            _activeTransport.value = TransportType.BluetoothFallback
            _status.value = RemoteStatus(LinkPhase.Ready, transport = TransportType.BluetoothFallback)
        }
    }

    private fun startBackgroundSync(device: TvDevice) {
        syncJob?.cancel()
        syncJob = repoScope.launch {
            while (isActive && remote != null) {
                refreshMediaSession()
                delay(4_000)
            }
        }
    }

    override suspend fun refreshMediaSession() = withContext(Dispatchers.IO) {
        val dev = currentDevice ?: return@withContext
        val info = adb.scrapeNowPlaying(dev.host)
        if (info != null) {
            _mediaInfo.value = info
            _status.update { it.copy(media = info) }
        }
    }

    override suspend fun sendKey(keyCode: Int, direction: KeyDirection) {
        // Trigger subtle haptic tick for clean key response
        haptics.tick()

        // If on Bluetooth fallback, transmit over RFCOMM
        if (_activeTransport.value == TransportType.BluetoothFallback) {
            bt.sendKey(keyCode)
            return
        }

        val session = remote
        val dir = when (direction) {
            KeyDirection.Short -> RemoteDirection.DIRECTION_SHORT
            KeyDirection.StartLong -> RemoteDirection.DIRECTION_START_LONG
            KeyDirection.EndLong -> RemoteDirection.DIRECTION_END_LONG
        }

        withContext(Dispatchers.IO) {
            try {
                session?.sendKey(keyCode, dir)
            } catch (_: IOException) {
                // Try ADB fallback for key event
                currentDevice?.let { dev -> adb.sendKey(dev.host, keyCode) }
            }
        }
    }

    /**
     * Permanent Fix for Keypad Text Transmission:
     * Dispatches text directly to the TV using both Android TV Remote IME protocol
     * and ADB 'input text' injection so search inputs on YouTube/Stremio register absolute text.
     */
    override suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        haptics.tick()
        var delivered = false

        // 1. Android TV Remote Control v2 Protocol IME Batch Edit
        try {
            remote?.sendText(text)
            delivered = true
        } catch (_: Exception) { }

        // 2. Direct ADB Shell 'input text' (permanent fix for on-screen TV keyboards like YouTube)
        currentDevice?.let { dev ->
            val adbOk = adb.sendText(dev.host, text)
            if (adbOk) delivered = true
        }

        // 3. Bluetooth fallback if Wi-Fi unavailable
        if (!delivered && _activeTransport.value == TransportType.BluetoothFallback) {
            bt.sendText(text)
        }
    }

    suspend fun sendKey(keyName: String, direction: KeyDirection = KeyDirection.Short) {
        val code = KEY_NAMES[keyName.trim().uppercase()] ?: return
        sendKey(code, direction)
    }

    override suspend fun launchApp(deepLink: String) {
        val session = remote ?: return
        val link = if (deepLink.contains("://")) deepLink else "market://launch?id=$deepLink"
        withContext(Dispatchers.IO) {
            try {
                session.launchApp(link)
            } catch (_: IOException) {
                session.close()
            }
        }
    }

    override fun disconnect() {
        syncJob?.cancel(); syncJob = null
        latencyMonitorJob?.cancel(); latencyMonitorJob = null
        val old = remote
        remote = null
        old?.close()
        bt.close()
        _activeTransport.value = TransportType.WiFi
    }

    override suspend fun resetPairing() {
        disconnect()
        pairing?.close()
        pairing = null
        certs.resetIdentity()
        store.clear()
        _status.value = RemoteStatus()
        _mediaInfo.value = null
    }

    private fun measurePing(host: String): Int? {
        val t0 = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, 6466), 1_200) }
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val KEY_NAMES: Map<String, Int> = mapOf(
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
            "SELECT" to KeyEvent.KEYCODE_DPAD_CENTER,
            "BACK" to KeyEvent.KEYCODE_BACK,
            "HOME" to KeyEvent.KEYCODE_HOME,
            "POWER" to KeyEvent.KEYCODE_POWER,
            "VOL_UP" to KeyEvent.KEYCODE_VOLUME_UP,
            "VOL_DOWN" to KeyEvent.KEYCODE_VOLUME_DOWN,
            "MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
            "CH_UP" to KeyEvent.KEYCODE_CHANNEL_UP,
            "CH_DOWN" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "ASSIST" to KeyEvent.KEYCODE_ASSIST,
            "SETTINGS" to KeyEvent.KEYCODE_SETTINGS,
            "QUICK_SETTINGS" to KeyEvent.KEYCODE_SETTINGS,
        )
    }
}
