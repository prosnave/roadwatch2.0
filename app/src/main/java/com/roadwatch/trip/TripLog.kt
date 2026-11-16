package com.roadwatch.trip

import android.content.Context
import android.util.Log
import com.roadwatch.engine.AlertEvent
import com.roadwatch.engine.Fix
import com.roadwatch.engine.TraceEvent
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records a drive (GPS fixes + every alert decision + driver feedback) to a JSONL file so a tester
 * can send it back and the engine can be replayed and tuned offline. Writes happen on a background
 * thread; the GPS callback never blocks on disk.
 */
class TripLog private constructor(val file: File) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "trip-log").apply { isDaemon = true } }
    private var writer: BufferedWriter? = null
    private var lastFlush = 0L

    @Volatile private var closed = false

    init {
        executor.execute {
            try {
                file.parentFile?.mkdirs()
                writer = BufferedWriter(FileWriter(file, true))
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open trip log", e)
            }
        }
    }

    fun meta(fields: Map<String, Any?>) = write(TripFormat.meta(fields))
    fun fix(f: Fix) = write(TripFormat.fix(f))
    fun alert(a: AlertEvent) = write(TripFormat.alert(a))
    fun trace(e: TraceEvent) = write(TripFormat.trace(e))
    fun custom(kind: String, fields: Map<String, Any?>) = write(TripFormat.custom(kind, fields))

    private fun write(line: String) {
        if (closed) return
        executor.execute {
            try {
                writer?.apply {
                    write(line)
                    newLine()
                    val now = System.currentTimeMillis()
                    if (now - lastFlush > 5_000) { flush(); lastFlush = now }
                }
            } catch (e: Exception) {
                Log.w(TAG, "trip write failed", e)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        executor.execute {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            writer = null
        }
        executor.shutdown()
        // Bounded wait so the tail of the trip is on disk before the service goes away.
        try { executor.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    companion object {
        private const val TAG = "TripLog"
        private const val MAX_TRIPS = 40

        fun dir(context: Context): File = File(context.filesDir, "trips")

        fun start(context: Context): TripLog {
            val d = dir(context)
            d.mkdirs()
            prune(d)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            var file = File(d, "trip_$stamp.jsonl")
            var n = 2
            while (file.exists()) file = File(d, "trip_${stamp}_${n++}.jsonl")
            return TripLog(file)
        }

        /** Newest first. */
        fun list(context: Context): List<File> =
            dir(context).listFiles { f -> f.name.endsWith(".jsonl") }?.sortedByDescending { it.name } ?: emptyList()

        /** Appends one line to an already-closed trip (used for feedback given after the drive). */
        fun append(file: File, line: String) {
            try { file.appendText(line + "\n") } catch (e: Exception) { Log.w(TAG, "append failed", e) }
        }

        private fun prune(d: File) {
            val files = d.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedByDescending { it.name } ?: return
            files.drop(MAX_TRIPS - 1).forEach { it.delete() }
        }
    }
}
