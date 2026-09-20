# Custom TTS Service for Android

An Android Text-to-Speech (TTS) engine that connects to any OpenAI-compatible API backend (supporting the `/v1/audio/speech` endpoint).

This allows you to use custom, self-hosted, or cloud TTS voices — powered by [AllTalk TTS](https://github.com/erew123/alltalk_tts), [Piper](https://github.com/rhasspy/piper), OpenAI, or any compatible service — as a standard system-wide TTS engine on your Android device.

---

## Features

* Integrates as a standard Android TTS Engine (selectable in Android Settings → Text-to-Speech).
* Connects to any OpenAI-compatible `/v1/audio/speech` backend.
* **API Key is optional** — works without authentication for local servers.
* In-app Settings screen to configure:
    * Backend URL
    * API Key (optional, stored locally, input masked)
    * TTS Model
    * TTS Voice
    * Response Format (wav / mp3 / opus / pcm)
* **Quick-fill presets** for AllTalk (local) and OpenAI Cloud.
* **Direct link** to Android TTS engine selection from the main screen.
* Settings are persisted locally using Jetpack DataStore.
* Supports streaming mode (tested with [qwentts.cpp](https://github.com/ServeurpersoCom/qwentts.cpp))

---

## Setup

### 1. Install the app

Download the latest APK from [Releases](../../releases) and install it, or build from source.

### 2. Select as TTS Engine

Open the app and tap **"Open Android TTS Settings"**, then set *Custom TTS* as your preferred engine.

### 3. Configure the backend

Tap the ⚙️ icon to open Settings. Use a preset or fill in manually:

| Field | AllTalk (local) | OpenAI Cloud |
|-------|----------------|--------------|
| Backend URL | `http://<your-pc-ip>:7851/v1/audio/speech` | `https://api.openai.com/v1/audio/speech` |
| API Key | *(leave empty)* | Your OpenAI API Key |
| Model | `piper` | `tts-1` or `tts-1-hd` |
| Voice | `alloy` | `alloy`, `nova`, `echo`, … |
| Format | `wav` | `wav` |

Tap **Save**.

---

## Local Setup with AllTalk TTS

[AllTalk TTS](https://github.com/erew123/alltalk_tts) runs on your PC/server and exposes an OpenAI-compatible API on port `7851`.

1. Install and start AllTalk TTS on your machine.
2. Make sure your Android device and the server are on the same network.
3. Use the **"AllTalk (Local)"** preset in the app — update the IP address to match your server.

> **Note for WSL users:** Set up a Windows port forward so the phone can reach WSL:
> ```
> netsh interface portproxy add v4tov4 listenport=7851 listenaddress=0.0.0.0 connectport=7851 connectaddress=<WSL-IP>
> ```

---

## Screenshots

<img src="img/img_tts_engine_select.png" width="25%"><img src="img/img_backend_settings.png" width="25%">

---

## Supported Audio Formats

| Format | Status |
|--------|--------|
| `wav` | ✅ Supported |
| `pcm` | ✅ Supported |
| `mp3` | ❌ Not yet implemented |
| `opus` | ❌ Not yet implemented |

---

## Future Work / TODO

* MP3 / Opus decoding support.
* More sophisticated language/voice mapping.
