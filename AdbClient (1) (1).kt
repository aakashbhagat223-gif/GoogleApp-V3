package com.glassremote.data.adb

import com.glassremote.domain.model.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight ADB client for communicating directly with Android TV ADB daemon (port 5555).
 * Enables absolute IME text injection ('input text ...') and dumpsys media_session inspection.
 */
@Singleton
class AdbClient @Inject constructor() {

    /** Sends raw text string directly to the focused input field via ADB shell input text. */
    suspend fun sendText(host: String, text: String, port: Int = ADB_PORT): Boolean = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext true
        // Android shell 'input text' expects spaces encoded as %s and shell metacharacters escaped
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace(";", "\\;")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("<", "\\<")
            .replace(">", "\\>")

        val cmd = "input text $escaped\n"
        execCommand(host, cmd, port)
    }

    /** Sends discrete key event code via ADB shell input keyevent. */
    suspend fun sendKey(host: String, keyCode: Int, port: Int = ADB_PORT): Boolean = withContext(Dispatchers.IO) {
        val cmd = "input keyevent $keyCode\n"
        execCommand(host, cmd, port)
    }

    /**
     * Scrapes TV active media sessions via 'dumpsys media_session'.
     * Parses active package name, track title, artist/channel, and playback state.
     */
    suspend fun scrapeNowPlaying(host: String, port: Int = ADB_PORT): MediaInfo? = withContext(Dispatchers.IO) {
        val raw = queryCommand(host, "dumpsys media_session\n", port) ?: return@withContext null
        parseMediaSessionOutput(raw)
    }

    /** Low-level socket runner for ADB shell commands. */
    private fun execCommand(host: String, command: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                val out: OutputStream = s.getOutputStream()
                out.write(command.toByteArray(Charsets.UTF_8))
                out.flush()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun queryCommand(host: String, command: String, port: Int): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                val out: OutputStream = s.getOutputStream()
                out.write(command.toByteArray(Charsets.UTF_8))
                out.flush()

                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val sb = StringBuilder()
                var line: String?
                var count = 0
                while (reader.readLine().also { line = it } != null && count < 250) {
                    sb.appendLine(line)
                    count++
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseMediaSessionOutput(output: String): MediaInfo? {
        val lines = output.lines()
        var currentPkg: String? = null
        var currentTitle: String? = null
        var currentArtist: String? = null
        var isPlaying = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package=")) {
                currentPkg = trimmed.substringAfter("package=").substringBefore(" ")
            } else if (trimmed.contains("description=") || trimmed.contains("MediaDescription")) {
                val titleMatch = Regex("""title=(.*?)(?:,\s*subtitle=|,\s*description=|$)""").find(trimmed)
                if (titleMatch != null) currentTitle = titleMatch.groupValues[1].trim()
                val subMatch = Regex("""subtitle=(.*?)(?:,\s*description=|$)""").find(trimmed)
                if (subMatch != null) currentArtist = subMatch.groupValues[1].trim()
            } else if (trimmed.contains("PlaybackState") || trimmed.startsWith("state=")) {
                if (trimmed.contains("state=3") || trimmed.contains("STATE_PLAYING")) {
                    isPlaying = true
                }
            }

            if (currentTitle != null && currentPkg != null) {
                val appLabel = when {
                    currentPkg.contains("youtube") -> "YouTube"
                    currentPkg.contains("stremio") -> "Stremio"
                    currentPkg.contains("netflix") -> "Netflix"
                    currentPkg.contains("spotify") -> "Spotify"
                    currentPkg.contains("disney") -> "Disney+"
                    else -> currentPkg.substringAfterLast('.')
                }
                return MediaInfo(
                    title = currentTitle,
                    artist = currentArtist ?: appLabel,
                    packageName = currentPkg,
                    appName = appLabel,
                    isPlaying = isPlaying
                )
            }
        }

        return null
    }

    companion object {
        const val ADB_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 1_200
        private const val READ_TIMEOUT_MS = 2_000
    }
}
