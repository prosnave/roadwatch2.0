package com.roadwatch.alerts.delivery

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.roadwatch.engine.Severity
import com.roadwatch.prefs.AppPrefs
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Speaks alerts the way a navigation app does:
 *
 *  - Audio attributes USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, so
 *    music/podcasts are lowered (not paused, not left masking us) and restored afterwards. Phone calls
 *    hold focus, in which case we stay quiet rather than talk over the caller.
 *  - Plays on the normal media/Bluetooth route (A2DP / car stereo). We deliberately do NOT switch
 *    Bluetooth into SCO "call mode": that degrades car-stereo audio and cuts music for seconds.
 *  - A short earcon precedes speech to grab attention and to absorb the 100–300 ms Bluetooth sinks
 *    take to wake up (otherwise the first syllable of every alert gets eaten).
 *  - Volume is never changed unless the user opted in, and is restored after the alert.
 */
class AudioAnnouncer(
    context: Context,
    private val diagnostic: (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private data class Speech(val text: String, val severity: Severity, val alertId: Long?, val queuedAt: Long)
    private var pending: Speech? = null
    private var currentSpeech: Speech? = null
    private var closed = false
    private var focus: AudioFocusRequest? = null
    private var restoreVolume: (() -> Unit)? = null
    private val release = Runnable { finish() }
    private var utteranceCounter = 0L
    private var currentUtterance: String? = null
    private var speechRunnable: Runnable? = null

    private val navAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        tts = TextToSpeech(ctx) { status ->
            if (closed) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val r = t.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.ENGLISH)
                t.setAudioAttributes(navAttrs)
                t.setSpeechRate(1.05f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { handler.post {
                        if (utteranceId == currentUtterance) logSpeech("started", currentSpeech)
                    } }
                    override fun onDone(utteranceId: String?) { handler.post { if (utteranceId == currentUtterance) scheduleRelease(250) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { handler.post {
                        if (utteranceId == currentUtterance) {
                            logSpeech("error", currentSpeech)
                            Log.e(TAG, "Speech synthesis failed: $utteranceId")
                            scheduleRelease(0)
                        }
                    } }
                })
                ttsReady = true
                handler.post { pending?.let { pending = null; deliver(it) } }
            } else {
                Log.w(TAG, "TextToSpeech init failed: $status")
                diagnostic("audio", mapOf("state" to "init_failed", "status" to status))
            }
        }
    }

    val isReady: Boolean get() = ttsReady

    /** Plays earcon then speech. Must be called on the main thread. */
    fun speak(text: String, severity: Severity, alertId: Long? = null) {
        if (closed) return
        // Reporting feedback must not interrupt a safety warning.
        if (alertId == null && (currentSpeech?.alertId != null || pending?.alertId != null)) return
        val speech = Speech(text, severity, alertId, SystemClock.elapsedRealtime())
        logSpeech("queued", speech)
        if (!ttsReady) {
            pending = speech
            return
        }
        deliver(speech)
    }

    private fun deliver(speech: Speech) {
        if (closed) return
        // A cold TTS engine must never announce a hazard long after its visual alert.
        if (SystemClock.elapsedRealtime() - speech.queuedAt > 4_000L) {
            logSpeech("expired", speech)
            return
        }
        handler.removeCallbacks(release)
        speechRunnable?.let { handler.removeCallbacks(it) }
        if (!requestFocus()) {
            Log.i(TAG, "Audio focus denied (call in progress?) – skipping speech")
            logSpeech("focus_denied", speech)
            return
        }
        boostVolumeIfWanted()
        val lead = playEarcon(speech.severity)
        val id = "rw-${utteranceCounter++}"
        currentUtterance = id
        currentSpeech = speech
        val run = Runnable {
            if (SystemClock.elapsedRealtime() - speech.queuedAt > 4_000L) {
                logSpeech("expired", speech)
                finish()
            } else if (tts?.speak(speech.text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.ERROR) {
                logSpeech("error", speech)
                Log.e(TAG, "TTS rejected speech")
                finish()
            }
        }
        speechRunnable = run
        handler.postDelayed(run, lead)
        // Safety net: never hold focus longer than this even if the engine stalls.
        handler.postDelayed(release, lead + 15_000L)
    }

    fun stop() {
        pending = null
        handler.removeCallbacks(release)
        speechRunnable?.let { handler.removeCallbacks(it) }
        speechRunnable = null
        currentUtterance = null
        try { tts?.stop() } catch (_: Exception) {}
        finish()
    }

    fun shutdown() {
        closed = true
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
    }

    private fun scheduleRelease(delayMs: Long) {
        handler.removeCallbacks(release)
        handler.postDelayed(release, delayMs)
    }

    private fun finish() {
        currentSpeech = null
        currentUtterance = null
        focus?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focus = null
        restoreVolume?.invoke()
        restoreVolume = null
    }

    private fun logSpeech(state: String, speech: Speech?) {
        diagnostic("audio", mapOf(
            "t" to System.currentTimeMillis(), "state" to state, "alert" to speech?.alertId,
            "delayMs" to speech?.let { SystemClock.elapsedRealtime() - it.queuedAt },
        ))
    }

    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(navAttrs)
            .setOnAudioFocusChangeListener { change ->
                // A call or another app took focus: stop talking immediately.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    handler.post { stop() }
                }
            }
            .build()
        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = req
        return granted
    }

    private fun boostVolumeIfWanted() {
        if (!AppPrefs.isBoostVolume(ctx) || restoreVolume != null) return
        try {
            val stream = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            val current = am.getStreamVolume(stream)
            val target = (max * 0.6).toInt()
            if (current < target) {
                am.setStreamVolume(stream, target, 0)
                restoreVolume = { try { am.setStreamVolume(stream, current, 0) } catch (_: Exception) {} }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot change volume (DND policy?)", e)
        }
    }

    private fun hasBluetoothOutput(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        // 26/27 = TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER (API 31+), written as literals to compile against older SDKs.
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == 26 || it.type == 27 ||
            it.type == AudioDeviceInfo.TYPE_HEARING_AID
    }

    /** Returns how long (ms) until speech should start. */
    private fun playEarcon(severity: Severity): Long {
        return try {
            val leadSilenceMs = if (hasBluetoothOutput()) 300 else 60
            val tones = when (severity) {
                Severity.WARNING -> listOf(880.0 to 130, 1175.0 to 130)
                Severity.CAUTION -> listOf(988.0 to 140)
                Severity.INFO -> listOf(784.0 to 100)
            }
            val rate = 22_050
            val samples = ArrayList<Short>()
            repeat(rate * leadSilenceMs / 1000) { samples.add(0) }
            tones.forEach { (freq, ms) ->
                val n = rate * ms / 1000
                for (i in 0 until n) {
                    val env = minOf(1.0, i / (rate * 0.01), (n - i) / (rate * 0.01))
                    samples.add((sin(2 * PI * freq * i / rate) * env * 0.6 * Short.MAX_VALUE).toInt().toShort())
                }
                repeat(rate * 40 / 1000) { samples.add(0) }
            }
            val data = samples.toShortArray()
            val track = AudioTrack.Builder()
                .setAudioAttributes(navAttrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.play()
            val durationMs = data.size * 1000L / rate
            handler.postDelayed({ try { track.release() } catch (_: Exception) {} }, durationMs + 200)
            durationMs
        } catch (e: Exception) {
            Log.w(TAG, "Earcon failed", e)
            0L
        }
    }

    companion object {
        private const val TAG = "AudioAnnouncer"
    }
}
