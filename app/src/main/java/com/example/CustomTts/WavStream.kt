package com.example.CustomTts

import android.media.AudioFormat
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discardExact
import io.ktor.utils.io.readFully
import kotlinx.io.EOFException

/**
 * Incremental WAV header reader.
 *
 * Reads the header from the response stream in blocking mode, requesting
 * exactly as many bytes as each part needs (12-byte RIFF/WAVE prefix, 8-byte
 * chunk headers, the fmt payload, skips for unknown chunks). On success the
 * stream is positioned right at the first PCM byte of the "data" chunk and
 * NOTHING has been buffered by this code - the rest of the response is then
 * fed to the same streaming code as a raw PCM stream.
 */

internal const val MAX_WAV_CHUNK_SIZE = 4 * 1024 * 1024L

private val RIFF_WAVE = byteArrayOf(
    'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
    0, 0, 0, 0, // size (ignored)
    'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()
)

/**
 * Sample parameters from the fmt chunk plus a playability gate.
 * The stream is 8/16-bit PCM only, 1-2 channels - everything the
 * audioAvailable() contract can express.
 */
internal data class WavHeaderInfo(
    val sampleRate: Int,
    val numChannels: Int,
    val bitsPerSample: Int,
    val androidEncoding: Int,
    val supported: Boolean
)

/** Outcome of reading a complete WAV header from [ByteReadChannel]. */
internal sealed interface WavHeaderRead {
    /**
     * The fmt and data chunks were read. [dataSize] is the declared payload
     * size of the "data" chunk; <= 0 means unknown (0xFFFFFFFF or 0,
     * streaming WAV) -> feed until EOF.
     */
    data class Ready(val info: WavHeaderInfo, val dataSize: Long) : WavHeaderRead
    data object Malformed : WavHeaderRead
    data class UnsupportedFormat(val bits: Int, val channels: Int) : WavHeaderRead
    data object StreamEnded : WavHeaderRead
    data object Aborted : WavHeaderRead
}

/** Pure fmt-chunk parser (unit-tested). Returns null when incomplete. */
internal fun parseFmtChunk(fmt: ByteArray): WavHeaderInfo? {
    if (fmt.size < 16) return null
    val channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
    val rate = (fmt[4].toInt() and 0xFF) or ((fmt[5].toInt() and 0xFF) shl 8) or
        ((fmt[6].toInt() and 0xFF) shl 16) or ((fmt[7].toInt() and 0xFF) shl 24)
    val bits = fmt[14].toInt() and 0xFF or ((fmt[15].toInt() and 0xFF) shl 8)
    if (channels < 1 || rate <= 0) return null

    // audioAvailable() accepts ENCODING_PCM_16BIT / ENCODING_PCM_8BIT /
    // ENCODING_DEFAULT. There is deliberately NO 32-bit float path:
    // 8-bit is rare but mappable, 32-bit is rejected.
    val androidEncoding = when (bits) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        8 -> AudioFormat.ENCODING_PCM_8BIT
        else -> 0 // unsupported (32-bit float etc.)
    }
    // >2 channels: we cannot reliably convert them to a supported layout.
    return WavHeaderInfo(rate, channels, bits, androidEncoding, bits != 32 && channels <= 2)
}

/**
 * Reads the complete header (RIFF/WAVE + fmt + skip-chunks up to "data").
 *
 * Only the bytes of the current part are ever read, in a blocking loop
 * (waits for the network when nothing is available yet). [isAlive] must
 * report whether the utterance is still running; it is checked between
 * chunks so a client stop does not wait for more header bytes.
 */
internal suspend fun readWavHeader(body: ByteReadChannel, isAlive: () -> Boolean): WavHeaderRead {
    val riff = readExactly(body, 12) ?: return WavHeaderRead.StreamEnded
    if (riff.size != 12) return WavHeaderRead.Malformed
    for (i in 0 until 12) {
        // The RIFF size field (bytes 4..7) is variable: compare around it.
        if (i != 4 && i != 5 && i != 6 && i != 7 && riff[i] != RIFF_WAVE[i]) return WavHeaderRead.Malformed
    }

    var fmt: WavHeaderInfo? = null
    while (true) {
        if (!isAlive()) return WavHeaderRead.Aborted
        val h = readExactly(body, 8) ?: return WavHeaderRead.StreamEnded
        val id0 = h[0].toInt(); val id1 = h[1].toInt(); val id2 = h[2].toInt(); val id3 = h[3].toInt()
        val size = (h[4].toInt() and 0xFF) or ((h[5].toInt() and 0xFF) shl 8) or
            ((h[6].toInt() and 0xFF) shl 16) or ((h[7].toInt() and 0xFF) shl 24)

        val id = when {
            id0 == 'f'.code && id1 == 'm'.code && id2 == 't'.code && id3 == ' '.code -> "fmt"
            id0 == 'd'.code && id1 == 'a'.code && id2 == 't'.code && id3 == 'a'.code -> "data"
            else -> "skip"
        }
        when (id) {
            "fmt" -> {
                if (size < 16) return WavHeaderRead.Malformed
                val f = readExactly(body, size) ?: return WavHeaderRead.StreamEnded
                val info = parseFmtChunk(f) ?: return WavHeaderRead.Malformed
                if (!info.supported) {
                    return WavHeaderRead.UnsupportedFormat(info.bitsPerSample, info.numChannels)
                }
                fmt = info
            }
            "data" -> {
                // 0xFFFFFFFF (-1) means "to EOF" (streaming WAV): allowed here only.
                if (size < -1 || size > MAX_WAV_CHUNK_SIZE) return WavHeaderRead.Malformed
                // No data bytes are read here: the stream now sits at the
                // start of the PCM payload and the caller feeds it on.
                return if (fmt != null) {
                    WavHeaderRead.Ready(fmt, if (size < 0) -1L else size.toLong())
                } else {
                    WavHeaderRead.Malformed
                }
            }
            else -> {
                // Unknown chunk before data (e.g. a leading LIST/fact): skip it.
                if (size < 0) return WavHeaderRead.Malformed
                try {
                    body.discardExact(size.toLong())
                } catch (e: EOFException) {
                    return WavHeaderRead.StreamEnded
                }
            }
        }
        // RIFF chunks are word-aligned: odd-sized payloads carry a pad byte.
        if (size % 2L == 1L && readExactly(body, 1) == null) return WavHeaderRead.StreamEnded
    }
}

/**
 * Blocking read of exactly [n] bytes: suspends until the network delivers
 * them. Returns null when the stream ends before all bytes arrive.
 */
private suspend fun readExactly(body: ByteReadChannel, n: Int): ByteArray? {
    val out = ByteArray(n)
    try {
        body.readFully(out, 0, n)
        return out
    } catch (e: EOFException) {
        return null
    }
}
