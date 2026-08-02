package com.vibeplayer.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioOffsetProcessorTest {
    private val stereo48k = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun zeroOffsetIsInactive() {
        val processor = AudioOffsetProcessor()

        processor.configure(stereo48k)

        assertFalse(processor.isActive)
    }

    @Test
    fun positiveOffsetPrependsSilence() {
        val processor = AudioOffsetProcessor().apply { offsetMs = 1 }
        processor.configure(stereo48k)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcmFrames(2)

        processor.queueInput(input)
        val output = processor.output

        assertEquals((48 + 2) * stereo48k.bytesPerFrame, output.remaining())
        repeat(48 * stereo48k.channelCount) { assertEquals(0, output.short.toInt()) }
        assertEquals(1, output.short.toInt())
    }

    @Test
    fun negativeOffsetDropsAudioFrames() {
        val processor = AudioOffsetProcessor().apply { offsetMs = -1 }
        processor.configure(stereo48k)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcmFrames(50)

        processor.queueInput(input)
        val output = processor.output

        assertEquals(2 * stereo48k.bytesPerFrame, output.remaining())
        assertEquals(1, output.short.toInt())
    }

    private fun pcmFrames(frameCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frameCount * stereo48k.bytesPerFrame)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(frameCount * stereo48k.channelCount) { putShort(1) }
                flip()
            }
}
