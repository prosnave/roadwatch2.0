package com.roadwatch.core.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remote logging utility that sends logs to the debug server
 */
object RemoteLogger {

    private const val TAG = "RemoteLogger"
    private const val SERVER_URL = "http://10.0.2.2:8081/log" // Android emulator localhost
    private const val SERVER_URL_DEVICE = "http://192.168.1.100:8081/log" // Replace with your computer's IP

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val scope = CoroutineScope(Dispatchers.IO)

    // Configuration
    var enabled = true
    var useDeviceUrl = false // Set to true when testing on physical device

    private val serverUrl: String
        get() = if (useDeviceUrl) SERVER_URL_DEVICE else SERVER_URL

    /**
     * Send a log entry to the remote server
     */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return

        scope.launch {
            try {
                val logData = JSONObject().apply {
                    put("timestamp", dateFormat.format(Date()))
                    put("level", level)
                    put("tag", tag)
                    put("message", message)
                    if (throwable != null) {
                        put("exception", throwable.toString())
                        put("stacktrace", Log.getStackTraceString(throwable))
                    }
                }

                sendLogToServer(logData.toString())

            } catch (e: Exception) {
                // Don't log this recursively
                Log.e(TAG, "Failed to send remote log", e)
            }
        }
    }

    /**
     * Convenience methods for different log levels
     */
    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log("WARN", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("ERROR", tag, message, throwable)

    /**
     * Log app lifecycle events
     */
    fun logAppEvent(event: String, details: String? = null) {
        val message = "App Event: $event" + if (details != null) " - $details" else ""
        i("AppLifecycle", message)
    }

    /**
     * Log screen navigation
     */
    fun logScreenView(screenName: String, source: String? = null) {
        val message = "Screen View: $screenName" + if (source != null) " (from: $source)" else ""
        i("Navigation", message)
    }

    /**
     * Log user actions
     */
    fun logUserAction(action: String, details: String? = null) {
        val message = "User Action: $action" + if (details != null) " - $details" else ""
        i("UserAction", message)
    }

    /**
     * Log errors with context
     */
    fun logError(context: String, error: String, throwable: Throwable? = null) {
        val message = "Error in $context: $error"
        e("AppError", message, throwable)
    }

    private fun sendLogToServer(jsonData: String) {
        try {
            val url = URL(serverUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", jsonData.toByteArray().size.toString())
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonData)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Remote logging failed with response code: $responseCode")
            }

            connection.disconnect()

        } catch (e: Exception) {
            Log.w(TAG, "Failed to send log to remote server", e)
        }
    }

    /**
     * Initialize the remote logger
     */
    fun initialize(context: Context) {
        // You can add device detection here
        // For now, we'll use the default configuration
        i(TAG, "RemoteLogger initialized")
    }
}
