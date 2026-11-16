package com.roadwatch.data

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent hazard store (single CSV in app-private storage, format unchanged from v1 so existing
 * installs keep their field data). All access goes through one in-memory copy per process, so
 *  - reads are free (the alert engine used to re-parse CSV files on every GPS fix),
 *  - writes are serialised and atomic (temp file + rename; a crash can't truncate the dataset),
 *  - ids are unique and stable, and listeners hear about every change.
 */
class HazardStore(context: Context) {
    private val shared = Shared.forFile(File(context.applicationContext.filesDir, FILE_NAME))

    fun list(): MutableList<UserHazard> = shared.snapshot().toMutableList()

    fun add(h: Hazard): Boolean = addReturning(h) != null

    fun addReturning(h: Hazard): UserHazard? = shared.mutate { items ->
        val u = UserHazard(shared.nextId(), h)
        items += u
        u
    }

    fun delete(id: String): Boolean = shared.mutate { items -> items.removeAll { it.id == id } } == true

    /** Puts back a previously deleted hazard with its original id (Undo). */
    fun restore(u: UserHazard): Boolean = shared.mutate { items ->
        if (items.none { it.id == u.id }) items += u
        true
    } == true

    fun toggleActive(id: String): Boolean = update(id) { it.copy(active = !it.active) }

    fun update(id: String, change: (Hazard) -> Hazard): Boolean = shared.mutate { items ->
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) false else {
            items[i] = items[i].copy(hazard = change(items[i].hazard))
            true
        }
    } == true

    fun upsertByKey(key: String, hazard: Hazard): Boolean = shared.mutate { items ->
        val i = items.indexOfFirst { SeedOverrides.keyOf(it.hazard) == key }
        if (i >= 0) items[i] = items[i].copy(hazard = hazard) else items += UserHazard(shared.nextId(), hazard)
        true
    } == true

    /** Replace the whole dataset (import / reset). Ids are kept when unique, regenerated otherwise. */
    fun replaceAll(newItems: List<UserHazard>): Boolean = shared.mutate { items ->
        items.clear()
        val seen = HashSet<String>()
        newItems.forEach { u ->
            val id = if (u.id.isBlank() || !seen.add(u.id)) shared.nextId().also { seen.add(it) } else u.id
            items += u.copy(id = id)
        }
        true
    } == true

    fun count(): Int = shared.snapshot().size

    fun file(): File = shared.file

    companion object {
        private const val TAG = "HazardStore"
        const val FILE_NAME = "user_hazards.csv"

        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        /** Called on any thread after every successful change. */
        fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        internal fun notifyChanged() { listeners.forEach { runCatching(it) } }
    }

    private class Shared(val file: File) {
        private val lock = Any()
        private var items: MutableList<UserHazard>? = null
        private var lastId = 0L

        fun snapshot(): List<UserHazard> = synchronized(lock) { loaded().map { it.withId() } }

        private fun UserHazard.withId() = copy(hazard = hazard.copy(id = id))

        fun nextId(): String = synchronized(lock) {
            lastId = maxOf(System.currentTimeMillis(), lastId + 1)
            lastId.toString()
        }

        /** Runs [block] on the live list under the lock; persists and notifies if it reports success. */
        fun <T> mutate(block: (MutableList<UserHazard>) -> T): T? {
            val result: T
            synchronized(lock) {
                val list = loaded()
                val before = list.toList()
                result = block(list)
                if (list != before) {
                    if (!persist(list)) {
                        items = before.toMutableList()
                        return null
                    }
                }
            }
            notifyChanged()
            return result
        }

        private fun loaded(): MutableList<UserHazard> {
            items?.let { return it }
            val parsed = try {
                if (file.exists()) HazardCsv.parse(file.readLines().asSequence()) else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read hazards; starting empty (file kept)", e)
                emptyList()
            }
            val seen = HashSet<String>()
            val fixed = parsed.map { u ->
                if (u.id.isBlank() || !seen.add(u.id)) {
                    val id = nextIdUnlocked()
                    seen.add(id)
                    u.copy(id = id)
                } else u
            }.toMutableList()
            lastId = maxOf(lastId, fixed.maxOfOrNull { it.id.toLongOrNull() ?: 0L } ?: 0L)
            items = fixed
            return fixed
        }

        private fun nextIdUnlocked(): String {
            lastId = maxOf(System.currentTimeMillis(), lastId + 1)
            return lastId.toString()
        }

        private fun persist(list: List<UserHazard>): Boolean = try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(HazardCsv.formatAll(list))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist hazards", e)
            false
        }

        companion object {
            private val registry = HashMap<String, Shared>()
            fun forFile(f: File): Shared = synchronized(registry) { registry.getOrPut(f.absolutePath) { Shared(f) } }
        }
    }
}

data class UserHazard(
    val id: String,
    val hazard: Hazard,
)
