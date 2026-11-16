package com.roadwatch.alerts.delivery

import android.content.Context
import com.roadwatch.app.AppForeground
import com.roadwatch.core.util.Haptics
import com.roadwatch.engine.AlertEvent
import com.roadwatch.engine.AlertText
import com.roadwatch.engine.Severity
import com.roadwatch.engine.Upcoming
import com.roadwatch.notifications.NotificationHelper
import com.roadwatch.prefs.AppPrefs
import com.roadwatch.drive.DriveSession

/**
 * Turns an [AlertEvent] into what the driver perceives, honouring the user's settings:
 *
 *  audio  – earcon + speech, ducking music (see [AudioAnnouncer])
 *  haptic – severity-specific vibration pattern
 *  visual – banner over whatever app is in front; if that permission isn't granted, a heads-up
 *           notification. While RoadWatch itself is on screen the HUD shows the alert, so neither is used.
 *
 * Alerts are always muted when the user has muted RoadWatch.
 */
class AlertPresenter(private val context: Context) {
    private val audio = AudioAnnouncer(context) { kind, fields -> DriveSession.tripLog?.custom(kind, fields) }
    private val banner = OverlayBanner(context)
    private var bannerKey: String? = null

    fun present(e: AlertEvent) {
        if (AppPrefs.isMuted(context)) return

        val appVisible = AppForeground.isForeground
        val visual = AppPrefs.isVisualEnabled(context)
        var notificationUsed = false

        if (visual && !appVisible) {
            if (AppPrefs.isOverlayEnabled(context) && banner.canShow()) {
                banner.show(e.title, e.subtitle, e.severity, e.primary.type, holdMs = if (e.kind == AlertEvent.Kind.HAZARD) 15_000L else 6_000L)
                bannerKey = if (e.kind == AlertEvent.Kind.HAZARD) e.primary.key else null
                NotificationHelper.cancelAlert(context)
            } else {
                NotificationHelper.postAlert(context, e)
                notificationUsed = true
            }
        }
        // The heads-up channel vibrates by itself; don't double up.
        if (AppPrefs.isHapticsEnabled(context) && !notificationUsed && e.severity != Severity.INFO) Haptics.alert(context, e.severity)
        if (AppPrefs.isAudioEnabled(context)) audio.speak(e.speech, e.severity, e.id)
    }

    /** Short spoken confirmation (e.g. "Speed bump reported") for eyes-free reporting. */
    fun confirm(text: String) {
        if (AppPrefs.isAudioEnabled(context) && !AppPrefs.isMuted(context)) audio.speak(text, Severity.INFO)
        else if (AppPrefs.isHapticsEnabled(context)) Haptics.tap(context)
    }

    /** Keeps the banner's distance live and removes it once the hazard is behind us. */
    fun onProgress(upcoming: List<Upcoming>) {
        val key = bannerKey ?: return
        if (!banner.isShowing) { bannerKey = null; return }
        val u = upcoming.firstOrNull { it.key == key }
        if (u == null || u.alongM < 8.0) {
            banner.hide()
            bannerKey = null
        } else {
            banner.updateSubtitle(AlertText.shortDistance(u.alongM.toInt()) + " ahead")
        }
    }

    fun hideVisuals() {
        banner.hide()
        bannerKey = null
        NotificationHelper.cancelAlert(context)
    }

    fun shutdown() {
        hideVisuals()
        audio.shutdown()
    }
}
