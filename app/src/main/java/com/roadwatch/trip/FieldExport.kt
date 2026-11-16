package com.roadwatch.trip

import android.content.Context
import android.os.Build
import com.roadwatch.app.BuildConfig
import com.roadwatch.data.HazardTransfer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a single zip the tester can send from the phone (Drive/WhatsApp/email): recent trip logs,
 * the hazard dataset, and a device/build summary. This is how real-world driving feedback reaches
 * the developer without any cable, server or account.
 */
object FieldExport {
    private const val MAX_TRIPS = 10

    fun createBundle(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val out = File(HazardTransfer.exportsDir(context), "roadwatch_field_$stamp.zip")
        val hazards = HazardTransfer.exportCsv(context)
        val trips = TripLog.list(context).take(MAX_TRIPS)

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            fun add(name: String, file: File) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write(deviceInfo(context).toByteArray())
            zip.closeEntry()
            add("hazards.csv", hazards)
            trips.forEach { add("trips/${it.name}", it) }
        }
        return out
    }

    private fun deviceInfo(context: Context): String = buildString {
        appendLine("RoadWatch ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build ${BuildConfig.BUILD_ID} flavor ${BuildConfig.FLAVOR}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Locale: ${Locale.getDefault()}")
        appendLine("Overlay permission: ${android.provider.Settings.canDrawOverlays(context)}")
        appendLine("Exported: ${Date()}")
    }
}
