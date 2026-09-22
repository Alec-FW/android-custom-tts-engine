package com.example.CustomTts.ui // Adjust this package name!

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource // <-- Important import!
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.example.CustomTts.R // Import for R.string...
import com.example.CustomTts.data.PrefKeys // Adjust this import!
import com.example.CustomTts.data.settingsDataStore // Adjust this import!
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

// Default text for the Test button.
private const val TEST_TEXT_DEFAULT = "The quick brown fox jumps over the lazy dog."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var urlState by remember { mutableStateOf("") }
    var apiKeyState by remember { mutableStateOf("") }
    var modelState by remember { mutableStateOf("") }
    var voiceState by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var formatState by remember { mutableStateOf("") }
    var feedGapState by remember { mutableStateOf("0") }
    var blankPauseState by remember { mutableStateOf("0") }
    var streamModeState by remember { mutableStateOf(true) }
    var bufferingDelayState by remember { mutableStateOf("0") }
    var pcmRateState by remember { mutableStateOf("24000") }
    var pcmChannelsState by remember { mutableStateOf("1") }
    var pcmBitsState by remember { mutableStateOf("16") }
    var seedState by remember { mutableStateOf("") }
    var temperatureState by remember { mutableStateOf("") }
    var testTextState by remember { mutableStateOf(TEST_TEXT_DEFAULT) }
    var testTts by remember { mutableStateOf<TextToSpeech?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val defaultUrl = stringResource(id = R.string.settings_placeholder_url) // Get default URL from string (optional)
    // Or keep it hardcoded if it is a fixed API:
    // val defaultUrl = "https://api.openai.com/v1/audio/speech"
    val defaultModel = stringResource(id = R.string.settings_placeholder_model).substringAfter("e.g., ") // Get default from string
    val defaultVoice = stringResource(id = R.string.settings_placeholder_voice).substringAfter("e.g., ") // Get default from string
    val defaultFormat = "wav"
    // mp3/opus are commented out for now: the backend serves raw PCM
    // (with or without a WAV header) and the service only decodes those.
    val supportedFormats = listOf("wav", /* "mp3", "opus", */ "pcm")

    LaunchedEffect(Unit) {
        isLoading = true
        context.settingsDataStore.data.firstOrNull()?.let { prefs ->
            urlState = prefs[PrefKeys.BACKEND_URL] ?: defaultUrl // Use defaultUrl
            apiKeyState = prefs[PrefKeys.API_KEY] ?: ""
            modelState = prefs[PrefKeys.TTS_MODEL] ?: defaultModel
            voiceState = prefs[PrefKeys.TTS_VOICE] ?: defaultVoice
            formatState = prefs[PrefKeys.RESPONSE_FORMAT] ?: defaultFormat
            blankPauseState = (prefs[PrefKeys.BLANK_LINE_PAUSE_MS] ?: 0).toString()
            streamModeState = prefs[PrefKeys.STREAM_MODE] ?: true
            bufferingDelayState = (prefs[PrefKeys.BUFFERING_DELAY_MS] ?: 0).toString()
            pcmRateState = (prefs[PrefKeys.PCM_SAMPLE_RATE] ?: 24000).toString()
            pcmChannelsState = (prefs[PrefKeys.PCM_CHANNELS] ?: 1).toString()
            pcmBitsState = (prefs[PrefKeys.PCM_BITS] ?: 16).toString()
            seedState = prefs[PrefKeys.SEED] ?: ""
            temperatureState = prefs[PrefKeys.TEMPERATURE] ?: ""
            testTextState = prefs[PrefKeys.TEST_TEXT] ?: TEST_TEXT_DEFAULT
            Log.d("SettingsScreen", "Initial values loaded from DataStore.")
        } ?: run {
            urlState = defaultUrl
            modelState = defaultModel
            voiceState = defaultVoice
            formatState = defaultFormat
            Log.d("SettingsScreen", "Using default values (DataStore might be empty).")
        }
        isLoading = false
    }

    val models = listOf("tts-1", "tts-1-hd")
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    data class Preset(val label: String, val url: String, val apiKey: String, val model: String, val voice: String, val format: String)
    val presets = listOf(
        Preset("AllTalk (Local)", "http://192.168.0.48:7851/v1/audio/speech", "", "piper", "alloy", "wav"),
        Preset("OpenAI Cloud", "https://api.openai.com/v1/audio/speech", "", "tts-1", "alloy", "wav")
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings_title)) }, // Changed
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.settings_back_description)) // Changed
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(id = R.string.settings_instruction),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("Presets", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        OutlinedButton(onClick = {
                            urlState = preset.url
                            apiKeyState = preset.apiKey
                            modelState = preset.model
                            voiceState = preset.voice
                            formatState = preset.format
                        }) {
                            Text(preset.label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = urlState,
                    onValueChange = { urlState = it },
                    label = { Text(stringResource(id = R.string.settings_label_url)) }, // Changed
                    placeholder = { Text(urlState.ifBlank { defaultUrl }) }, // Show default or current value as placeholder
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = apiKeyState,
                    onValueChange = { apiKeyState = it },
                    label = { Text(stringResource(id = R.string.settings_label_api_key)) }, // Changed
                    placeholder = { Text(stringResource(id = R.string.settings_placeholder_api_key)) }, // Changed
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = modelState,
                    onValueChange = { modelState = it },
                    label = { Text(stringResource(id = R.string.settings_label_model)) }, // Changed
                    placeholder = { Text(modelState.ifBlank { defaultModel }) }, // Changed
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = voiceState,
                    onValueChange = { voiceState = it },
                    label = { Text(stringResource(id = R.string.settings_label_voice)) }, // Changed
                    placeholder = { Text(voiceState.ifBlank { defaultVoice }) }, // Changed
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                // --- Dropdown for response format ---
                Spacer(modifier = Modifier.height(16.dp))
                var formatExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = !formatExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = formatState, // Shows the currently selected format
                        onValueChange = {}, // Not directly editable
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.settings_label_response_format)) }, // Add string!
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded) },
                        modifier = Modifier
                            .menuAnchor() // Required for dropdown positioning
                            .fillMaxWidth()
                    )
                    // The actual dropdown menu
                    ExposedDropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        supportedFormats.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    formatState = selectionOption // Update state
                                    formatExpanded = false // Close menu
                                }
                            )
                        }
                    }
                } // End ExposedDropdownMenuBox

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = feedGapState,
                    onValueChange = { feedGapState = it },
                    label = { Text("Debug: feed gap in ms (0 = off)") },
                    placeholder = { Text("0") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = blankPauseState,
                    onValueChange = { blankPauseState = it },
                    label = { Text("Pause on blank line (ms)") },
                    placeholder = { Text("0") },
                    supportingText = { Text("Silence played for lines without letters/digits; 0 = off") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stream mode", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    Checkbox(
                        checked = streamModeState,
                        onCheckedChange = { streamModeState = it }
                    )
                }
                OutlinedTextField(
                    value = bufferingDelayState,
                    onValueChange = { bufferingDelayState = it },
                    label = { Text("Buffering delay (ms)") },
                    placeholder = { Text("0") },
                    supportingText = { Text("Silence before the streamed audio starts; 0 = off") },
                    enabled = streamModeState, // only meaningful in stream mode
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = seedState,
                        onValueChange = { seedState = it },
                        label = { Text("Seed") },
                        supportingText = { Text("empty = not sent") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = temperatureState,
                        onValueChange = { temperatureState = it },
                        label = { Text("Temperature") },
                        supportingText = { Text("empty = not sent") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = pcmRateState,
                    onValueChange = { pcmRateState = it },
                    label = { Text("PCM sample rate (Hz)") },
                    supportingText = { Text("Only for response format 'pcm'; must match the backend") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pcmChannelsState,
                        onValueChange = { pcmChannelsState = it },
                        label = { Text("PCM channels") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = pcmBitsState,
                        onValueChange = { pcmBitsState = it },
                        label = { Text("PCM bits (only 16 supported)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        scope.launch {
                            // Get string resources for snackbar messages
                            val validationErrorMsg = context.getString(R.string.settings_snackbar_validation_error)
                            val savedMsg = context.getString(R.string.settings_snackbar_saved)
                            val errorMsg = context.getString(R.string.settings_snackbar_save_error)

                            try {
                                // Take values from the state, trimming them if necessary
                                val urlToSave = urlState.trim()
                                val modelToSave = modelState.trim()
                                val voiceToSave = voiceState.trim()
                                // Note: formatState is used as-is, no trim needed/useful

                                // Check whether all *required* fields are filled in
                                // formatState should also be validated!
                                if (urlToSave.isBlank() || modelToSave.isBlank() || voiceToSave.isBlank() || formatState.isBlank()) {
                                    snackbarHostState.showSnackbar(validationErrorMsg)
                                    return@launch // End the coroutine here
                                }

                                // Store the values in the DataStore
                                context.settingsDataStore.edit { settings ->
                                    settings[PrefKeys.BACKEND_URL] = urlToSave
                                    settings[PrefKeys.API_KEY] = apiKeyState // Do not trim the key!
                                    settings[PrefKeys.TTS_MODEL] = modelToSave
                                    settings[PrefKeys.TTS_VOICE] = voiceToSave
                                    // Use formatState directly for saving
                                    settings[PrefKeys.RESPONSE_FORMAT] = formatState
                                    settings[PrefKeys.BLANK_LINE_PAUSE_MS] = blankPauseState.trim().toIntOrNull() ?: 0
                                    settings[PrefKeys.STREAM_MODE] = streamModeState
                                    settings[PrefKeys.BUFFERING_DELAY_MS] = bufferingDelayState.trim().toIntOrNull() ?: 0
                                    settings[PrefKeys.PCM_SAMPLE_RATE] = pcmRateState.trim().toIntOrNull() ?: 24000
                                    settings[PrefKeys.PCM_CHANNELS] = pcmChannelsState.trim().toIntOrNull() ?: 1
                                    settings[PrefKeys.PCM_BITS] = pcmBitsState.trim().toIntOrNull() ?: 16
                                    settings[PrefKeys.SEED] = seedState.trim()
                                    settings[PrefKeys.TEMPERATURE] = temperatureState.trim()
                                    settings[PrefKeys.TEST_TEXT] = testTextState
                                }

                                Log.i("SettingsScreen", "Settings saved!")
                                snackbarHostState.showSnackbar(savedMsg) // Success message
                            } catch (e: Exception) {
                                Log.e("SettingsScreen", "Failed to save settings", e)
                                snackbarHostState.showSnackbar(errorMsg) // Error message
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(id = R.string.settings_button_save))
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("Test", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = testTextState,
                    onValueChange = { testTextState = it },
                    label = { Text("Text to speak") },
                    supportingText = { Text("Sent through the normal TTS API, exactly as a client app would do.") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        // Replace any previous test instance.
                        testTts?.shutdown()
                        // The 3-arg constructor (engine = package name) dates back
                        // to API 14, which is below our minSdk, so it is always
                        // available; it pins the test to THIS engine instead of
                        // whatever the system default is.
                        testTts = TextToSpeech(
                            context,
                            object : TextToSpeech.OnInitListener {
                                override fun onInit(status: Int) {
                                    val tts = testTts ?: return
                                    if (status != TextToSpeech.SUCCESS) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("TTS init failed (status $status)")
                                        }
                                        return
                                    }
                                    tts.setLanguage(java.util.Locale("eng", "USA"))
                                    val r = tts.speak(
                                        testTextState,
                                        TextToSpeech.QUEUE_FLUSH,
                                        null,
                                        "test"
                                    )
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (r == TextToSpeech.SUCCESS) "Test synthesis started" else "speak() failed (code $r)"
                                        )
                                    }
                                }
                            },
                            context.packageName
                        )
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Test")
                }
            } // End Column
        } // End else (isLoading)
    } // End Scaffold
} // End SettingsScreen

// Preview stays commented out or must be adapted to use context/strings
// @Preview(showBackground = true, widthDp = 360, heightDp = 640)
// @Composable
// fun SettingsScreenPreview() { ... }
