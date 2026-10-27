package com.glassremote.domain.model

data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 6466,
    val bluetoothAddress: String? = null,
)

enum class LinkPhase { Idle, Connecting, Ready, Disconnected }

enum class TransportType { WiFi, BluetoothFallback }

data class MediaInfo(
    val title: String,
    val artist: String = "",
    val packageName: String = "",
    val appName: String = "",
    val isPlaying: Boolean = true,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
)

data class RemoteStatus(
    val phase: LinkPhase = LinkPhase.Idle,
    val transport: TransportType = TransportType.WiFi,
    val latencyMs: Int? = null,
    val poweredOn: Boolean? = null,
    val volume: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null,
    val error: String? = null,
    val media: MediaInfo? = null,
)

enum class KeyDirection { Short, StartLong, EndLong }
