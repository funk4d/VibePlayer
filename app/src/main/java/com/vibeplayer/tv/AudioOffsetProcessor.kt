package com.vibeplayer.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Applies a small user-selected A/V correction in decoded PCM.
 *
 * Positive values prepend silence so audio is heard later. Negative values discard the same
 * amount of PCM after each stream start/seek so audio is heard earlier. The processor is rebuilt
 * with the player when the setting changes; zero remains a true pass-through path.
 */
@UnstableApi
internal class AudioOffsetProcessor : BaseAudioProcessor() {
    @Volatile
    var offsetMs: Int = 0
        set(value) {
            field = value.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        }

    private var configuredOffsetMs = 0
    private var pendingSilenceBytes = 0
    private var pendingDropBytes = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredOffsetMs = offsetMs
        if (configuredOffsetMs == 0) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        max(0L, durationUs + configuredOffsetMs * 1_000L)

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (pendingDropBytes > 0) {
            val dropBytes = minOf(inputBuffer.remaining(), pendingDropBytes)
            inputBuffer.position(inputBuffer.position() + dropBytes)
            pendingDropBytes -= dropBytes
            if (!inputBuffer.hasRemaining()) return
        }

        val output = replaceOutputBuffer(pendingSilenceBytes + inputBuffer.remaining())
        while (pendingSilenceBytes >= Long.SIZE_BYTES) {
            output.putLong(0L)
            pendingSilenceBytes -= Long.SIZE_BYTES
        }
        while (pendingSilenceBytes > 0) {
            output.put(0)
            pendingSilenceBytes--
        }
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val offsetFrames = inputAudioFormat.sampleRate.toLong() * abs(configuredOffsetMs) / 1_000L
        val offsetBytes = (offsetFrames * inputAudioFormat.bytesPerFrame).toInt()
        pendingSilenceBytes = if (configuredOffsetMs > 0) offsetBytes else 0
        pendingDropBytes = if (configuredOffsetMs < 0) offsetBytes else 0
    }

    override fun onReset() {
        configuredOffsetMs = 0
        pendingSilenceBytes = 0
        pendingDropBytes = 0
    }

    companion object {
        const val MAX_OFFSET_MS = 5_000
    }
}
