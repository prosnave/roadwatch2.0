package com.roadwatch.core.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object Haptics {
    fun tap(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(20)
            }
        } catch (_: Throwable) {}
    }
}

