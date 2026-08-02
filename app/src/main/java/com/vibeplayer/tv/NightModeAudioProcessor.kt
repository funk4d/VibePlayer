package com.vibeplayer.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lightweight linked-channel compressor for the TCL's analogue output.
 *
 * The TV firmware advertises Android DynamicsProcessing but rejects a valid configuration, so
 * night mode must run in the decoded PCM path. Keeping all channels linked avoids shifting the
 * stereo image and prevents surround channels from pumping independently before Android downmixes
 * them to the 3.5 mm output.
 */
@UnstableApi
internal class NightModeAudioProcessor(
    initiallyEnabled: Boolean,
) : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = initiallyEnabled

    private var envelope = 0f
    private var gain = 1f
    private var envelopeAttack = 0f
    private var envelopeRelease = 0f
    private var gainAttack = 0f
    private var gainRelease = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val sampleRate = inputAudioFormat.sampleRate.toFloat()
        envelopeAttack = coefficient(ENVELOPE_ATTACK_MS, sampleRate)
        envelopeRelease = coefficient(ENVELOPE_RELEASE_MS, sampleRate)
        gainAttack = coefficient(GAIN_ATTACK_MS, sampleRate)
        gainRelease = coefficient(GAIN_RELEASE_MS, sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val channelCount = inputAudioFormat.channelCount
        val output = replaceOutputBuffer(inputBuffer.remaining())

        while (inputBuffer.remaining() >= bytesPerFrame) {
            val frameStart = inputBuffer.position()
            var framePeak = 0f
            repeat(channelCount) { channel ->
                val sample = inputBuffer.getShort(frameStart + channel * Short.SIZE_BYTES).toInt()
                framePeak = max(framePeak, abs(sample / PCM_SCALE))
            }

            val envelopeCoefficient = if (framePeak > envelope) envelopeAttack else envelopeRelease
            envelope = framePeak + envelopeCoefficient * (envelope - framePeak)

            val targetGain = if (enabled) compressedGain(envelope) else 1f
            val gainCoefficient = if (targetGain < gain) gainAttack else gainRelease
            gain = targetGain + gainCoefficient * (gain - targetGain)

            repeat(channelCount) {
                val input = inputBuffer.short.toInt()
                val processed = (input * gain).roundToInt().coerceIn(-OUTPUT_CEILING, OUTPUT_CEILING)
                output.putShort(processed.toShort())
            }
        }

        // Media3 supplies frame-aligned PCM. Consume defensively if a vendor decoder violates that.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    override fun onFlush() {
        envelope = 0f
        gain = 1f
    }

    override fun onReset() {
        onFlush()
    }

    private fun compressedGain(level: Float): Float {
        val levelDb = amplitudeToDb(max(level, MIN_LEVEL))
        val reductionDb = if (levelDb > THRESHOLD_DB) {
            (levelDb - THRESHOLD_DB) * (1f - 1f / RATIO)
        } else {
            0f
        }
        return dbToAmplitude((MAKEUP_GAIN_DB - reductionDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB))
    }

    private fun coefficient(milliseconds: Float, sampleRate: Float): Float =
        exp((-1_000f / (milliseconds * sampleRate)).toDouble()).toFloat()

    private fun amplitudeToDb(amplitude: Float): Float =
        (20.0 * ln(amplitude.toDouble()) / ln(10.0)).toFloat()

    private fun dbToAmplitude(db: Float): Float = 10f.pow(db / 20f)

    private companion object {
        const val PCM_SCALE = 32_768f
        const val OUTPUT_CEILING = 27_820 // -1.42 dBFS; headroom for the analogue stage.
        const val MIN_LEVEL = 0.000_001f

        const val THRESHOLD_DB = -20f
        const val RATIO = 3f
        const val MAKEUP_GAIN_DB = 8f
        const val MIN_GAIN_DB = -8f
        const val MAX_GAIN_DB = 8f

        const val ENVELOPE_ATTACK_MS = 5f
        const val ENVELOPE_RELEASE_MS = 180f
        const val GAIN_ATTACK_MS = 4f
        const val GAIN_RELEASE_MS = 250f
    }
}
