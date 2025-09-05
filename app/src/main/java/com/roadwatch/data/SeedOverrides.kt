package com.roadwatch.data

import android.content.Context
import kotlin.math.round

object SeedOverrides {
    private const val FILE = "seed_overrides"
    private const val KEY_DISABLED = "disabled_set"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun keyOf(h: Hazard): String = "${h.type}|${h.lat.format6()}|${h.lng.format6()}"

    fun isDisabled(context: Context, key: String): Boolean =
        prefs(context).getStringSet(KEY_DISABLED, emptySet())?.contains(key) == true

    fun toggle(context: Context, key: String) {
        val set = prefs(context).getStringSet(KEY_DISABLED, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (!set.addOrRemove(key)) return
        prefs(context).edit().putStringSet(KEY_DISABLED, set).apply()
    }

    private fun MutableSet<String>.addOrRemove(value: String): Boolean {
        return if (contains(value)) { remove(value); true } else { add(value); true }
    }

    private fun Double.format6(): String = String.format(java.util.Locale.US, "%.6f", this)
}

