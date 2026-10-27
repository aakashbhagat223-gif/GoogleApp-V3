package com.glassremote.data.haptics

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages tactile feedback profiles for HomeRemote+.
 * Supports clean key ticks, boundary heavy clicks, and continuous rumble for spring sliders.
 */
@Singleton
class HapticsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Clean, crisp micro-tick for standard QWERTY keypad taps and navigation buttons. */
    fun tick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                return
            } catch (_: Exception) { }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(12L, 80))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(12L)
        }
    }

    /** Standard button click confirmation. */
    fun click() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                return
            } catch (_: Exception) { }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(20L, 160))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(20L)
        }
    }

    /** Strong tactile click when slider reaches extreme 100% / -100% limits. */
    fun heavyBoundaryClick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                return
            } catch (_: Exception) { }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(35L, 255))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(35L)
        }
    }

    /** Minor continuous rumble pattern while holding slider at or beyond boundary limit. */
    fun continuousRumble() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 18, 40, 18)
            val amplitudes = intArrayOf(0, 75, 0, 95)
            try {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } catch (_: Exception) {
                v.vibrate(VibrationEffect.createOneShot(25L, 100))
            }
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(25L)
        }
    }

    fun cancel() {
        vibrator?.cancel()
    }
}
