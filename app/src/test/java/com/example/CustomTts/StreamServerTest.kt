package com.example.CustomTts

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.io.EOFException
import kotlinx.io.readByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * In-process HTTP server tests: a raw loopback [ServerSocket] writes the
 * response bytes, the real CIO client reads them back through a
 * [ByteReadChannel]. No device, no network - and the handler has full control
 * over the exact bytes AND their timing (which is what the timeout tests need).
 */
class StreamServerTest {

    /**
     * Starts a one-shot loopback HTTP server on a random free port. [handler]
     * is handed the connected [Socket] once the client connects and writes the
     * raw HTTP response itself. [block] runs with the port while the server is
     * up; the server is torn down afterwards (best-effort).
     */
    private fun withServer(handler: (Socket) -> Unit, block: (Int) -> Unit) {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val acceptor = thread(name = "test-http-server", start = true) {
            runCatching {
                val socket = serverSocket.accept()
                try {
                    handler(socket)
                } finally {
                    runCatching { socket.close() }
                }
            }
        }
        try {
            block(port)
        } finally {
            runCatching { serverSocket.close() }
            runCatching { acceptor.interrupt() }
            runCatching { acceptor.join(2_000) }
        }
    }

    /** Writes a minimal HTTP/1.1 200 response (headers + [body]) over [socket]. */
    private fun writeResponse(socket: Socket, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        val out = socket.getOutputStream()
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    /**
     * Sends the response [headers] (promising [body] via Content-Length),
     * PAUSES [pauseMs] ms, then sends the body and lets [withServer] close the
     * connection. The pause forces the receiver's blocking read to genuinely
     * suspend (no body bytes buffered yet) before the body arrives - the
     * realistic streaming case.
     */
    private fun headerPauseThenBody(socket: Socket, body: ByteArray, pauseMs: Long) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        val out = socket.getOutputStream()
        out.write(header.toByteArray())
        out.flush()
        Thread.sleep(pauseMs)
        out.write(body)
        out.flush()
    }

