package com.roadwatch.drive

/** Intent actions and extras handled by [com.roadwatch.core.location.DriveModeService]. */
object DriveActions {
    const val START = "com.roadwatch.core.location.action.START"
    const val STOP = "com.roadwatch.core.location.action.STOP"
    const val REPORT = "com.roadwatch.core.location.action.REPORT"
    const val FEEDBACK = "com.roadwatch.core.location.action.FEEDBACK"
    const val SIMULATE = "com.roadwatch.core.location.action.SIMULATE"

    const val EXTRA_HAZARD_TYPE = "hazard_type"
    const val EXTRA_ALERT_ID = "alert_id"
    const val EXTRA_VERDICT = "verdict"
}
