package com.roadwatch.core.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** Distinct pulse patterns so a driver can feel severity without looking. */
    fun alert(context: Context, severity: com.roadwatch.engine.Severity) {
        val pattern = when (severity) {
            com.roadwatch.engine.Severity.WARNING -> longArrayOf(0, 160, 90, 160, 90, 160)
            com.roadwatch.engine.Severity.CAUTION -> longArrayOf(0, 130, 90, 130)
            com.roadwatch.engine.Severity.INFO -> longArrayOf(0, 70)
        }
        try {
            vibrator(context).vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Throwable) {}
    }

    fun tap(context: Context) {
        val vib = vibrator(context)
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
