package com.glassremote.data.ir

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** IR is strictly optional: on a phone without a blaster (e.g. CMF Phone 2) isSupported is false. */
@Singleton
class IrBlaster @Inject constructor(@ApplicationContext private val ctx: Context) {
    val isSupported: Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR) &&
            ctx.getSystemService(Context.CONSUMER_IR_SERVICE)
                ?.let { (it as ConsumerIrManager).hasIrEmitter() } == true

    fun transmit(carrierHz: Int, pattern: IntArray): Boolean {
        if (!isSupported) return false
        (ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager).transmit(carrierHz, pattern)
        return true
    }
}
