package com.roadwatch.drive

import android.location.Location
import android.os.Handler
import android.os.Looper
import com.roadwatch.engine.AlertEngine
import com.roadwatch.engine.AlertEvent
import com.roadwatch.engine.Fix
import com.roadwatch.engine.Upcoming
import com.roadwatch.trip.TripLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide view of the current drive. The foreground service is the single writer (GPS, engine,
 * alerts); UI screens only observe. Replaces the old scheme where the HUD ran its own second GPS
 * listener and its own copy of the hazard-matching maths.
 */
object DriveSession {
    data class State(
        val running: Boolean = false,
        val simulated: Boolean = false,
        val location: Location? = null,
        val fix: Fix? = null,
        val courseDeg: Double? = null,
        val upcoming: List<Upcoming> = emptyList(),
        val lastAlert: AlertEvent? = null,
        val alertCount: Int = 0,
        val night: Boolean = false,
    )

    @Volatile var state: State = State()
        private set

    /** Live engine of the running real drive (not simulations); used to place reports using the trail. */
    @Volatile var engine: AlertEngine? = null
    @Volatile var tripLog: TripLog? = null

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: (State) -> Unit) {
        listeners.addIfAbsent(l)
        l(state)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    /** Must be called on the main thread. */
    fun update(change: (State) -> State) {
        state = change(state)
        val s = state
        listeners.forEach { it(s) }
    }

    fun post(change: (State) -> State) {
        if (Looper.myLooper() == Looper.getMainLooper()) update(change) else main.post { update(change) }
    }
}
