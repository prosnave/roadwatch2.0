package com.roadwatch.app

/** True while any RoadWatch screen is visible. Alerts use banners/notifications only when it is not. */
object AppForeground {
    @Volatile private var started = 0

    val isForeground: Boolean get() = started > 0

    fun started() { started++ }
    fun stopped() { if (started > 0) started-- }
}
