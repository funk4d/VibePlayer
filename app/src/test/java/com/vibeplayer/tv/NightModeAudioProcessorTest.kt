package com.vibeplayer.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeAudioProcessorTest {
    @Test
    fun disabledProcessorCopiesPcm() {
        val processor = configuredProcessor(enabled = false)
        val input = pcmBuffer(shortArrayOf(-20_000, -1_000, 0, 1_000, 20_000))

        processor.queueInput(input)
        val output = processor.output

        assertEquals(shortArrayOf(-20_000, -1_000, 0, 1_000, 20_000).toList(), output.toShortList())
    }

    @Test
    fun enabledProcessorRaisesQuietDialogueLevel() {
        val processor = configuredProcessor(enabled = true)
        val samples = ShortArray(48_000 * 2) { 1_000 }

        processor.queueInput(pcmBuffer(samples))
        val output = processor.output
        output.position(output.limit() - Short.SIZE_BYTES)

        assertTrue(output.short > 2_000)
    }

    @Test
    fun enabledProcessorLimitsPeaks() {
        val processor = configuredProcessor(enabled = true)
        val samples = ShortArray(48_000 * 2) { 32_000 }

        processor.queueInput(pcmBuffer(samples))
        val output = processor.output
        var peak = 0
        while (output.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(output.short.toInt()))

        assertTrue(peak <= 27_820)
    }

    private fun configuredProcessor(enabled: Boolean): NightModeAudioProcessor =
        NightModeAudioProcessor(initiallyEnabled = enabled).also {
            it.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            it.flush()
        }

    private fun pcmBuffer(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                samples.forEach(::putShort)
                flip()
            }

    private fun ByteBuffer.toShortList(): List<Short> = buildList {
        while (this@toShortList.hasRemaining()) add(this@toShortList.short)
    }
}
