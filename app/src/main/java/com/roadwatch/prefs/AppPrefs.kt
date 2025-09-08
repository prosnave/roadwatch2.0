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
    private const val KEY_CLUSTER_ENABLED = "cluster_enabled"
    private const val KEY_CLUSTER_SPEED_KPH = "cluster_speed_kph"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_AUTO_RESUME = "auto_resume_enabled"
    private const val KEY_LAST_AUTOSTOP_LAT = "last_autostop_lat"
    private const val KEY_LAST_AUTOSTOP_LNG = "last_autostop_lng"
    private const val KEY_LAST_AUTOSTOP_AT = "last_autostop_at"
    private const val KEY_LAST_DND_PROMPT_AT = "last_dnd_prompt_at"

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

    // Clustering controls
    fun setClusterEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CLUSTER_ENABLED, enabled).apply()
    }
    fun isClusterEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_ENABLED, true)

    fun setClusterSpeedThreshold(context: Context, kph: Int) {
        val safe = if (kph < 10) 10 else if (kph > 120) 120 else kph
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CLUSTER_SPEED_KPH, safe).apply()
    }
    fun getClusterSpeedThreshold(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SPEED_KPH, 50)

    // Haptics
    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
    }
    fun isHapticsEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAPTICS_ENABLED, true)

    // Auto-resume Drive Mode
    fun setAutoResumeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }
    fun isAutoResumeEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RESUME, true)

    fun recordAutoStop(context: Context, lat: Double, lng: Double) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LAST_AUTOSTOP_LAT, lat.toFloat())
            .putFloat(KEY_LAST_AUTOSTOP_LNG, lng.toFloat())
            .putLong(KEY_LAST_AUTOSTOP_AT, System.currentTimeMillis())
            .apply()
    }
    fun clearAutoStop(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_AUTOSTOP_LAT)
            .remove(KEY_LAST_AUTOSTOP_LNG)
            .remove(KEY_LAST_AUTOSTOP_AT)
            .apply()
    }
    fun getLastAutoStopLat(context: Context): Double? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_AUTOSTOP_LAT)) return null
        return prefs.getFloat(KEY_LAST_AUTOSTOP_LAT, 0f).toDouble()
    }
    fun getLastAutoStopLng(context: Context): Double? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_AUTOSTOP_LNG)) return null
        return prefs.getFloat(KEY_LAST_AUTOSTOP_LNG, 0f).toDouble()
    }
    fun getLastAutoStopAt(context: Context): Long? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_AUTOSTOP_AT)) return null
        return prefs.getLong(KEY_LAST_AUTOSTOP_AT, 0L)
    }

    fun setLastDndPromptAt(context: Context, whenMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_DND_PROMPT_AT, whenMs).apply()
    }
    fun getLastDndPromptAt(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_DND_PROMPT_AT, 0L)
}
