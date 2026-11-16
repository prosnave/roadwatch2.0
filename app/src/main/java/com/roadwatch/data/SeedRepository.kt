package com.roadwatch.data

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException

/**
 * Facade over [HazardStore] used by UI code. Bundled seed data (assets/seeds.csv) is imported into the
 * store once on first run, after which there is a single source of truth: the store.
 */
class SeedRepository(private val context: Context) {
    private val tag = "SeedRepository"
    private val store = HazardStore(context)

    /** Rows of the bundled seed asset. Empty if the asset is missing. */
    fun readBundledSeeds(): List<UserHazard> = try {
        context.assets.open("seeds.csv").bufferedReader().use { HazardCsv.parse(it.lineSequence()) }
    } catch (e: FileNotFoundException) {
        emptyList()
    } catch (e: Exception) {
        Log.e(tag, "Failed to read seeds.csv", e)
        emptyList()
    }

    /**
     * Kept for callers that list "seed vs user" rows. Seeds now live in the store (imported on first
     * run), so there is nothing separate to load.
     */
    fun loadSeeds(): Pair<SeedLoadResult, List<Hazard>> = SeedLoadResult(loaded = true, count = 0) to emptyList()

    fun loadUserHazards(): List<UserHazard> = store.list()

    fun addUserHazard(hazard: Hazard): Boolean = store.add(hazard)

    /** Hazards that should alert and be drawn: active, and not hidden by a legacy override. */
    fun activeHazards(): List<Hazard> = store.list()
        .map { it.hazard }
        .filter { it.active && !SeedOverrides.isDisabled(context, SeedOverrides.keyOf(it)) }

    /** Imports the bundled dataset once, only into an empty store (never overwrites field data). */
    fun importBundledSeedsIfFirstRun(): Int {
        val prefs = context.getSharedPreferences("roadwatch_seed", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return 0
        if (store.count() > 0) {
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            return 0
        }
        val seeds = readBundledSeeds().map { it.copy(hazard = it.hazard.copy(source = "SEED")) }
        if (seeds.isNotEmpty() && store.replaceAll(seeds)) {
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            Log.i(tag, "Imported ${seeds.size} bundled hazards")
            return seeds.size
        }
        return 0
    }

    /** Discards all local hazards and restores the bundled dataset. */
    fun resetToBundledSeeds(): Int {
        val seeds = readBundledSeeds().map { it.copy(hazard = it.hazard.copy(source = "SEED")) }
        return if (store.replaceAll(seeds)) seeds.size else -1
    }

    enum class AddResult { ADDED, DUPLICATE_NEARBY, MERGED_BIDIRECTIONAL, ERROR }

    data class ReportResult(val result: AddResult, val saved: UserHazard? = null, val previous: UserHazard? = null)

    /**
     * Saves a new report applying [HazardMerge] rules. [ReportResult.saved] and
     * [ReportResult.previous] let the caller offer an exact Undo.
     */
    fun report(hazard: Hazard): ReportResult = synchronized(TX_LOCK) {
        try {
            when (val d = HazardMerge.decide(store.list().map { it.hazard }, hazard)) {
                is HazardMerge.Decision.Add ->
                    store.addReturning(hazard)?.let { ReportResult(AddResult.ADDED, saved = it) } ?: ReportResult(AddResult.ERROR)
                is HazardMerge.Decision.Duplicate -> ReportResult(AddResult.DUPLICATE_NEARBY)
                is HazardMerge.Decision.UpgradeToBidirectional -> {
                    val before = store.list().firstOrNull { it.id == d.existing.id } ?: return@synchronized ReportResult(AddResult.ERROR)
                    if (store.update(before.id) { it.copy(directionality = "BIDIRECTIONAL") }) {
                        ReportResult(AddResult.MERGED_BIDIRECTIONAL, saved = before.copy(hazard = before.hazard.copy(directionality = "BIDIRECTIONAL")), previous = before)
                    } else ReportResult(AddResult.ERROR)
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "report failed", t)
            ReportResult(AddResult.ERROR)
        }
    }

    /** Undo for [report]: removes the added hazard, or restores the pre-merge version. */
    fun undo(r: ReportResult): Boolean = when (r.result) {
        AddResult.ADDED -> r.saved?.let { store.delete(it.id) } ?: false
        AddResult.MERGED_BIDIRECTIONAL -> r.previous?.let { p -> store.update(p.id) { p.hazard } } ?: false
        else -> false
    }

    fun addUserHazardWithDedup(hazard: Hazard): AddResult = report(hazard).result

    companion object {
        private const val KEY_SEEDED = "bundled_seeds_imported_v1"

        /** Serialises "decide then write" sequences (reports, imports) so concurrent callers can't both add the same hazard. */
        val TX_LOCK = Any()
    }
}
