package com.example.CustomTts.data // Make sure the package name is correct

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey // <-- Add import!
import androidx.datastore.preferences.preferencesDataStore

// DataStore instance (from step 1)
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tts_settings")

// Object to hold the keys
object PrefKeys {
    // A key for the backend URL (type: String)
    val BACKEND_URL = stringPreferencesKey("backend_url")

    // A key for the API key (type: String)
    val API_KEY = stringPreferencesKey("api_key")

    // A key for the model (type: String)
    val TTS_MODEL = stringPreferencesKey("tts_model")

    // A key for the voice (type: String)
    val TTS_VOICE = stringPreferencesKey("tts_voice")

    // A key for the response format
    val RESPONSE_FORMAT = stringPreferencesKey("response_format")

    // Seed for the TTS backend (stored as text so "empty" is distinguishable
    // from "0": empty = do not send the field at all, 0 = valid seed).
    val SEED = stringPreferencesKey("seed")

    // Temperature for the TTS backend (same convention as SEED: empty =
    // do not send the field at all).
    val TEMPERATURE = stringPreferencesKey("temperature")

    // Custom text for the Test button in the settings UI.
    val TEST_TEXT = stringPreferencesKey("test_text")

    // Length in ms of the silence clip played for "silent" text
    // (no letters/digits: blank lines, punctuation only). 0 = no pause.
    val BLANK_LINE_PAUSE_MS = intPreferencesKey("blank_line_pause_ms")

    // Whether to request chunked streaming from the backend ("stream" in the
    // request JSON). Defaults to true when unset.
    val STREAM_MODE = booleanPreferencesKey("stream_mode")

    // Length in ms of the silence clip played BEFORE the streamed audio
    // starts (absorbs start-of-stream latency spikes). Stream mode only.
    // 0 = off.
    val BUFFERING_DELAY_MS = intPreferencesKey("buffering_delay_ms")

    // Assumed parameters of header-less raw PCM ("pcm" response format).
    val PCM_SAMPLE_RATE = intPreferencesKey("pcm_sample_rate")
    val PCM_CHANNELS = intPreferencesKey("pcm_channels")
    val PCM_BITS = intPreferencesKey("pcm_bits")

    // You can add further keys for other settings here as needed
}
