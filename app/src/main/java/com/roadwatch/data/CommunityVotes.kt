package com.roadwatch.data

import android.content.Context

object CommunityVotes {
    private const val FILE = "community_votes"
    private const val KEY_VOTED = "voted_keys"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getVotes(context: Context, key: String): Int = prefs(context).getInt(key, 0)

    fun upvote(context: Context, key: String) {
        val p = prefs(context)
        val voted = p.getStringSet(KEY_VOTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (voted.contains(key)) return
        val v = p.getInt(key, 0) + 1
        voted.add(key)
        p.edit().putInt(key, v).putStringSet(KEY_VOTED, voted).apply()
    }

    fun downvote(context: Context, key: String) {
        val p = prefs(context)
        val voted = p.getStringSet(KEY_VOTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!voted.contains(key)) return
        val v = (p.getInt(key, 0) - 1).coerceAtLeast(0)
        voted.remove(key)
        p.edit().putInt(key, v).putStringSet(KEY_VOTED, voted).apply()
    }

    fun hasVoted(context: Context, key: String): Boolean =
        prefs(context).getStringSet(KEY_VOTED, emptySet())?.contains(key) == true

    fun setVotes(context: Context, key: String, votes: Int) {
        val safe = if (votes < 0) 0 else votes
        prefs(context).edit().putInt(key, safe).apply()
    }
}
