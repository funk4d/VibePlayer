package com.vibeplayer.tv

internal enum class PlaybackAttempt {
    NATIVE,
    BASE_LAYER,
    TERMINAL,
}

internal class PlaybackRecoveryController {
    var attempt: PlaybackAttempt = PlaybackAttempt.NATIVE
        private set

    fun reset() {
        attempt = PlaybackAttempt.NATIVE
    }

    fun requestBaseLayerRetry(isDolbyVision: Boolean): Boolean {
        if (!isDolbyVision || attempt != PlaybackAttempt.NATIVE) return false
        attempt = PlaybackAttempt.BASE_LAYER
        return true
    }

    fun markTerminal() {
        attempt = PlaybackAttempt.TERMINAL
    }
}

