package com.roadwatch.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export, import and share hazard data as CSV (the interchange format for consolidation later). */
object HazardTransfer {
    data class ImportSummary(val rows: Int, val added: Int, val merged: Int, val duplicates: Int, val skipped: Int) {
        override fun toString() = "Read $rows rows: $added added, $merged merged to both directions, $duplicates already known, $skipped invalid"
    }

    fun exportsDir(context: Context): File = File(context.filesDir, "exports").apply { mkdirs() }

    /** Writes the current dataset (all rows, including inactive) and returns the file. */
    fun exportCsv(context: Context, name: String = "hazards_export.csv"): File {
        val out = File(exportsDir(context), name)
        out.writeText(HazardCsv.formatAll(HazardStore(context).list()))
        return out
    }

    /** Safety copy taken before anything destructive. */
    fun backup(context: Context, reason: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return exportCsv(context, "backup_${reason}_$stamp.csv")
    }

    fun share(context: Context, file: File, mime: String, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /** Merges rows into the store, applying the same dedupe rules as live reporting. */
    fun importCsv(context: Context, csv: String): ImportSummary = synchronized(SeedRepository.TX_LOCK) {
        val store = HazardStore(context)
        val rows = HazardCsv.parse(csv.lineSequence())
        val totalLines = csv.lineSequence().count { it.isNotBlank() } - 1
        var added = 0
        var merged = 0
        var dup = 0
        var failed = 0
        val working = store.list().map { it.hazard }.toMutableList()
        rows.forEach { u ->
            when (val d = HazardMerge.decide(working, u.hazard)) {
                is HazardMerge.Decision.Add -> {
                    val saved = store.addReturning(u.hazard)
                    if (saved != null) { working += saved.hazard.copy(id = saved.id); added++ } else failed++
                }
                is HazardMerge.Decision.UpgradeToBidirectional ->
                    if (store.update(d.existing.id) { it.copy(directionality = "BIDIRECTIONAL") }) {
                        val i = working.indexOfFirst { it.id == d.existing.id }
                        if (i >= 0) working[i] = working[i].copy(directionality = "BIDIRECTIONAL")
                        merged++
                    } else failed++
                is HazardMerge.Decision.Duplicate -> dup++
            }
        }
        ImportSummary(rows.size, added, merged, dup, skipped = (totalLines - rows.size).coerceAtLeast(0) + failed)
    }
}
