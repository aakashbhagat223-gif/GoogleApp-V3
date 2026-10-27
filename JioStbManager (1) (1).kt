package com.glassremote.data.stb

import com.glassremote.data.adb.AdbClient
import com.glassremote.data.intent.IntentDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Module 4: Jio Set-Top Box (STB) Controller.
 * Manages dedicated peripheral commands forwarding over HDMI 2 with HDMI-CEC pass-through.
 */
@Singleton
class JioStbManager @Inject constructor(
    private val adbClient: AdbClient,
    private val intentDispatcher: IntentDispatcher,
) {
    /**
     * Switches the TV display directly to HDMI 2 (Jio STB).
     */
    suspend fun selectHdmi2(): Boolean {
        return intentDispatcher.switchToHdmi2()
    }

    /**
     * Sends numeric channel digits to the Set-Top Box (e.g. Channel 101).
     */
    suspend fun tuneChannelDigit(digit: Int): Boolean {
        if (digit in 0..9) {
            // Android KEYCODE_0 (7) through KEYCODE_9 (16)
            val keyCode = 7 + digit
            return adbClient.sendKeyEvent(keyCode)
        }
        return false
    }

    suspend fun channelUp(): Boolean = adbClient.sendKeyEvent(166) // KEYCODE_CHANNEL_UP
    suspend fun channelDown(): Boolean = adbClient.sendKeyEvent(167) // KEYCODE_CHANNEL_DOWN

    suspend fun openGuide(): Boolean = adbClient.sendKeyEvent(172) // KEYCODE_GUIDE / EPG
    suspend fun showInfo(): Boolean = adbClient.sendKeyEvent(165) // KEYCODE_INFO
    suspend fun toggleAudioLanguage(): Boolean = adbClient.sendKeyEvent(256) // KEYCODE_TV_AUDIO_DESCRIPTION

    suspend fun toggleStbPower(): Boolean = adbClient.sendKeyEvent(26) // KEYCODE_POWER
}
