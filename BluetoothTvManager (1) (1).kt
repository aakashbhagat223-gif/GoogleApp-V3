package com.glassremote.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secondary fallback connection layer using Android Bluetooth RFCOMM / BLE profiles.
 * Activated when Wi-Fi is unstable or latency spikes above 65 ms threshold.
 */
@Singleton
class BluetoothTvManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = btManager?.adapter

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val _isFallbackActive = MutableStateFlow(false)
    val isFallbackActive: StateFlow<Boolean> = _isFallbackActive.asStateFlow()

    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice.asStateFlow()

    /** Standard SPP / TV Serial RFCOMM UUID */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    val isBluetoothSupported: Boolean
        get() = adapter != null

    @SuppressLint("MissingPermission")
    suspend fun connect(targetAddressOrName: String? = null): Boolean = withContext(Dispatchers.IO) {
        val btAdapter = adapter ?: return@withContext false
        if (!btAdapter.isEnabled) return@withContext false

        try {
            close()
            // Find paired TV device matching the target address or containing "TV" / "Android"
            val bonded = btAdapter.bondedDevices ?: emptySet()
            val target: BluetoothDevice? = if (!targetAddressOrName.isNullOrBlank()) {
                bonded.firstOrNull { it.address.equals(targetAddressOrName, ignoreCase = true) || it.name.contains(targetAddressOrName, ignoreCase = true) }
            } else {
                bonded.firstOrNull { it.name?.contains("TV", ignoreCase = true) == true || it.name?.contains("Android", ignoreCase = true) == true }
            }

            val device = target ?: bonded.firstOrNull() ?: return@withContext false
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            btAdapter.cancelDiscovery()
            s.connect()

            socket = s
            outputStream = s.outputStream
            _isFallbackActive.value = true
            _connectedDevice.value = device.name ?: device.address
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    suspend fun sendKey(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext false
        try {
            // Android TV HID/RFCOMM payload: [0x01 (KeyCommand), keyCode MSB, keyCode LSB, 0x00]
            val packet = byteArrayOf(0x01, ((keyCode shr 8) and 0xFF).toByte(), (keyCode and 0xFF).toByte(), 0x00)
            out.write(packet)
            out.flush()
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext false
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val packet = byteArrayOf(0x02, (bytes.size and 0xFF).toByte()) + bytes
            out.write(packet)
            out.flush()
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    fun setFallbackActive(active: Boolean) {
        _isFallbackActive.value = active
    }

    fun close() {
        _isFallbackActive.value = false
        _connectedDevice.value = null
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) { }
        outputStream = null
        socket = null
    }
}
