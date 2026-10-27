package com.glassremote.data.connection

import android.content.Context
import com.glassremote.data.adb.AdbClient
import com.glassremote.data.bluetooth.BluetoothTvManager
import com.glassremote.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine representing connection state between Phone and TV
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    WIFI_ACTIVE,
    WIFI_DEGRADED,
    BLUETOOTH_FALLBACK
}

/**
 * Module 1: Automated dual-channel connectivity manager that fails over to
 * Bluetooth RFCOMM/BLE when Wi-Fi latency exceeds 65 ms or heartbeats fail.
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adbClient: AdbClient,
    private val bluetoothTvManager: BluetoothTvManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeTransport = MutableStateFlow(TransportType.WIFI)
    val activeTransport: StateFlow<TransportType> = _activeTransport.asStateFlow()

    private val _wifiLatencyMs = MutableStateFlow(24L)
    val wifiLatencyMs: StateFlow<Long> = _wifiLatencyMs.asStateFlow()

    private var targetHost: String = "192.168.31.116"
    private var targetPort: Int = 6466
    private val LATENCY_THRESHOLD_MS = 65L
    private val HEARTBEAT_INTERVAL_MS = 2500L

    fun initialize(tvIp: String = "192.168.31.116") {
        targetHost = tvIp
        startHeartbeatLoop()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val ping = measureWifiLatency(targetHost, targetPort)
                _wifiLatencyMs.value = ping

                if (ping < 0) {
                    // Wi-Fi unreachable -> Fail over to Bluetooth
                    _connectionState.value = ConnectionState.BLUETOOTH_FALLBACK
                    _activeTransport.value = TransportType.BLUETOOTH
                    if (!bluetoothTvManager.isFallbackActive.value) {
                        bluetoothTvManager.connect()
                    }
                } else if (ping > LATENCY_THRESHOLD_MS) {
                    // Latency spiked above 65 ms -> Activate Bluetooth fallback channel
                    _connectionState.value = ConnectionState.WIFI_DEGRADED
                    _activeTransport.value = TransportType.BLUETOOTH
                    if (!bluetoothTvManager.isFallbackActive.value) {
                        bluetoothTvManager.connect()
                    }
                } else {
                    // Healthy Wi-Fi connection
                    _connectionState.value = ConnectionState.WIFI_ACTIVE
                    _activeTransport.value = TransportType.WIFI
                    bluetoothTvManager.setFallbackActive(false)
                }

                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun measureWifiLatency(host: String, port: Int): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                // Try port 6466 (Remote v2) or fallback to 5555 (ADB)
                socket.connect(InetSocketAddress(host, port), 600)
                System.currentTimeMillis() - start
            }
        } catch (_: Exception) {
            try {
                // Secondary check: ADB port 5555
                val startAdb = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, 5555), 600)
                    System.currentTimeMillis() - startAdb
                }
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Multiplexes command strings or keycodes through the currently authoritative channel.
     */
    suspend fun dispatchKey(keyCode: Int): Boolean {
        return if (_activeTransport.value == TransportType.BLUETOOTH) {
            bluetoothTvManager.sendKey(keyCode)
        } else {
            adbClient.sendKeyEvent(keyCode)
        }
    }

    suspend fun dispatchText(text: String): Boolean {
        return if (_activeTransport.value == TransportType.BLUETOOTH) {
            bluetoothTvManager.sendText(text)
        } else {
            adbClient.sendText(text)
        }
    }
}
