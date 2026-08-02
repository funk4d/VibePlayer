package com.vibeplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryControllerTest {
    @Test
    fun retriesDolbyVisionExactlyOnce() {
        val recovery = PlaybackRecoveryController()
        assertTrue(recovery.requestBaseLayerRetry(isDolbyVision = true))
        assertEquals(PlaybackAttempt.BASE_LAYER, recovery.attempt)
        assertFalse(recovery.requestBaseLayerRetry(isDolbyVision = true))
    }

    @Test
    fun doesNotRetryOrdinaryVideo() {
        val recovery = PlaybackRecoveryController()
        assertFalse(recovery.requestBaseLayerRetry(isDolbyVision = false))
        assertEquals(PlaybackAttempt.NATIVE, recovery.attempt)
    }
}

