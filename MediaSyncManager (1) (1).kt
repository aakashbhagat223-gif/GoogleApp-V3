package com.glassremote.data.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.glassremote.data.adb.AdbClient
import com.glassremote.domain.model.MediaInfo
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Module 2: "Now Playing" Real-Time Metadata & Artwork Sync.
 * Dual strategy: Primary local MediaSession token observer + Fallback ADB dumpsys media_session scraper.
 */
@Singleton
class MediaSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adbClient: AdbClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    private val _currentMedia = MutableStateFlow(
        MediaInfo(
            title = "YouTube TV",
            artist = "Living Room TV",
            album = "Android TV",
            isPlaying = true,
            positionMs = 45000,
            durationMs = 240000,
            appPackage = "com.google.android.youtube.tv",
            artworkUrl = null
        )
    )
    val currentMedia: StateFlow<MediaInfo> = _currentMedia.asStateFlow()

    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private var activeController: MediaController? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers?.firstOrNull())
    }

    fun startSync() {
        try {
            // Register local media session listener if permissions granted
            val component = ComponentName(context, MediaSyncManager::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val controllers = mediaSessionManager?.getActiveSessions(component)
            updateActiveController(controllers?.firstOrNull())
        } catch (_: SecurityException) {
            // Expected on non-notification listener service: rely on ADB scraper fallback
        }

        // Background ADB scraper loop for Android TV apps (YouTube TV, Stremio, Netflix, Hotstar)
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                pollTvMediaSession()
                delay(3000)
            }
        }
    }

    private suspend fun pollTvMediaSession() {
        val output = adbClient.executeCommand("dumpsys media_session") ?: return
        parseMediaSessionOutput(output)
    }

    private fun parseMediaSessionOutput(dumpsys: String) {
        var foundPackage = "com.google.android.youtube.tv"
        var foundTitle: String? = null
        var foundArtist: String? = null
        var isPlaying = false
        var positionMs = 0L
        var durationMs = 0L

        dumpsys.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("package=")) {
                foundPackage = line.substringAfter("package=").substringBefore(" ")
            }
            if (line.contains("description.getTitle()=") || line.contains("MediaDescription: title=")) {
                foundTitle = line.substringAfter("title=").substringBefore(",")
            }
            if (line.contains("description.getSubtitle()=") || line.contains("subtitle=")) {
                foundArtist = line.substringAfter("subtitle=").substringBefore(",")
            }
            if (line.contains("state=PlaybackState {state=3") || line.contains("state=3 (STATE_PLAYING)")) {
                isPlaying = true
            }
            if (line.contains("position=")) {
                positionMs = line.substringAfter("position=").substringBefore(",").toLongOrNull() ?: positionMs
            }
            if (line.contains("duration=")) {
                durationMs = line.substringAfter("duration=").substringBefore(",").toLongOrNull() ?: durationMs
            }
        }

        if (foundTitle != null) {
            _currentMedia.value = MediaInfo(
                title = foundTitle ?: "Active Streaming Stream",
                artist = foundArtist ?: getAppDisplayName(foundPackage),
                album = getAppDisplayName(foundPackage),
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = if (durationMs > 0) durationMs else 180000,
                appPackage = foundPackage,
                artworkUrl = getAppIconPreset(foundPackage)
            )
        }
    }

    private fun updateActiveController(controller: MediaController?) {
        activeController = controller
        val metadata = controller?.metadata
        val playbackState = controller?.playbackState

        if (metadata != null) {
            val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
            val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

            _currentMedia.value = _currentMedia.value.copy(
                title = title ?: _currentMedia.value.title,
                artist = artist ?: _currentMedia.value.artist,
                album = album ?: _currentMedia.value.album,
                durationMs = if (duration > 0) duration else _currentMedia.value.durationMs,
                isPlaying = isPlaying
            )
        }
    }

    suspend fun togglePlayPause(): Boolean {
        // Send KEYCODE_MEDIA_PLAY_PAUSE (85)
        return adbClient.sendKeyEvent(85)
    }

    suspend fun skipNext(): Boolean {
        // Send KEYCODE_MEDIA_NEXT (87)
        return adbClient.sendKeyEvent(87)
    }

    suspend fun skipPrevious(): Boolean {
        // Send KEYCODE_MEDIA_PREVIOUS (88)
        return adbClient.sendKeyEvent(88)
    }

    private fun getAppDisplayName(pkg: String): String = when {
        pkg.contains("youtube") -> "YouTube"
        pkg.contains("stremio") -> "Stremio"
        pkg.contains("netflix") -> "Netflix"
        pkg.contains("hotstar") -> "Disney+ Hotstar"
        pkg.contains("jio") -> "JioCinema"
        else -> "Android TV"
    }

    private fun getAppIconPreset(pkg: String): String = when {
        pkg.contains("youtube") -> "youtube_tv"
        pkg.contains("stremio") -> "stremio"
        pkg.contains("netflix") -> "netflix"
        else -> "default_tv"
    }

    fun stopSync() {
        syncJob?.cancel()
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
    }
}
