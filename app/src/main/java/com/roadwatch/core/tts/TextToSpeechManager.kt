package com.roadwatch.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) : TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var vibrator: Vibrator? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val voiceEnabledKey = booleanPreferencesKey("voice_enabled")
    private val vibrationEnabledKey = booleanPreferencesKey("vibration_enabled")
    private val alertDistanceKey = floatPreferencesKey("alert_distance")

    val voiceEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[voiceEnabledKey] ?: true
    }

    val vibrationEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[vibrationEnabledKey] ?: true
    }

    val alertDistance: Flow<Float> = dataStore.data.map { preferences ->
        preferences[alertDistanceKey] ?: 1.0f
    }

    init {
        initializeTTS()
        initializeVibrator()
    }

    private fun initializeTTS() {
        textToSpeech = TextToSpeech(context, this)
    }

    private fun initializeVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATED")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                val result = tts.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Language not supported, fallback to English
                    tts.setLanguage(Locale.ENGLISH)
                }

                tts.setSpeechRate(1.0f)
                tts.setPitch(1.0f)

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        requestAudioFocus()
                    }

                    override fun onDone(utteranceId: String?) {
                        abandonAudioFocus()
                    }

                    override fun onError(utteranceId: String?) {
                        abandonAudioFocus()
                    }
                })
            }
        }
    }

    private fun requestAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioFocusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
        } else {
            TODO("VERSION.SDK_INT < O")
        }

        audioFocusRequest?.let {
            audioManager.requestAudioFocus(it)
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }

    suspend fun speakAlert(message: String, hazardId: Long) {
        val voiceEnabled = voiceEnabled.first()
        val vibrationEnabled = vibrationEnabled.first()

        if (voiceEnabled) {
            speak(message, hazardId.toString())
        }

        if (vibrationEnabled) {
            vibrate()
        }
    }

    private fun speak(text: String, utteranceId: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun vibrate() {
        vibrator?.let {
            if (it.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATED")
                    it.vibrate(500)
                }
            }
        }
    }

    suspend fun setVoiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[voiceEnabledKey] = enabled
        }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[vibrationEnabledKey] = enabled
        }
    }

    suspend fun setAlertDistance(distance: Float) {
        dataStore.edit { preferences ->
            preferences[alertDistanceKey] = distance
        }
    }

    fun shutdown() {
        textToSpeech?.shutdown()
        abandonAudioFocus()
    }

    companion object {
        fun createHazardAlertMessage(hazardType: String, distance: Int): String {
            return when (hazardType.lowercase()) {
                "speed bump" -> "Speed bump ahead in $distance meters."
                "rumble strip" -> "Rumble strip ahead in $distance meters."
                "pothole" -> "Pothole ahead in $distance meters."
                "debris" -> "Debris on road ahead in $distance meters."
                "police" -> "Police ahead in $distance meters."
                "speed limit zone" -> "Entering $distance kilometer per hour zone."
                else -> "Hazard ahead in $distance meters."
            }
        }

        fun createSpeedLimitExitMessage(speedLimit: Int): String {
            return "Exiting $speedLimit kilometer per hour zone."
        }
    }
}
