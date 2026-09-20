package com.example.CustomTts // Adjust package names

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.CustomTts.ui.SettingsScreen
import com.example.CustomTts.ui.theme.DummyTTSTheme

class MainActivity : ComponentActivity() {
    // Instance-level so onNewIntent can also flip it (the system gear
    // button launches this activity even when it is already running).
    private val showSettingsState = mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The gear button in the system TTS settings sends an explicit
        // intent (setClassName, action == null); the launcher sends
        // ACTION_MAIN. So: show the settings screen unless we were
        // launched from the launcher.
        showSettingsState.value = intent?.action != Intent.ACTION_MAIN

        setContent {
            DummyTTSTheme {
                var showSettings by showSettingsState

                if (showSettings) {
                    SettingsScreen(
                        onNavigateBack = { showSettings = false }
                    )
                } else {
                    // Main screen using string resources
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(id = R.string.main_title)) }, // Changed
                                actions = {
                                    IconButton(onClick = { showSettings = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.Settings,
                                            contentDescription = stringResource(id = R.string.main_settings_action_description) // Changed
                                        )
                                    }
                                }
                            )
                        }
                    ) { paddingValues ->
                        val context = LocalContext.current
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(id = R.string.main_screen_text_1))
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(stringResource(id = R.string.main_screen_text_2))
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(stringResource(id = R.string.main_screen_text_3))
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedButton(onClick = {
                                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                            }) {
                                Text("Open Android TTS Settings")
                            }
                        }
                    } // End Scaffold (main content)
                } // End else (main content)
            } // End Theme
        } // End setContent
    } // End onCreate

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showSettingsState.value = intent.action != Intent.ACTION_MAIN
    }
}

// Preview using string resources
@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    DummyTTSTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(id = R.string.main_title)) }, // Changed
                    actions = {
                        IconButton(onClick = { /* Preview: no action */ }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(id = R.string.main_settings_action_description)) // Changed
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(id = R.string.main_screen_text_1)) // Changed
                Spacer(modifier = Modifier.height(20.dp))
                Text(stringResource(id = R.string.main_screen_text_2)) // Changed
            }
        }
    }
}
