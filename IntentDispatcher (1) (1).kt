package com.glassremote.data.intent

import com.glassremote.data.adb.AdbClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Module 3: Native App Launching & Intent Interception.
 * Eliminates flaky UI-automation navigation macros (e.g., 14-step D-Pad sequences)
 * with deterministic, sub-50ms native Android intent dispatch over ADB/Remote service.
 */
@Singleton
class IntentDispatcher @Inject constructor(
    private val adbClient: AdbClient,
) {
    /**
     * Replaces macro: HOME WAIT 500 DOWN DOWN DOWN RIGHT RIGHT RIGHT... OK
     * Launches Stremio TV app natively in a single atomic intent call.
     */
    suspend fun launchStremio(): Boolean {
        // Direct intent to Stremio Leanback activity
        val cmd = "am start -n com.stremio.one/.MainActivity || am start -a android.intent.action.VIEW -d \"stremio://\" com.stremio.one"
        return adbClient.executeCommand(cmd) != null
    }

    /**
     * Replaces macro: HOME WAIT 500 RIGHT RIGHT RIGHT RIGHT RIGHT RIGHT WAIT 500 OK WAIT 500 DOWN OK...
     * Directly switches TV hardware input to HDMI 2 (Jio Set-Top Box) via Android TV Input Framework (TIF).
     */
    suspend fun switchToHdmi2(): Boolean {
        // 1. Android TV Input Framework command
        val cmdTif = "cmd tv_input set_current_input passthrough/HDMI2 || cmd tv_input set_current_input com.android.tv/.input.HdmiInputService/HW1"
        val res = adbClient.executeCommand(cmdTif)
        if (res != null && !res.contains("error", ignoreCase = true)) {
            return true
        }

        // 2. Hardware TV input selector keycode KEYCODE_TV_INPUT (178)
        adbClient.sendKeyEvent(178)
        return true
    }

    /**
     * Launches YouTube on TV directly to a search query or home.
     */
    suspend fun launchYouTube(searchQuery: String? = null): Boolean {
        val cmd = if (!searchQuery.isNullOrBlank()) {
            val encoded = searchQuery.replace(" ", "%s")
            "am start -a android.intent.action.VIEW -d \"vnd.youtube://results?search_query=$encoded\" com.google.android.youtube.tv"
        } else {
            "am start -n com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.MainActivity"
        }
        return adbClient.executeCommand(cmd) != null
    }

    /**
     * Launches Netflix on Android TV.
     */
    suspend fun launchNetflix(): Boolean {
        return adbClient.executeCommand("am start -n com.netflix.ninja/.MainActivity") != null
    }

    /**
     * Launches Disney+ Hotstar on Android TV.
     */
    suspend fun launchHotstar(): Boolean {
        return adbClient.executeCommand("monkey -p in.startv.hotstar -c android.intent.category.LAUNCHER 1") != null
    }

    /**
     * Launches JioCinema on Android TV.
     */
    suspend fun launchJioCinema(): Boolean {
        return adbClient.executeCommand("monkey -p com.jio.media.ondemand -c android.intent.category.LAUNCHER 1") != null
    }
}
