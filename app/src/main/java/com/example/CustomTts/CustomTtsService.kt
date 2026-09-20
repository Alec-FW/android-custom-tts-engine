package com.example.CustomTts

import android.media.AudioFormat
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readFully
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CustomTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "CustomTtsService"

        // Defaults for raw PCM without header (settings may override) -
        // must match the backend!
        private const val PCM_SAMPLE_RATE = 24000
        private const val PCM_CHANNELS = 1
        private const val PCM_BITS = 16
        private const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // Bytes per sample = channels * bitsPerSample / 8 (16-bit mono = 2).
        private const val PCM_SAMPLE_BYTES = PCM_CHANNELS * 2

        /** Rounds [n] down to the nearest multiple of [sampleBytes]. */
        private fun Int.alignDown(sampleBytes: Int): Int = (this / sampleBytes) * sampleBytes

        /**
         * True if the text contains no letters or digits at all - i.e. it
         * is "empty" as far as TTS is concerned: empty strings, pure
         * whitespace, or only punctuation/special characters (chapter
         * breaks, dashes, ellipses, ...).
         */
        private fun isSilentText(text: String): Boolean =
            !text.any { it.isLetterOrDigit() }
    }

    /**
     * Terminal-callback bookkeeping for one utterance.
     *
     * Contract: once start() has been called, done() must ALWAYS follow -
     * on normal completion, after error(), or even after an external stop.
     * Worst case it is a no-op, but it must be there.
     */
    private class Utterance(val callback: SynthesisCallback, val seq: Long, val cfg: BackendConfig) {
        val requestStartMs: Long = SystemClock.elapsedRealtime()
        var sampleRate: Int = PCM_SAMPLE_RATE
        var encoding: Int = AudioFormat.ENCODING_PCM_16BIT
        var numChannels: Int = 1
        var sampleBytes: Int = 2

        /** Tries to start() with set parameters */
        fun start(): Int {
            val startResult = callback.start(sampleRate, encoding, numChannels)
            if (startResult == TextToSpeech.STOPPED) {
                Log.w(TAG, "seq=$seq: start rejected (TextToSpeech.STOPPED), aborting (no error reported)")
                return -1
            } else if (startResult != TextToSpeech.SUCCESS) {
                Log.w(TAG, "seq=$seq: start rejected with unknown error, aborting")
                fail(TextToSpeech.ERROR_OUTPUT)
                return -1
            }
            return 0
        }

        /** error(code), then done() if start() was called. */
        fun fail(code: Int) {
            Log.w(TAG, "seq=$seq: reporting error code: $code")
            callback.error(code)
            finish()
        }

        /** done() exactly once, and only if start() was called and not already done. */
        fun finish() {
            if (!callback.hasStarted() || callback.hasFinished()) return
            Log.d(TAG, "seq=$seq: calling done()")
            callback.done()
        }
    }

    private var httpClient: HttpClient? = null

    // Job of the in-flight utterance. Created per request, cancelled from
    // onStop()/onDestroy() (other threads) to unblock the synthesis thread.
    @Volatile private var currentJob: Job? = null

    // Monotonic counter for log lines.
    @Volatile private var nextSeq = 0L

    override fun onCreate() {
        super.onCreate()
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
            install(HttpTimeout) {
                // No total request timeout: an utterance's feed phase lasts as
                // long as its audio plays (can be minutes). A hung/dead
                // connection is bounded by connectTimeoutMillis (headers) and
                // socketTimeoutMillis (IDLE - any byte resets it) instead.
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
            }
        }
        Log.i(TAG, "Service created, HTTP client started.")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        currentJob?.cancel()
        httpClient?.close()
        httpClient = null
        super.onDestroy()
    }

    // --------------------------------------------------------------------------
    // Language / engine metadata
    // --------------------------------------------------------------------------

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        // Log.d(TAG, "onIsLanguageAvailable: lang=$lang, country=$country, variant=$variant")
        // We don't query server for languages, so there's no reason to limit this to any particular set
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
		/*
        return when (lang?.lowercase()) {
            "eng" -> TextToSpeech.LANG_COUNTRY_AVAILABLE // Or LANG_AVAILABLE
            "de" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            "es" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }*/
    }

    override fun onGetLanguage(): Array<String>? {
        Log.d(TAG, "onGetLanguage called")
        // Example: Returns US English as the default.
        // TODO: Adjust to the selected or configured default language
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        Log.d(TAG, "onLoadLanguage for $lang-$country-$variant: Result=$result")
        return result
    }

    // --------------------------------------------------------------------------
    // onStop(): external stop. May be called from any thread EXCEPT the
    // synthesis thread (per framework docs).
    // --------------------------------------------------------------------------

    override fun onStop() {
        Log.d(TAG, "onStop called from thread ${Thread.currentThread().name}")
        // Cancel the in-flight job: the synthesis thread unblocks at its
        // next suspension point (read* / await*) with a CancellationException.
        currentJob?.cancel()
    }

    // --------------------------------------------------------------------------
    // Synthesis entry point.
    //
    // Per the framework contract:
    //  - called on the synthesis thread,
    //  - must BLOCK until the utterance is finished (done()/error() done),
    //  - start()/audioAvailable()/done()/error() must all be called on this
    //    same thread.
    //
    // runBlocking(job) runs the suspend Ktor code ON this thread and parks
    // the thread until the block returns. All continuations resume here, so
    // every callback invocation happens on the synthesis thread.
    // --------------------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            Log.e(TAG, "onSynthesizeText: Request or callback is null")
            return
        }
        val seq = nextSeq++
        val text = request.charSequenceText?.toString() ?: ""
        // D-level: utterance text is user content and must not be logged at
        // info/warning (would be readable in logcat on shared devices).
        Log.d(TAG, "onSynthesizeText seq=$seq: len=${text.length}, thread=${Thread.currentThread().name}, text='${text.take(40)}'")

        val job = Job()
        currentJob = job
        try {
            runBlocking(job) {
                synthesize(request, callback, seq)
            }
        } catch (ce: CancellationException) {
            // Expected when onStop() cancelled the job: even if synthesize()
            // handled the cancellation internally, runBlocking() itself
            // rethrows the job's cancellation on exit. Nothing to do.
        } catch (t: Throwable) {
            // Top-level backstop: anything that escaped synthesize's own
            // catch blocks (e.g. a callback throwing from done()/error()).
            Log.e(TAG, "seq=$seq: unexpected exception escaped synthesis", t)
        } finally {
            if (currentJob === job) currentJob = null
        }
    }

    /**
     * Full synthesis for one utterance: settings -> HTTP -> stream to the
     * callback. Runs on the synthesis thread (inside runBlocking).
     *
     * The terminal-callback contract is enforced in finally{}: once
     * start() has been called (callback.hasStarted()), done() is guaranteed
     * to follow - normal completion, after error(), or after an external stop.
     */
    private suspend fun synthesize(request: SynthesisRequest, callback: SynthesisCallback, seq: Long) {
        // Settings first (parsed & validated in one place, BackendConfig.kt);
        // the utterance then carries cfg as immutable per-request context.
        val cfg = readBackendConfig(applicationContext)
        val u = Utterance(callback, seq, cfg)
        val text = request.charSequenceText?.toString() ?: ""

        try {
            // Text without any letter/digit: play silence clip instead of
            // sending it to the backend (or finish immediately if pause == 0).
            if (isSilentText(text)) {
                Log.d(TAG, "seq=$seq: text has no alphanumeric content, pauseMs=${cfg.blankLinePauseMs}")
                if (cfg.blankLinePauseMs > 0) {
                    // A real pause: play silence, done() follows in finally.
                    if (callback.start(cfg.pcmRate, cfg.pcmEncoding, cfg.pcmChannels) == TextToSpeech.ERROR) {
                        Log.w(TAG, "seq=$seq: silence start rejected by the client, aborting (no error reported)")
                    } else {
                        feedSilence(u, cfg.blankLinePauseMs)
                    }
                }
                // No pause -> no audio at all: no start()/done() for this utterance.
                return
            }

            val androidRate = request.speechRate.toFloat().coerceIn(20f, 300f)
            val openAiSpeed = androidRate / 100.0f

            if (cfg.backendUrl.isBlank()) {
                Log.e(TAG, "seq=$seq: backend URL is missing in settings.")
                u.fail(TextToSpeech.ERROR_SERVICE)
                return
            }
            val client = httpClient
            if (client == null) {
                Log.e(TAG, "seq=$seq: HTTP client is gone (service destroyed?)")
                u.fail(TextToSpeech.ERROR_SERVICE)
                return
            }

            Log.d(TAG, "seq=$seq: Processing: URL=${cfg.backendUrl}, Model=${cfg.model}, Voice=${cfg.voice}, Format=${cfg.responseFormat}, Speed=$openAiSpeed")

            // --- HTTP request: preparePost + execute for streaming ---
            // post() would only return after the complete body has been received;
            // the execute block, in contrast, runs as soon as the body starts
            // arriving.
            var body: ByteReadChannel? = null
            try {
                client.preparePost(cfg.backendUrl) {
                    if (cfg.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${cfg.apiKey}")
                    contentType(ContentType.Application.Json)
                    // Built by hand (instead of a data class) so optional
                    // fields like "seed" can be left out of the JSON entirely.
                    setBody(
                        buildJsonObject {
                            put("model", cfg.model)
                            put("input", text)
                            put("voice", cfg.voice)
                            put("response_format", cfg.responseFormat)
                            put("speed", openAiSpeed.toDouble())
                            put("stream", cfg.streamMode)
                            cfg.seed?.let { put("seed", it) }
                            cfg.temperature?.let { put("temperature", it) }
                        }
                    )
                }.execute { response ->
                    Log.d(TAG, "seq=$seq: Backend response status: ${response.status}")

                    if (!response.status.isSuccess()) {
                        // HTTP-level failure: the body is a short error message,
                        // so it is fine to read it completely here (the stream
                        // below is never started for this response).
                        val errorBody = try { response.bodyAsText() } catch (_: Exception) { "<unreadable>" }
                        Log.e(TAG, "seq=$seq: Backend request failed: Status=${response.status}, Body='$errorBody'")
                        u.fail(TextToSpeech.ERROR_NETWORK)
                        return@execute
                    }

                    // --- STREAM the response (do not buffer it completely!) ---
                    val b: ByteReadChannel = response.body()
                    body = b

                    when (cfg.responseFormat) {
                        "pcm" -> feedPcm(b, u)
                        "wav" -> feedWav(b, u)
                        else -> {
                            Log.e(TAG, "seq=$seq: Unsupported audio format: ${cfg.responseFormat} (mp3/opus not implemented)")
                            u.fail(TextToSpeech.ERROR_INVALID_REQUEST)
                        }
                    }
                }
            } finally {
                // Guarantee: the response body is ALWAYS closed, on every path
                // (normal EOF, abort, HTTP error, exception, external stop).
                closeBody(body)
            }
            // Normal completion (EOF) -> finally: done()
        } catch (e: CancellationException) {
            // External stop: onStop()/onDestroy() cancelled the job, or the
            // Ktor channel cancel (closeBody) surfaced from readAvailable -
            // same class on the JVM (kotlinx.coroutines.CancellationException
            // is a typealias of java.util.concurrent.CancellationException).
            // No error(); done() is still guaranteed by the finally block.
            // Log identity + origin so we can tell a job.cancel() CancellationException
            // apart from our own closeBody() one (message="body closed early") or a
            // cancel surfacing out of a Ktor suspension.
            Log.w(TAG, "seq=$seq: caught CancellationException class=${e::class.simpleName} message='${e.message}'")
            Log.w(TAG, "seq=$seq: CancellationException origin stack:\n${e.stackTraceToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "seq=$seq: exception during synthesis", e)
            u.fail(mapErrorCode(e))
        } finally {
            u.finish() // guarantees done() after start(), on every path
        }
    }

    /**
     * Feeds header-less raw PCM (parameters from settings, must match the
     * backend): start -> optional pre-buffer silence -> stream to EOF.
     */
    private suspend fun feedPcm(body: ByteReadChannel, u: Utterance) {
        u.sampleRate = u.cfg.pcmRate
        u.sampleBytes = u.cfg.pcmSampleBytes
        u.encoding = u.cfg.pcmEncoding
        u.numChannels = u.cfg.pcmChannels
        Log.d(TAG, "seq=${u.seq}: PCM params from settings: rate=${u.cfg.pcmRate}, channels=${u.cfg.pcmChannels}, bits=${u.cfg.pcmBits}")

        if (u.start() != 0) return

        // A failed feed below means the CLIENT stopped/flushed the utterance
        // (audioAvailable -> ERROR) or we were stopped externally. Both are
        // client-side signals: do NOT report an error() for them (error() is
        // only for our own synthesis failures, e.g. network).
        // (The optional pre-buffer silence is applied at the top of feedStream.)
        if (!feedStream(body, u)) {
            Log.d(TAG, "seq=${u.seq}: feed aborted (client stop/flush or external stop) - no error reported")
            return
        }
    }

    /**
     * Feeds a WAV stream: read the header incrementally (nothing buffered),
     * start with the header's parameters, optional pre-buffer, then hand the
     * rest of the body to the same streaming code as the raw PCM path.
     */
    private suspend fun feedWav(body: ByteReadChannel, u: Utterance) {
        // 1) Read the header incrementally (WavStream.readWavHeader): blocking,
        //    exactly as many bytes as each part needs, nothing buffered. On
        //    success the stream sits at the first PCM byte of the data chunk.
        var dataSize = -1L
        val wavInfo: WavHeaderInfo
        when (val read = readWavHeader(body) { currentJob?.isActive == true }) {
            is WavHeaderRead.Aborted -> {
                Log.d(TAG, "seq=${u.seq}: external stop while reading WAV header - no error reported")
                return
            }
            is WavHeaderRead.StreamEnded -> {
                Log.e(TAG, "seq=${u.seq}: WAV stream ended before header was complete.")
                u.fail(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            is WavHeaderRead.Malformed -> {
                Log.e(TAG, "seq=${u.seq}: malformed WAV header.")
                u.fail(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            is WavHeaderRead.UnsupportedFormat -> {
                Log.e(TAG, "seq=${u.seq}: unsupported WAV format: ${read.bits}-bit, ${read.channels} ch (need 16-bit or 8-bit, 1-2 channels)")
                u.fail(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            is WavHeaderRead.Ready -> {
                wavInfo = read.info
                dataSize = read.dataSize
            }
        }

        u.sampleRate = wavInfo.sampleRate
        u.numChannels = wavInfo.numChannels
        u.encoding = wavInfo.androidEncoding
        // Bytes per sample = channels * bitsPerSample / 8
        u.sampleBytes = u.numChannels * (wavInfo.bitsPerSample / 8).coerceAtLeast(1)

        Log.d(TAG, "seq=${u.seq}: WAV header: Rate=${wavInfo.sampleRate}, Encoding=${wavInfo.androidEncoding}, Channels=${wavInfo.numChannels}, sampleBytes=$u.sampleBytes, dataSize=$dataSize")

        if (u.start() != 0) return

        // 2) Stream the body from where it sits (the data chunk payload)
        //    The 'data' chunk size is authoritative when known: never feed more than dataSize
        //    PCM bytes (chunks AFTER 'data', e.g. a trailing LIST, must not be
        //    interpreted as audio). dataSize <= 0 (0 or 0xFFFFFFFF) means
        //    unknown (streaming WAV) -> feed until EOF.
        val sizeLimit = if (dataSize > 0) dataSize else Long.MAX_VALUE
        Log.d(TAG, "seq=${u.seq}: WAV data limit: ${if (sizeLimit == Long.MAX_VALUE) "unknown (EOF)" else "$sizeLimit bytes"}")

        if (!feedStream(body, u, sizeLimit)) {
            Log.d(TAG, "seq=${u.seq}: feed aborted (client stop/flush or external stop) - no error reported")
            return
        }
    }

    private fun mapErrorCode(e: Exception): Int = when (e) {
        is io.ktor.client.plugins.ClientRequestException -> TextToSpeech.ERROR_NETWORK
        is io.ktor.client.plugins.ServerResponseException -> TextToSpeech.ERROR_NETWORK
        is io.ktor.client.plugins.RedirectResponseException -> TextToSpeech.ERROR_NETWORK
        is java.net.UnknownHostException -> TextToSpeech.ERROR_NETWORK
        is java.net.ConnectException -> TextToSpeech.ERROR_NETWORK_TIMEOUT
        is java.net.SocketTimeoutException -> TextToSpeech.ERROR_NETWORK_TIMEOUT
        is java.io.IOException -> TextToSpeech.ERROR_NETWORK
        is kotlinx.serialization.SerializationException -> TextToSpeech.ERROR_INVALID_REQUEST
        else -> TextToSpeech.ERROR_SERVICE
    }

    /**
     * Feeds [ms] milliseconds of silence (zero bytes) in the assumed PCM
     * format. Used for the "pause on blank line" feature.
     * @return true if all silence was delivered, false if the feed was
     *         aborted (client stop/flush or external stop).
     */
    private suspend fun feedSilence(u: Utterance, ms: Int): Boolean {
        val totalBytes = (u.sampleRate.toLong() * u.sampleBytes * ms / 1000L).toInt()
        // Keep every chunk aligned to a whole number of samples.
        val bufSize = u.callback.getMaxBufferSize().alignDown(u.sampleBytes)
        val buf = ByteArray(bufSize) // all zeros = silence
        var remaining = totalBytes
        while (remaining > 0) {
            val delivered = feedAudio(u, buf, 0, minOf(remaining, buf.size), "silence")
            if (delivered < 0) return false
            remaining -= delivered
        }
        Log.d(TAG, "seq=${u.seq}: silence delivered ($ms ms, $totalBytes bytes)")
        return true
    }

    /**
     * Plays [len] bytes from [offset] of [data] out via audioAvailable(),
     * split into pieces of at most getMaxBufferSize().
     * @return number of bytes delivered, or -1 on error/abort.
     */
    private fun feedAudio(
        u: Utterance,
        data: ByteArray,
        offset: Int,
        len: Int,
        tag: String
    ): Int {
        var off = 0
        var delivered = 0
        while (off < len) {
            if (currentJob?.isActive == false) {
                Log.d(TAG, "seq=${u.seq} [STOP]: feedAudio job inactive (currentJob.isActive=false) -> -1")
                return -1
            }
            // hasFinished(): the client already finished with this callback.
            if (u.callback.hasFinished()) {
                Log.w(TAG, "$tag: UNEXPECTED hasFinished()==true before audioAvailable (off=${offset + off}/$len) - aborting feed.")
                return -1
            }
            val max = u.callback.getMaxBufferSize()
            val chunkLen = if (max > 0) minOf(max, len - off) else len - off
            val r = u.callback.audioAvailable(data, offset + off, chunkLen)
            // Log.d(TAG, "$tag: audioAvailable(off=${offset + off}, len=$chunkLen) -> $r")
            if (r == TextToSpeech.ERROR) {
                // The CLIENT stopped/flushed the utterance. This is a client-side
                // signal, NOT a synthesis error - the caller must not report
                // error() for it (that makes the framework announce a failure and
                // kill the TTS session).
                Log.w(TAG, "seq=${u.seq} [STOP]: feedAudio audioAvailable returned ERROR (client stopped/flushed) -> -1")
                return -1
            }
            off += chunkLen
            delivered += chunkLen
        }
        return delivered
    }

    /**
     * Streams the response body to the framework callback until EOF or stop.
     *
     * Alignment contract: every audioAvailable() slice is a multiple of the
     * sample size ([sampleBytes] = channels * bytesPerChannel) and starts at
     * a sample boundary. A misaligned slice shifts the sample phase and turns
     * the rest of the audio into white noise.
     *
     * Pre-buffer: if streaming is on and a buffering delay is configured, a
     * silence clip is played before the real audio to absorb
     * start-of-stream latency spikes.
     *
     * @return true if the stream was delivered till EOF or data limit, false on stop/abort.
     */
    private suspend fun feedStream(
        body: ByteReadChannel,
        u: Utterance,
        remaining: Long = Long.MAX_VALUE // max PCM bytes we may feed (WAV dataSize); MAX = unknown/EOF
    ): Boolean {
        var rem = remaining // mutable copy (parameters are val)
        val frameworkMax = u.callback.getMaxBufferSize()
        val maxRead = frameworkMax.alignDown(u.sampleBytes) // whole samples only, <= frameworkMax
        val buf = ByteArray(maxRead)
        var chunkIdx = 0

        Log.d(TAG, "seq=${u.seq}: feedStream start: maxBufferSize=$frameworkMax, readSize=$maxRead, sampleBytes=${u.sampleBytes}, sampleRate=${u.sampleRate}")

        // Configurable delay to build some buffer.
        // Helpful for slow (RTF~1.0) streaming servers.
        // Delay is measured from when request was sent, to avoid
        // adding delay to servers that ignore request to stream data.
        if (u.cfg.streamMode && u.cfg.bufferingDelayMs > 0) {
            val elapsedMs = SystemClock.elapsedRealtime() - u.requestStartMs
            val prebufferMs = (u.cfg.bufferingDelayMs - elapsedMs).coerceAtLeast(0L)
            Log.d(TAG, "seq=${u.seq}: pre-buffer: configured=${u.cfg.bufferingDelayMs} ms, request took=$elapsedMs ms, silence to play=$prebufferMs ms")
            if (prebufferMs > 0L && !feedSilence(u, prebufferMs.toInt())) {
                Log.d(TAG, "seq=${u.seq}: pre-buffer aborted (client stop/flush or external stop)")
                return false
            }
        }
        // awaitContent(sampleBytes) in the loop condition guarantees availableForRead >=
        // sampleBytes inside the body: we only ever read whole samples (a partial slice
        // would shift the sample phase into white noise).
        // It suspends for the network, returns false at channel close, and throws
        // CancellationException on stop.
        while (rem >= u.sampleBytes && body.awaitContent(u.sampleBytes)) {
            // Stop check at the loop head (fast abort between chunks).
            if (currentJob?.isActive == false) {
                Log.d(TAG, "seq=${u.seq} [STOP]: feedStream loop-head job inactive -> exit")
                return false
            }

            // Read a whole number of samples: as much as is buffered, capped by the
            // framework buffer limit and the dataSize limit. (bytesToRead >= sampleBytes.)
            val bytesToRead = minOf(body.availableForRead, minOf(maxRead.toLong(), rem).toInt()).alignDown(u.sampleBytes)
            // readAvailable does not always read full requested amount, even when it's within availableForRead.
            body.readFully(buf, 0, bytesToRead)
            chunkIdx++
            // Log.d(TAG, "seq=${u.seq}: read bytesRead=$bytesRead from network (bytesToRead=$bytesToRead, available=$available)")
            if (feedAudio(u, buf, 0, bytesToRead, "chunk#$chunkIdx") < 0) return false
            rem -= bytesToRead
        }

        Log.d(TAG, "seq=${u.seq}: stream complete after $chunkIdx chunks (rem=$rem, dropped ${body.availableForRead} leftover byte(s))")
        return true
    }

    /**
     * Safely closes a response body that may have been abandoned early
     * (stop / timeout / error). CIO closes the socket with this.
     */
    private fun closeBody(body: ByteReadChannel?) {
        if (body == null) return
        try {
            body.cancel(CancellationException("body closed early"))
        } catch (t: Throwable) {
            Log.e(TAG, "closeBody: cancel failed", t)
        }
    }
}