    /**
     * Sends response headers promising [promisedBytes] bytes of body, flushes,
     * then STALLS [stallMs] ms without sending the body or closing the socket -
     * a silent server. [withServer] closes the connection after the handler
     * returns (or earlier when the acceptor thread is interrupted).
     */
    private fun headerThenStall(socket: Socket, promisedBytes: Int, stallMs: Long) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: $promisedBytes\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        val out = socket.getOutputStream()
        out.write(header.toByteArray())
        out.flush()
        Thread.sleep(stallMs)
    }

    /** A CIO client bounded by short timeouts so a bad handshake fails fast, never hangs. */
    private fun client(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
        }
    }

    @Test
    fun inProcessServerServesBytes_andClientReadsThemAll() {
        val payload = ByteArray(5_000) { (it * 31).toByte() }

        withServer(handler = { socket -> writeResponse(socket, payload) }) { port ->
            runBlocking {
                val client = client()
                try {
                    val channel: ByteReadChannel = client.get("http://127.0.0.1:$port/").bodyAsChannel()
                    val got = channel.readRemaining().readByteArray()
                    assertArrayEquals(payload, got)
                } finally {
                    client.close()
                }
            }
        }
    }

    // =========================================================================
    // DISCOVERY TEMPLATE
    //
    // One focused scenario per test, asserting OBSERVABLE behaviour (what
    // returns when, what throws, how it reacts to stall/timeout/cancellation).
    // Adjust the server handler + the receiver call to probe whatever specific
    // behaviour we care about next. The library may change; the point is to pin
    // down what actually happens.
    // =========================================================================

    /**
     * Server: headers, PAUSE, then 3 bytes + close. Receiver: `awaitContent(4)`.
     *
     * Expected (to confirm): returns `false` (only 3 bytes ever arrive, then the
     * channel closes) and throws nothing (clean EOF, not an error).
     *
     * Uses `prepareGet().execute {}` so the request returns right after the
     * response HEADERS and the body is a lazy streaming channel - the same
     * pattern the service uses (`preparePost().execute`) to stream.
     */
    @Test
    fun template_awaitContent4_afterHeaderPauseThen3BytesClose() {
        val pauseMs = 500L
        var headerReturnMs = -1L // set from inside the execute block (= when headers were received)
        withServer(handler = { socket -> headerPauseThenBody(socket, ByteArray(3), pauseMs) }) { port ->
            runBlocking {
                val client = client()
                try {
                    val t0 = System.nanoTime()
                    val result: Boolean = client.prepareGet("http://127.0.0.1:$port/").execute { response ->
                        headerReturnMs = (System.nanoTime() - t0) / 1_000_000 // time to get headers
                        response.bodyAsChannel().awaitContent(4) // suspends ~pauseMs
                    }
                    val totalMs = (System.nanoTime() - t0) / 1_000_000
                    val bodyWaitMs = totalMs - headerReturnMs
                    println("streaming: headerReturn=${headerReturnMs}ms bodyWait=${bodyWaitMs}ms total=${totalMs}ms result=$result")
                    assertFalse(
                        "awaitContent(4) expected false: only 3 bytes arrived, then the channel closed (clean EOF)",
                        result
                    )
                    // STREAMING proof: execute() returned right after the HEADERS (fast), and the
                    // body took ~pauseMs to arrive. If execute() had blocked until the body was
                    // fully buffered, headerReturn would be ~pauseMs and bodyWait ~0.
                    assertTrue(
                        "execute should return after headers (fast), got ${headerReturnMs}ms",
                        headerReturnMs < pauseMs / 2
                    )
                    assertTrue(
                        "body should stream in over ~${pauseMs}ms (the server pause), got ${bodyWaitMs}ms",
                        bodyWaitMs in (pauseMs / 2)..(pauseMs + 1_000)
                    )
                } finally {
                    client.close()
                }
            }
        }
    }

    /**
     * Server: headers, PAUSE, then 3 bytes + close. Receiver: `readFully(buf, 0, 10)`.
     *
     * Expected (to confirm): throws `kotlinx.io.EOFException` - the channel is
     * closed with fewer bytes (3) than were requested (10).
     */
    @Test
    fun template_readFully10_afterHeaderPauseThen3BytesClose_throwsEof() {
        val pauseMs = 500L
        withServer(handler = { socket -> headerPauseThenBody(socket, ByteArray(3), pauseMs) }) { port ->
            runBlocking {
                val client = client()
                try {
                    val buf = ByteArray(10)
                    val t0 = System.nanoTime()
                    val thrown = runCatching {
                        client.prepareGet("http://127.0.0.1:$port/").execute { response ->
                            response.bodyAsChannel().readFully(buf, 0, 10)
                        }
                    }
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    val ex = thrown.exceptionOrNull()
                    println("readFully-EOF: exception=${ex?.javaClass?.simpleName}: ${ex?.message} elapsed=${elapsedMs}ms")
                    assertTrue("readFully(10) on a 3-byte stream should throw EOFException, but got: $ex", ex is EOFException)
                    // The EOF only becomes known after the server pauses + sends 3 bytes + closes,
                    // so it should arrive ~pauseMs later (not instantly, not much later).
                    assertTrue(
                        "EOF should arrive ~${pauseMs}ms after the request (the server pause), got ${elapsedMs}ms",
                        elapsedMs in (pauseMs / 2)..(pauseMs + 1_000)
                    )
                } finally {
                    client.close()
                }
            }
        }
    }

    /**
     * Server: headers, then STALLS (silent, never closes). Receiver: `awaitContent(100)`
     * in a launched job, which we cancel while it is suspended.
     *
     * Expected (to confirm): the suspended read unblocks and the job completes
     * cancelled (with a CancellationException) - the mechanism the service's stop
     * relies on (cancel the job to unblock the network read).
     */
    @Test
    fun template_suspendedAwaitContent_unblocksOnJobCancellation() {
        val suspendMs = 500L
        var innerEx: Throwable? = null // at the awaitContent site (inside the execute block)
        var innerElapsedMs = -1L
        var outerEx: Throwable? = null // around prepareGet.execute (a different source)
        var outerElapsedMs = -1L
        withServer(handler = { socket -> headerThenStall(socket, promisedBytes = 100, stallMs = 5_000) }) { port ->
            runBlocking {
                val client = client()
                val scope = CoroutineScope(Dispatchers.Default)
                try {
                    val job = scope.launch {
                        val outerT0 = System.nanoTime()
                        try {
                            client.prepareGet("http://127.0.0.1:$port/").execute { response ->
                                val innerT0 = System.nanoTime()
                                try {
                                    response.bodyAsChannel().awaitContent(100) // suspends until cancelled
                                } catch (e: Throwable) {
                                    innerEx = e
                                    innerElapsedMs = (System.nanoTime() - innerT0) / 1_000_000
                                    // Do NOT rethrow: the outer exception is a different source
                                    // (execute's own mechanism), so swallow here to observe both.
                                }
                            }
                        } catch (e: Throwable) {
                            outerEx = e
                            outerElapsedMs = (System.nanoTime() - outerT0) / 1_000_000
                        }
                    }
                    delay(suspendMs) // let the job reach the suspended read, then cancel it
                    job.cancelAndJoin()
                    println(
                        "cancellation: inner=${innerEx?.javaClass?.simpleName}@${innerElapsedMs}ms " +
                            "outer=${outerEx?.javaClass?.simpleName}@${outerElapsedMs}ms isCancelled=${job.isCancelled}"
                    )
                    // (a) awaitContent itself threw (unblocked with an exception) after ~suspendMs.
                    assertNotNull("awaitContent should throw when the job is cancelled", innerEx)
                    assertTrue(
                        "awaitContent should suspend ~${suspendMs}ms then unblock, got ${innerElapsedMs}ms",
                        innerElapsedMs in (suspendMs - 200)..(suspendMs + 1_500)
                    )
                    // (b) the cancellation ALSO surfaces from around execute (a different source
                    //     than the one at the awaitContent site) - as the service observed.
                    assertNotNull("execute should also surface a cancellation exception", outerEx)
                    assertTrue("job should end cancelled", job.isCancelled)
                } finally {
                    client.close()
                    scope.cancel()
                }
            }
        }
    }

    /**
     * Server: headers, then STALLS (silent, never closes) far longer than the client's
     * socket timeout. Receiver: `awaitContent(100)` on a client with a short
     * `socketTimeoutMillis`.
     *
     * Discovery goal: does CIO's socket timeout break a suspended channel read?
     *  - elapsed ~ 800ms  -> socket timeout FIRES (the read throws, e.g. IOException)
     *  - elapsed ~ 4000ms -> it did NOT fire; the 4s coroutine backstop
     *                        (withTimeoutOrNull) ended the read (returns null)
     * Either way the test completes in bounded time (never hangs the build).
     */
    @Test
    fun template_stalledServer_vs_socketTimeout() {
        val socketTimeoutMs = 800L
        var awaitEx: Throwable? = null // captured at the awaitContent site (inside the execute block)
        val c = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = socketTimeoutMs
                requestTimeoutMillis = 15_000 // high: isolate the socket timeout
            }
        }
        withServer(handler = { socket -> headerThenStall(socket, promisedBytes = 100, stallMs = 6_000) }) { port ->
            runBlocking {
                val start = System.nanoTime()
                val outer = runCatching {
                    withTimeoutOrNull(4_000) {
                        c.prepareGet("http://127.0.0.1:$port/").execute { response ->
                            try {
                                response.bodyAsChannel().awaitContent(100) // suspended; socket timeout fires
                            } catch (e: Throwable) {
                                awaitEx = e // captured at the awaitContent site (do not rethrow)
                            }
                        }
                    }
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                val outerEx = outer.exceptionOrNull()
                println(
                    "socket-timeout: elapsed=${elapsedMs}ms awaitEx=${awaitEx?.javaClass?.simpleName}: " +
                        "${awaitEx?.message} outer=${if (outer.isSuccess) "ok" else outerEx?.javaClass?.simpleName}"
                )
                // The read must throw at the awaitContent site, and at roughly the configured
                // socketTimeoutMs (NOT the 4000ms coroutine backstop).
                assertNotNull("awaitContent should throw when the socket times out", awaitEx)
                assertTrue(
                    "socket timeout should fire ~${socketTimeoutMs}ms (not the 4000ms backstop), got ${elapsedMs}ms",
                    elapsedMs in (socketTimeoutMs - 100)..(socketTimeoutMs + 3_000)
                )
                c.close()
            }
        }
    }
}
