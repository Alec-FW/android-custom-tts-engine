package com.example.CustomTts

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.example.CustomTts.data.PrefKeys
import com.example.CustomTts.data.settingsDataStore
import kotlinx.coroutines.flow.first

private const val TAG = "CustomTtsConfig"

/**
 * All backend-related settings, parsed and validated in one place.
 * [CustomTtsService.synthesize] gets a fully ready-to-use config per
 * request; the synthesis path itself contains no settings parsing.
 */
data class BackendConfig(
    val backendUrl: String,
    val apiKey: String,
    val model: String,
    val voice: String,
    val responseFormat: String,
    val blankLinePauseMs: Int,
    val streamMode: Boolean,
    val bufferingDelayMs: Int,
    val pcmRate: Int,
    val pcmChannels: Int,
    val pcmBits: Int,
    /** Parsed as a JSON number (OpenAI-compatible "seed"). Null = not sent. */
    val seed: Int?,
    val temperature: Double?,
    val pcmSampleBytes: Int,
    /** android.media.AudioFormat encoding constant (Int). Always 16-bit. */
    val pcmEncoding: Int
)

/**
 * Reads and validates all TTS backend settings from DataStore.
 * Coercions are deliberate: bad values fall back to safe defaults and
 * are logged, never fatal (a bad URL is an error at request time, not a
 * settings parse error).
 */
suspend fun readBackendConfig(context: Context): BackendConfig {
    val s = context.settingsDataStore.data.first()

    val backendUrl = s[PrefKeys.BACKEND_URL].orEmpty()
    val apiKey = s[PrefKeys.API_KEY] ?: ""
    val model = s[PrefKeys.TTS_MODEL] ?: "tts-1"
    val voice = s[PrefKeys.TTS_VOICE] ?: "alloy"

    // "mp3" and "opus" are commented out for now
    val supportedFormats = listOf("wav", /* "mp3", "opus", */ "pcm")
    val rawFormat = s[PrefKeys.RESPONSE_FORMAT] ?: "wav"
    val responseFormat = if (rawFormat in supportedFormats) rawFormat else "wav"
    if (rawFormat != responseFormat) {
        Log.w(TAG, "Unsupported response_format '$rawFormat', using '$responseFormat'")
    }

    val blankLinePauseMs = (s[PrefKeys.BLANK_LINE_PAUSE_MS] ?: 0).coerceIn(0, 10_000)

    // Pre-buffering controls (feed the first audio chunk this much
    // earlier than the playback position, measured in ms)
    val streamMode = s[PrefKeys.STREAM_MODE] ?: true
    val bufferingDelayMs = (s[PrefKeys.BUFFERING_DELAY_MS] ?: 0).coerceIn(0, 10_000)

    // Raw PCM output parameters (used when response_format = "pcm").
    // Only 16-bit is supported: there is no 32-bit float path, and 8-bit
    // PCM from TTS streams would be misread as unsigned.
    val pcmRate = (s[PrefKeys.PCM_SAMPLE_RATE] ?: 24000).coerceIn(8000, 96000)
    val pcmChannels = (s[PrefKeys.PCM_CHANNELS] ?: 1).coerceIn(1, 2)
    val pcmBitsSet = s[PrefKeys.PCM_BITS] ?: 16
    if (pcmBitsSet != 16) {
        Log.w(TAG, "PCM_BITS=$pcmBitsSet is not supported, using 16")
    }
    val pcmBits = 16

    // Optional OpenAI-compatible parameters. Empty string = not sent.
    val seedRaw = s[PrefKeys.SEED]?.trim().orEmpty()
    val seed: Int? = if (seedRaw.isEmpty()) null else seedRaw.toIntOrNull()
    if (seedRaw.isNotEmpty() && seed == null) {
        Log.w(TAG, "SEED='$seedRaw' is not an integer, ignoring")
    }
    val tempRaw = s[PrefKeys.TEMPERATURE]?.trim().orEmpty()
    val temperature: Double? = if (tempRaw.isEmpty()) {
        null
    } else {
        tempRaw.toFloatOrNull()?.toDouble() ?: run {
            Log.w(TAG, "TEMPERATURE='$tempRaw' is not a number, ignoring")
            null
        }
    }

    // Derived values (pcmBits is always 16, see above)
    val pcmSampleBytes = pcmChannels * (pcmBits / 8)
    val pcmEncoding = AudioFormat.ENCODING_PCM_16BIT // Int constant

    return BackendConfig(
        backendUrl = backendUrl,
        apiKey = apiKey,
        model = model,
        voice = voice,
        responseFormat = responseFormat,
        blankLinePauseMs = blankLinePauseMs,
        streamMode = streamMode,
        bufferingDelayMs = bufferingDelayMs,
        pcmRate = pcmRate,
        pcmChannels = pcmChannels,
        pcmBits = pcmBits,
        seed = seed,
        temperature = temperature,
        pcmSampleBytes = pcmSampleBytes,
        pcmEncoding = pcmEncoding
    )
}
