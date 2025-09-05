package com.roadwatch.prefs

import android.content.Context

object AppPrefs {
    private const val FILE = "roadwatch_prefs"
    private const val KEY_MUTED_UNTIL = "muted_until"
    private const val KEY_AUDIO_FOCUS = "audio_focus" // DUCK or EXCLUSIVE
    private const val KEY_BG_ALERTS = "background_alerts"
    private const val KEY_PASSENGER_ENABLED = "passenger_enabled"
    private const val KEY_SPEED_CURVE = "speed_curve" // CONSERVATIVE, NORMAL, AGGRESSIVE
    private const val KEY_ZONE_ENTER = "zone_enter_msg"
    private const val KEY_ZONE_EXIT = "zone_exit_msg"
    private const val KEY_ZONE_REPEAT_MS = "zone_repeat_ms"
    private const val KEY_AUDIO_ENABLED = "audio_enabled"
    private const val KEY_VISUAL_ENABLED = "visual_enabled"
    private const val KEY_DEFAULT_MUTE_MIN = "default_mute_min"

    fun setMutedForMinutes(context: Context, minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MUTED_UNTIL, until)
            .apply()
    }

    fun clearMute(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MUTED_UNTIL)
            .apply()
    }

    fun isMuted(context: Context): Boolean {
        val until = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_MUTED_UNTIL, 0L)
        return until > System.currentTimeMillis()
    }
    fun getMutedUntilMillis(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_MUTED_UNTIL, 0L)

    fun setAudioFocusMode(context: Context, mode: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUDIO_FOCUS, mode).apply()
    }
    fun getAudioFocusMode(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_AUDIO_FOCUS, "DUCK") ?: "DUCK"

    fun setBackgroundAlerts(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BG_ALERTS, enabled).apply()
    }
    fun isBackgroundAlerts(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_BG_ALERTS, false)

    fun setPassengerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PASSENGER_ENABLED, enabled).apply()
    }
    fun isPassengerEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_PASSENGER_ENABLED, true)

    fun setSpeedCurve(context: Context, curve: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_SPEED_CURVE, curve).apply()
    }
    fun getSpeedCurve(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SPEED_CURVE, "NORMAL") ?: "NORMAL"

    fun setZoneConfig(context: Context, enter: String, exit: String, repeatMs: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ZONE_ENTER, enter)
            .putString(KEY_ZONE_EXIT, exit)
            .putLong(KEY_ZONE_REPEAT_MS, repeatMs)
            .apply()
    }
    fun getZoneEnter(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ZONE_ENTER, "Entering speed limit zone") ?: "Entering speed limit zone"
    fun getZoneExit(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ZONE_EXIT, "Exiting speed limit zone") ?: "Exiting speed limit zone"
    fun getZoneRepeatMs(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_ZONE_REPEAT_MS, 60_000L)

    fun setAlertChannels(context: Context, audio: Boolean, visual: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_ENABLED, audio)
            .putBoolean(KEY_VISUAL_ENABLED, visual)
            .apply()
    }
    fun isAudioEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_ENABLED, true)
    fun isVisualEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_VISUAL_ENABLED, true)

    fun setDefaultMuteMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DEFAULT_MUTE_MIN, minutes).apply()
    }
    fun getDefaultMuteMinutes(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_MUTE_MIN, 20)
}
