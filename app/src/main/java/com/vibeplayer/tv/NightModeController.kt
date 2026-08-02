package com.vibeplayer.tv

import android.util.Log
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal class NightModeController {
    val audioProcessor = NightModeAudioProcessor(initiallyEnabled = true)

    val requestedEnabled: Boolean
        get() = audioProcessor.enabled

    fun toggle(): Boolean {
        audioProcessor.enabled = !audioProcessor.enabled
        Log.i(TAG, "PCM night mode enabled=${audioProcessor.enabled}")
        return true
    }

    private companion object {
        const val TAG = "VibePlayer"
    }
}
