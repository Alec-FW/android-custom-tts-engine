package com.example.CustomTts

import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the WAV header code (WavStream.kt).
 *
 * - [parseFmtChunk]: pure parser, pinned directly.
 * - [readWavHeader]: incremental reader, exercised through a ByteReadChannel
 *   built from a byte array.
 */
class WavStreamTest {

    // --- little-endian builders -------------------------------------------

    private fun le16(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun le32(v: Long): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun chunkId(s: String): ByteArray = s.map { it.code.toByte() }.toByteArray()

    private fun fmtPayload(channels: Int, rate: Int, bits: Int): ByteArray {
        val byteRate = rate * channels * bits / 8
        return le16(1) + // audio format: PCM
            le16(channels) +
            le32(rate.toLong()) +
            le32(byteRate.toLong()) +
            le16(channels * bits / 8) + // block align
            le16(bits)
    }

    private fun wavBytes(
        channels: Int,
        rate: Int,
        bits: Int,
        dataSize: Int,
        withList: Boolean = false,
        pcm: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6)
    ): ByteArray {
        val fmt = chunkId("fmt ") + le32(16) + fmtPayload(channels, rate, bits)
        val middle = if (withList) {
            chunkId("LIST") + le32(8) +
                byteArrayOf(0x6a.toByte(), 0, 0, 0, 0x49, 0x54, 0x4d, 0x53)
        } else {
            ByteArray(0)
        }
        val data = chunkId("data") + le32(dataSize.toLong()) + pcm
        // The RIFF size field is 0: the reader does not validate it.
        return chunkId("RIFF") + le32(0) + chunkId("WAVE") + fmt + middle + data
    }

    private fun readHeader(bytes: ByteArray): WavHeaderRead =
        runBlocking { readWavHeader(bytes.inputStream().toByteReadChannel()) { true } }

    // --- parseFmtChunk (pure) ---------------------------------------------

    @Test
    fun fmtChunkParses() {
        val info = parseFmtChunk(fmtPayload(channels = 2, rate = 48000, bits = 16))!!
        assertEquals(48000, info.sampleRate)
        assertEquals(2, info.numChannels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(2, info.androidEncoding) // ENCODING_PCM_16BIT
        assertTrue(info.supported)
    }

    @Test
    fun fmtChunkEightBitIsSupported() {
        val info = parseFmtChunk(fmtPayload(channels = 1, rate = 8000, bits = 8))!!
        assertTrue(info.supported)
        assertEquals(3, info.androidEncoding) // ENCODING_PCM_8BIT
    }

    @Test
    fun fmtChunkThirtyTwoBitIsUnsupported() {
        val info = parseFmtChunk(fmtPayload(channels = 1, rate = 24000, bits = 32))!!
        assertFalse(info.supported)
    }

    @Test
    fun fmtChunkThreeChannelsIsUnsupported() {
        val info = parseFmtChunk(fmtPayload(channels = 3, rate = 24000, bits = 16))!!
        assertFalse(info.supported)
    }

    @Test
    fun fmtChunkTruncatedIsRejected() {
        val full = fmtPayload(channels = 1, rate = 24000, bits = 16)
        for (len in 1 until 16) {
            assertEquals("len=$len", null, parseFmtChunk(full.copyOf(len)))
        }
    }

    // --- readWavHeader (incremental) ---------------------------------------

    @Test
    fun minimalHeaderReadsReady() {
        val r = readHeader(wavBytes(channels = 2, rate = 48000, bits = 16, dataSize = 6))
        assertTrue("expected Ready, got $r", r is WavHeaderRead.Ready)
        val ready = r as WavHeaderRead.Ready
        assertEquals(48000, ready.info.sampleRate)
        assertEquals(2, ready.info.numChannels)
        assertEquals(6L, ready.dataSize) // the declared 'data' chunk payload size
    }

    @Test
    fun streamIsPositionedAtDataPayload() {
        val pcm = byteArrayOf(9, 9, 9, 9)
        val body = wavBytes(channels = 1, rate = 24000, bits = 16, dataSize = 4, pcm = pcm).inputStream().toByteReadChannel()
        runBlocking {
            val r = readWavHeader(body) { true }
            assertTrue(r is WavHeaderRead.Ready)
            val rest = ByteArray(pcm.size)
            val n = body.readAvailable(rest, 0, rest.size)
            assertEquals(4, n)
            assertEquals(pcm.toList(), rest.toList())
        }
    }

    @Test
    fun listChunkBeforeDataIsSkipped() {
        val r = readHeader(wavBytes(channels = 1, rate = 24000, bits = 16, dataSize = 4, withList = true))
        assertTrue("expected Ready, got $r", r is WavHeaderRead.Ready)
        assertEquals(4L, (r as WavHeaderRead.Ready).dataSize)
    }

    @Test
    fun unknownDataSizeMapsToMinusOne() {
        val r = readHeader(wavBytes(channels = 1, rate = 24000, bits = 16, dataSize = -1))
        assertTrue(r is WavHeaderRead.Ready)
        assertEquals(-1L, (r as WavHeaderRead.Ready).dataSize)
    }

    @Test
    fun streamEndInsideHeaderIsReported() {
        val full = wavBytes(channels = 1, rate = 24000, bits = 16, dataSize = 4)
        assertTrue(readHeader(full.copyOf(20)) is WavHeaderRead.StreamEnded)
        assertTrue(readHeader(full.copyOf(11)) is WavHeaderRead.StreamEnded)
    }

    @Test
    fun dataChunkBeforeFmtIsMalformed() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val h = chunkId("RIFF") + le32(0) + chunkId("WAVE") +
            chunkId("data") + le32(4) + pcm + chunkId("fmt ") + le32(16) + fmtPayload(1, 24000, 16)
        assertTrue(readHeader(h) is WavHeaderRead.Malformed)
    }

    @Test
    fun badRiffPrefixIsMalformed() {
        val h = wavBytes(channels = 1, rate = 24000, bits = 16, dataSize = 4)
        h[0] = 'X'.code.toByte()
        assertTrue(readHeader(h) is WavHeaderRead.Malformed)
    }

    @Test
    fun thirtyTwoBitFmtIsUnsupported() {
        val r = readHeader(wavBytes(channels = 1, rate = 24000, bits = 32, dataSize = 4))
        assertTrue("expected UnsupportedFormat, got $r", r is WavHeaderRead.UnsupportedFormat)
    }

    @Test
    fun abortIsReported() {
        val r = readHeader(wavBytes(channels = 1, rate = 24000, bits = 16, dataSize = 4))
        // With a live check it must be Ready...
        assertTrue(r is WavHeaderRead.Ready)
        val dead = runBlocking { readWavHeader(wavBytes(1, 24000, 16, 4).inputStream().toByteReadChannel()) { false } }
        assertTrue("expected Aborted, got $dead", dead is WavHeaderRead.Aborted)
    }
}
