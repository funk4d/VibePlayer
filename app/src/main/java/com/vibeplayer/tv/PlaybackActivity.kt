package com.vibeplayer.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import java.util.Locale

@UnstableApi
class PlaybackActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var softwarePlayerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var playbackInfo: TextView
    private lateinit var playbackTitleGroup: View
    private lateinit var playbackTitle: TextView
    private lateinit var playbackSource: TextView
    private lateinit var seriesControls: View
    private lateinit var seasonButton: TextView
    private lateinit var episodeButton: TextView
    private lateinit var episodeTitleText: TextView
    private lateinit var seekFeedback: TextView
    private lateinit var controlsPanel: View
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var timeSeekBar: SeekBar
    private lateinit var seekBackButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekForwardButton: ImageButton
    private lateinit var qualityButton: TextView
    private lateinit var audioButton: ImageButton
    private lateinit var voiceoverButton: ImageButton
    private lateinit var subtitlesButton: ImageButton
    private lateinit var settingsButton: ImageButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recovery = PlaybackRecoveryController()
    private val nightMode = NightModeController()
    private val audioOffsetProcessor = AudioOffsetProcessor()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var request: PlaybackRequest? = null
    private var sourceLadder: SourceLadder? = null
    private var isStarted = false
    private var restorePositionMs = 0L
    private var firstFrameRendered = false
    private var currentVideoIsDolbyVision = false
    private var unsupportedVideoMessage: String? = null
    private var usingSoftwareVideoOutput = false
    private var oversizedAv1WarningLogged = false
    private var selectedQualityLabel: String? = null
    private var selectedVoiceoverLabel: String? = null
    private var selectedEpisodeInfo: EpisodeVariantInfo? = null
    private var initialVoiceoverEpisodeKey: Pair<Int, Int>? = null
    private var watchdogStartPositionMs = 0L
    private var settingsDialogOpen = false
    private var dialogReturnFocus: View? = null
    private var videoInfo: String? = null
    private var audioInfo: String? = null
    private var decoderInfo: String? = null
    private var seekIntervalMs = DEFAULT_SEEK_MS
    private var audioOffsetMs = 0
    private var restorePlayFocusBackground = false
    private var shortMediaReported = false
    private var stubRetries = 0

    private val hideStatus = Runnable { statusText.visibility = View.GONE }
    private val hideControls = Runnable {
        if (!settingsDialogOpen) {
            controlsPanel.visibility = View.GONE
            seriesControls.visibility = View.GONE
            updatePlaybackInfoUi()
        }
    }
    private val hideSeekFeedback = Runnable {
        seekFeedback.animate()
            .alpha(0f)
            .scaleX(1.25f)
            .scaleY(1.25f)
            .setDuration(180L)
            .withEndAction {
                seekFeedback.visibility = View.GONE
                if (restorePlayFocusBackground) {
                    playPauseButton.setBackgroundResource(R.drawable.player_playback_focus)
                    restorePlayFocusBackground = false
                }
            }
            .start()
    }
    private val updateProgress = object : Runnable {
        override fun run() {
            updateProgressUi()
            recordWatchProgress()
            mainHandler.postDelayed(this, PROGRESS_UPDATE_MS)
        }
    }
    private val firstFrameWatchdog = Runnable {
        val activePlayer = player ?: return@Runnable
        val advanced = activePlayer.currentPosition - watchdogStartPositionMs >= 1_500L
        if (!firstFrameRendered && currentVideoIsDolbyVision &&
            activePlayer.playbackState == Player.STATE_READY && (activePlayer.isPlaying || advanced)
        ) {
            Log.w(TAG, "Dolby Vision first-frame watchdog fired attempt=${recovery.attempt}")
            retryWithBaseLayer("Dolby Vision produced no video frame")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        bindViews()
        loadUserSettings()
        setupControls()
        hideSystemUi()
        WatchProgressServer.start()
        acceptIntent(intent, resetRecovery = true)
        restorePositionMs = savedInstanceState?.getLong(STATE_POSITION)
            ?: request?.startPositionMs
            ?: 0L
        mainHandler.post(updateProgress)
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        if (player == null) startPlayback(restorePositionMs)
    }

    override fun onStop() {
        restorePositionMs = player?.currentPosition ?: restorePositionMs
        isStarted = false
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent, resetRecovery = true)
        restorePositionMs = request?.startPositionMs ?: 0L
        if (isStarted) startPlayback(restorePositionMs)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_POSITION, player?.currentPosition ?: restorePositionMs)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (controlsPanel.visibility == View.VISIBLE) {
                controlsPanel.visibility = View.GONE
                seriesControls.visibility = View.GONE
            } else {
                finishWithPlaybackResult()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            showSettingsDialog()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlayback()
            return true
        }

        if (controlsPanel.visibility == View.VISIBLE) {
            scheduleControlsHide()
            return handleControlsKey(event) || super.dispatchKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                togglePlayback()
                showControls(focusTimeline = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(-seekIntervalMs)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(seekIntervalMs)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            -> {
                showControls(focusTimeline = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        softwarePlayerView = findViewById(R.id.software_player_view)
        statusText = findViewById(R.id.status_text)
        playbackInfo = findViewById(R.id.playback_info)
        playbackTitleGroup = findViewById(R.id.playback_title_group)
        playbackTitle = findViewById(R.id.playback_title)
        playbackSource = findViewById(R.id.playback_source)
        seriesControls = findViewById(R.id.series_controls)
        seasonButton = findViewById(R.id.season_button)
        episodeButton = findViewById(R.id.episode_button)
        seasonButton.clipToOutline = true
        episodeButton.clipToOutline = true
        episodeTitleText = findViewById(R.id.episode_title_text)
        seekFeedback = findViewById(R.id.seek_feedback)
        controlsPanel = findViewById(R.id.controls_panel)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        timeSeekBar = findViewById(R.id.time_seek_bar)
        seekBackButton = findViewById(R.id.seek_back_button)
        playPauseButton = findViewById(R.id.play_pause_button)
        seekForwardButton = findViewById(R.id.seek_forward_button)
        qualityButton = findViewById(R.id.quality_button)
        audioButton = findViewById(R.id.audio_button)
        voiceoverButton = findViewById(R.id.voiceover_button)
        subtitlesButton = findViewById(R.id.subtitles_button)
        settingsButton = findViewById(R.id.settings_button)
    }

    private fun setupControls() {
        seekForwardButton.setOnClickListener { seekBy(seekIntervalMs) }
        seekBackButton.setOnClickListener { seekBy(-seekIntervalMs) }
        playPauseButton.setOnClickListener { togglePlayback() }
        qualityButton.setOnClickListener { showQualityDialog() }
        audioButton.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        voiceoverButton.setOnClickListener { showVoiceoverDialog() }
        subtitlesButton.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        settingsButton.setOnClickListener { showSettingsDialog() }
        seasonButton.setOnClickListener { showSeasonDialog() }
        episodeButton.setOnClickListener { showEpisodeDialog() }
        attachFocusPulse(
            playPauseButton,
            qualityButton,
            audioButton,
            voiceoverButton,
            subtitlesButton,
            settingsButton,
            seasonButton,
            episodeButton,
        )
        timeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(progress * 1_000L)
                if (fromUser) positionText.text = formatTime(progress * 1_000L)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = scheduleControlsHide()
        })
        updateSeekButtonDescriptions()
    }

    private fun loadUserSettings() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        seekIntervalMs = preferences.getLong(PREF_SEEK_INTERVAL_MS, DEFAULT_SEEK_MS)
            .coerceIn(MIN_SEEK_MS, MAX_SEEK_MS)
        audioOffsetMs = preferences.getInt(PREF_AUDIO_OFFSET_MS, 0)
            .coerceIn(-AudioOffsetProcessor.MAX_OFFSET_MS, AudioOffsetProcessor.MAX_OFFSET_MS)
        audioOffsetProcessor.offsetMs = audioOffsetMs
    }

    private fun acceptIntent(intent: Intent, resetRecovery: Boolean) {
        val parsed = PlaybackRequest.from(intent)
        request = parsed.getOrNull()
        resetSourceLadder()
        if (resetRecovery) {
            recovery.reset()
            stubRetries = 0
            val active = request
            val selectedVariant = active?.qualityVariants?.firstOrNull { it.uri == active.uri }
            selectedQualityLabel = selectedVariant?.label
            // The launched address belongs to the chosen quality and equals no entry's own
            // address, so where we are is taken from what the source stated, not guessed.
            val stated = active?.currentEpisode
            selectedVoiceoverLabel = stated?.voice ?: selectedVariant?.voiceoverLabel
            selectedEpisodeInfo = stated?.let { current ->
                episodeVariants(current.season, current.episode).firstOrNull()?.episode ?: current
            }
            initialVoiceoverEpisodeKey = selectedEpisodeInfo?.key
        }
        updateVoiceoverButtonVisibility()
        updateSeriesControlsVisibility()
        val activeRequest = request
        if (activeRequest == null) {
            val message = if (intent.action == Intent.ACTION_MAIN) {
                getString(R.string.ready_message)
            } else {
                parsed.exceptionOrNull()?.message ?: getString(R.string.invalid_link)
            }
            showPersistentStatus(message)
        } else {
            // Structural counts only: this is the one signal that says whether the Lampa
            // bridge is alive and what it managed to serialize. Never log URLs or headers.
            // The labels exactly as they arrived, before any parsing. Distinguishes "the
            // bridge sent nothing" from "the player understood nothing"; labels carry titles
            // and episode numbers, never addresses.
            Log.i(
                TAG,
                "Raw quality labels=" + intent.getStringArrayExtra("quality_levels")
                    ?.take(RAW_LABEL_LOG_LIMIT)
                    ?.joinToString(" ¦ ") { it.take(MAX_RAW_LABEL_LENGTH) }
                    .orEmpty().ifEmpty { "(none)" },
            )
            val variants = activeRequest.qualityVariants
            // Voice names are what the source calls its own audio tracks - no addresses, no
            // tokens - and seeing them is the only way to tell a missing one from a duplicate.
            Log.i(
                TAG,
                "Voices raw=" + variants.mapNotNull { it.episode?.voice }.distinct().sorted() +
                    " forCurrent=" + voicesForCurrentEpisode().keys.sorted() +
                    " selected=" + (selectedVoiceoverLabel ?: "none") +
                    " at=" + (selectedEpisodeInfo?.let { "S${it.season}E${it.episode}" } ?: "none"),
            )
            Log.i(
                TAG,
                "Intent position=${activeRequest.startPositionMs}ms " +
                    "qualityLabels=${variants.filter { it.episode == null && it.voiceoverLabel == null }.map { it.label }} " +
                    "bridge=[title=${activeRequest.title != null} source=${activeRequest.sourceName != null} " +
                    "episodes=${variants.count { it.episode != null }} " +
                    "voiceovers=${variants.mapNotNull { it.voiceoverLabel }.distinct().size} " +
                    "reserves=${activeRequest.reserveUrls.size} " +
                    "capture=${activeRequest.bridgeProbe ?: "none"} " +
                    "plugin=${HeaderParser.valueOf(activeRequest.headers, "X-Vibe-Bridge") ?: "unknown"} " +
                    "stats=${HeaderParser.valueOf(activeRequest.headers, "X-Vibe-Stats") ?: "none"}]",
            )
        }
    }

    /**
     * Rebuilds the fallback ladder for whatever address is current. Every change of the
     * playing URL — a new intent, a quality switch, an episode switch — starts a fresh
     * ladder, so one bad stream can never spend another stream's retry budget.
     */
    private fun resetSourceLadder() {
        val activeRequest = request
        sourceLadder = activeRequest?.let {
            val url = it.uri.toString()
            SourceLadder(
                primaryUrl = url,
                primaryMimeType = if (it.mimeTypeInferred) SourceLadder.containerHint(url) else it.mimeType,
                reserveUrls = it.reserveUrls,
            )
        }
    }

    private fun startPlayback(positionMs: Long) {
        val activeRequest = request ?: return
        val source = sourceLadder?.current ?: return
        releasePlayer()
        firstFrameRendered = false
        shortMediaReported = false
        currentVideoIsDolbyVision = false
        unsupportedVideoMessage = null
        oversizedAv1WarningLogged = false
        videoInfo = null
        audioInfo = null
        decoderInfo = null
        useSoftwareVideoOutput(false)
        mainHandler.removeCallbacks(firstFrameWatchdog)

        Log.i(
            TAG,
            "Open ${LocationRedactor.redact(source.url)} mime=${source.mimeType ?: "auto"} " +
                "source=${source.kind} headers=${activeRequest.headers.keys.sorted()} " +
                "quality=${selectedQualityLabel ?: "auto"} " +
                "position=${positionMs.coerceAtLeast(0L)} attempt=${recovery.attempt}",
        )
        showPersistentStatus("Loading…")

        val components = PlayerFactory.create(
            this,
            activeRequest,
            recovery.attempt,
            audioOffsetProcessor,
            nightMode.audioProcessor,
        )
        val newPlayer = components.player
        trackSelector = components.trackSelector
        player = newPlayer
        playerView.player = newPlayer
        newPlayer.addListener(playerListener)
        newPlayer.addAnalyticsListener(analyticsListener)
        newPlayer.setMediaItem(PlayerFactory.mediaItem(source), positionMs.coerceAtLeast(0L))
        newPlayer.prepare()
        newPlayer.play()
        updateProgressUi()
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(firstFrameWatchdog)
        mainHandler.removeCallbacks(hideStatus)
        if (::playerView.isInitialized) playerView.player = null
        if (::softwarePlayerView.isInitialized) softwarePlayerView.player = null
        player?.release()
        player = null
        trackSelector = null
    }

    private fun togglePlayback() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
        updateProgressUi()
    }

    private fun seekBy(deltaMs: Long) {
        val activePlayer = player ?: return
        val duration = activePlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        activePlayer.seekTo((activePlayer.currentPosition + deltaMs).coerceIn(0L, duration))
        animateSeekFeedback(deltaMs)
        updateProgressUi()
    }

    private fun animateSeekFeedback(deltaMs: Long) {
        mainHandler.removeCallbacks(hideSeekFeedback)
        seekFeedback.animate().cancel()
        val seconds = kotlin.math.abs(deltaMs) / 1_000L
        seekFeedback.text = if (deltaMs < 0) "−${seconds}s" else "+${seconds}s"
        val controlsVisible = controlsPanel.visibility == View.VISIBLE
        val density = resources.displayMetrics.density
        val horizontalOffsetDp = if (controlsVisible) 98f else 120f
        seekFeedback.translationX = if (deltaMs < 0) -horizontalOffsetDp * density else horizontalOffsetDp * density
        seekFeedback.translationY = 0f
        if (controlsVisible && currentFocus == playPauseButton) {
            restorePlayFocusBackground = true
            playPauseButton.setBackgroundResource(android.R.color.transparent)
        }
        seekFeedback.alpha = 0f
        seekFeedback.scaleX = 0.72f
        seekFeedback.scaleY = 0.72f
        seekFeedback.visibility = View.VISIBLE
        // The feedback is a sibling of the controls panel. Move it above the panel so the
        // gradient pulse covers the rewind glyph instead of being painted behind it.
        seekFeedback.bringToFront()
        seekFeedback.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140L)
            .start()
        mainHandler.postDelayed(hideSeekFeedback, 420L)
    }

    private fun scrubTimeline(direction: Int) {
        val activePlayer = player ?: return
        val duration = activePlayer.duration.takeIf { it > 0 } ?: return
        val step = (duration / TIMELINE_STEPS).coerceAtLeast(1_000L)
        activePlayer.seekTo((activePlayer.currentPosition + direction * step).coerceIn(0L, duration))
        updateProgressUi()
    }

    private fun showControls(focusTimeline: Boolean) {
        controlsPanel.visibility = View.VISIBLE
        updateSeriesControlsVisibility()
        updateProgressUi()
        controlsPanel.post {
            (if (focusTimeline) timeSeekBar else playPauseButton).requestFocus()
        }
        scheduleControlsHide()
        updatePlaybackInfoUi()
    }

    private fun handleControlsKey(event: KeyEvent): Boolean {
        val focused = currentFocus
        val playbackButtons: List<View> = listOf(seekBackButton, playPauseButton, seekForwardButton)
        val seriesButtons: List<View> = listOf(seasonButton, episodeButton)
            .filter { it.visibility == View.VISIBLE }
        val settingsButtons: List<View> = listOf(
            qualityButton,
            audioButton,
            voiceoverButton,
            subtitlesButton,
            settingsButton,
        ).filter { it.visibility == View.VISIBLE }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> focused?.performClick() == true
            KeyEvent.KEYCODE_DPAD_UP -> {
                when (focused) {
                    timeSeekBar -> playPauseButton.requestFocus()
                    in settingsButtons -> timeSeekBar.requestFocus()
                    in playbackButtons -> seriesButtons.firstOrNull()?.requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (focused) {
                    in seriesButtons -> playPauseButton.requestFocus()
                    in playbackButtons -> timeSeekBar.requestFocus()
                    timeSeekBar -> qualityButton.requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                when (focused) {
                    timeSeekBar -> scrubTimeline(direction)
                    in settingsButtons -> {
                        val currentIndex = settingsButtons.indexOf(focused)
                        settingsButtons[(currentIndex + direction).coerceIn(settingsButtons.indices)].requestFocus()
                    }
                    in seriesButtons -> {
                        val currentIndex = seriesButtons.indexOf(focused)
                        seriesButtons[(currentIndex + direction).coerceIn(seriesButtons.indices)].requestFocus()
                    }
                    in playbackButtons -> {
                        seekBy(direction * seekIntervalMs)
                        playPauseButton.requestFocus()
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun scheduleControlsHide() {
        mainHandler.removeCallbacks(hideControls)
        mainHandler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
    }

    private fun updateProgressUi() {
        if (!::timeSeekBar.isInitialized) return
        val activePlayer = player
        val position = activePlayer?.currentPosition?.coerceAtLeast(0L) ?: restorePositionMs
        val duration = activePlayer?.duration?.takeIf { it > 0 } ?: 0L
        positionText.text = formatTime(position)
        durationText.text = formatTime(duration)
        timeSeekBar.max = (duration / 1_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        timeSeekBar.progress = (position / 1_000L).coerceIn(0L, timeSeekBar.max.toLong()).toInt()
        val isPlaying = activePlayer?.isPlaying == true
        playPauseButton.setImageResource(if (isPlaying) R.drawable.player_pause else R.drawable.player_play)
        playPauseButton.contentDescription = getString(if (isPlaying) R.string.pause else R.string.play)
        updateQualityBadge()
        updatePlaybackInfoUi()
    }

    private fun showSettingsDialog() {
        val quality = selectedQualityLabel ?: selectedVideoFormatLabel() ?: "Auto"
        val subtitles = selectedTrackLabel(C.TRACK_TYPE_TEXT) ?: getString(R.string.off)
        val audio = selectedTrackLabel(C.TRACK_TYPE_AUDIO) ?: "Auto"
        val items = arrayOf(
            "${getString(R.string.quality)}: $quality",
            "${getString(R.string.audio)}: $audio",
            "${getString(R.string.subtitles)}: $subtitles",
            "Seek step: ${seekIntervalMs / 1_000L}s",
            "Audio sync: ${formatAudioOffset(audioOffsetMs)}",
            getString(if (nightMode.requestedEnabled) R.string.night_mode_on else R.string.night_mode_off),
        )
        showDialog(
            title = getString(R.string.settings),
            items = items,
        ) { index ->
            when (index) {
                0 -> showQualityDialog()
                1 -> showTrackDialog(C.TRACK_TYPE_AUDIO)
                2 -> showTrackDialog(C.TRACK_TYPE_TEXT)
                3 -> showSeekIntervalDialog()
                4 -> showAudioOffsetDialog()
                5 -> {
                    nightMode.toggle()
                    showTemporaryStatus(
                        getString(if (nightMode.requestedEnabled) R.string.night_mode_on else R.string.night_mode_off),
                    )
                }
            }
        }
    }

    private fun showSeekIntervalDialog() {
        val labels = SEEK_INTERVAL_OPTIONS_SECONDS.map { seconds ->
            if (seekIntervalMs == seconds * 1_000L) "✓ ${seconds}s" else "${seconds}s"
        }.toTypedArray()
        showDialog("Seek step", labels) { index ->
            seekIntervalMs = SEEK_INTERVAL_OPTIONS_SECONDS[index] * 1_000L
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PREF_SEEK_INTERVAL_MS, seekIntervalMs)
                .apply()
            updateSeekButtonDescriptions()
            showTemporaryStatus("Seek step: ${seekIntervalMs / 1_000L}s")
        }
    }

    private fun showAudioOffsetDialog() {
        rememberDialogFocus()
        val valueText = TextView(this).apply {
            textSize = 18f
            setTextColor(getColor(R.color.status_text))
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        val slider = SeekBar(this).apply {
            max = AUDIO_OFFSET_STEPS * 2
            progress = (audioOffsetMs / AUDIO_OFFSET_STEP_MS) + AUDIO_OFFSET_STEPS
            keyProgressIncrement = 1
        }
        fun selectedOffset(): Int = (slider.progress - AUDIO_OFFSET_STEPS) * AUDIO_OFFSET_STEP_MS
        fun updateLabel() {
            valueText.text = formatAudioOffset(selectedOffset())
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = updateLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        updateLabel()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (28 * resources.displayMetrics.density).toInt()
            val vertical = (12 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            addView(valueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        settingsDialogOpen = true
        mainHandler.removeCallbacks(hideControls)
        AlertDialog.Builder(this)
            .setTitle("Audio sync (experimental)")
            .setMessage("Positive values play audio later; negative values play it earlier.")
            .setView(content)
            .setPositiveButton("Apply") { _, _ -> applyAudioOffset(selectedOffset()) }
            .setNeutralButton("Reset") { _, _ -> applyAudioOffset(0) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { restoreDialogFocus() }
            .show()
        slider.requestFocus()
    }

    private fun applyAudioOffset(valueMs: Int) {
        audioOffsetMs = valueMs.coerceIn(-AudioOffsetProcessor.MAX_OFFSET_MS, AudioOffsetProcessor.MAX_OFFSET_MS)
        audioOffsetProcessor.offsetMs = audioOffsetMs
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_AUDIO_OFFSET_MS, audioOffsetMs)
            .apply()
        val position = player?.currentPosition ?: restorePositionMs
        if (isStarted && request != null) startPlayback(position)
        showTemporaryStatus("Audio sync: ${formatAudioOffset(audioOffsetMs)}")
    }

    private fun formatAudioOffset(valueMs: Int): String = when {
        valueMs > 0 -> "+${valueMs}ms (later)"
        valueMs < 0 -> "${valueMs}ms (earlier)"
        else -> "0ms"
    }

    private fun updateSeekButtonDescriptions() {
        val seconds = seekIntervalMs / 1_000L
        seekBackButton.contentDescription = "Seek back $seconds seconds"
        seekForwardButton.contentDescription = "Seek forward $seconds seconds"
    }

    private fun showQualityDialog() {
        val variants = activeQualityVariants()
        if (variants.isNotEmpty()) {
            val labels = variants.map { variant ->
                if (variant.label == selectedQualityLabel) "✓ ${variant.label}" else variant.label
            }.toTypedArray()
            showDialog(getString(R.string.quality), labels) { index ->
                switchToExternalQuality(variants[index])
            }
            return
        }

        val tracks = collectTracks(C.TRACK_TYPE_VIDEO).filter { it.group.isTrackSupported(it.index) }
        if (tracks.isEmpty()) {
            showTemporaryStatus("No selectable video tracks")
            return
        }
        showDialog(getString(R.string.quality), tracks.map(::videoTrackLabel).toTypedArray()) { index ->
            applyTrackOverride(tracks[index])
            selectedQualityLabel = videoTrackLabel(tracks[index])
        }
    }

    /** Every stream of one episode, whatever voice it is in. */
    private fun episodeVariants(season: Int, episode: Int): List<QualityVariant> =
        request?.qualityVariants.orEmpty().filter {
            it.episode?.season == season && it.episode.episode == episode
        }

    private fun voiceKey(name: String): String {
        val bracketed = Regex("\\(([^)]{2,})\\)").find(name)?.groupValues?.get(1)
        return (bracketed ?: name).lowercase().filter(Char::isLetterOrDigit)
    }

    private fun <T> foldVoiceAliases(groups: Map<String, List<T>>): Map<String, List<T>> {
        val byKey = LinkedHashMap<String, Pair<String, MutableList<T>>>()
        groups.forEach { (name, entries) ->
            val key = voiceKey(name).ifEmpty { name.lowercase() }
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = name to entries.toMutableList()
            } else {
                existing.second.addAll(entries)
                // Keep the shorter spelling: it is the name, the longer one repeats it.
                if (name.length < existing.first.length) byKey[key] = name to existing.second
            }
        }
        return byKey.values.associate { (name, entries) -> name to entries.toList() }
    }

    private fun voicesForCurrentEpisode(): Map<String, List<QualityVariant>> {
        val current = selectedEpisodeInfo
        val byVoice = (current?.let { episodeVariants(it.season, it.episode) } ?: emptyList())
            .filter { it.episode?.voice != null }
            .groupBy { requireNotNull(it.episode?.voice) }
        if (byVoice.isNotEmpty()) return foldVoiceAliases(byVoice)

        // Sources without episodes still offer plain voiceover variants of one stream.
        return foldVoiceAliases(
            request?.qualityVariants.orEmpty()
                .filter { it.voiceoverLabel != null && it.episode == null }
                .groupBy { requireNotNull(it.voiceoverLabel) },
        )
    }

    private fun showVoiceoverDialog() {
        val voiceoverGroups = voicesForCurrentEpisode()
        if (voiceoverGroups.isEmpty()) {
            updateVoiceoverButtonVisibility()
            return
        }

        val options = voiceoverGroups.keys.toList()
        val labels = options.map { voiceover ->
            if (voiceover == selectedVoiceoverLabel) "✓ $voiceover" else voiceover
        }.toTypedArray()

        showDialog(getString(R.string.voiceover), labels) { index ->
            val selected = options[index]
            if (selected == selectedVoiceoverLabel) return@showDialog
            chooseBestVariant(voiceoverGroups.getValue(selected))?.let { variant ->
                selectedVoiceoverLabel = selected
                // Same episode, different voice: carry on from where the viewer is rather
                // than restarting at the episode's saved position.
                switchToEpisode(variant, resumeAt = player?.currentPosition ?: restorePositionMs)
            }
        }
    }

    private fun chooseBestVariant(variants: List<QualityVariant>): QualityVariant? {
        if (variants.isEmpty()) return null
        val selectedHeight = selectedQualityLabel?.let(QualityVariantParser::heightFromLabel)
            ?: selectedVideoHeight()
        return variants.firstOrNull { it.label.equals(selectedQualityLabel, ignoreCase = true) }
            ?: selectedHeight?.let { height ->
                variants.minByOrNull { variant ->
                    kotlin.math.abs((variant.height ?: Int.MAX_VALUE / 2) - height)
                }
            }
            ?: variants.maxByOrNull { it.height ?: 0 }
    }

    private fun updateVoiceoverButtonVisibility() {
        if (!::voiceoverButton.isInitialized) return
        // A choice exists whenever the episode being watched has more than one voice, which
        // stays true after switching episode or voice - both used to hide the button.
        val hasVoiceovers = voicesForCurrentEpisode().size > 1
        if (!hasVoiceovers && currentFocus == voiceoverButton) audioButton.requestFocus()
        voiceoverButton.visibility = if (hasVoiceovers) View.VISIBLE else View.GONE
    }

    private fun activeQualityVariants(): List<QualityVariant> {
        val variants = request?.qualityVariants.orEmpty()
        selectedVoiceoverLabel?.let { voiceover ->
            return variants.filter { it.voiceoverLabel == voiceover && it.episode == null }
        }
        selectedEpisodeInfo?.key?.let { episodeKey ->
            return variants.filter { it.voiceoverLabel == null && it.episode?.key == episodeKey }
        }
        return variants.filter { it.voiceoverLabel == null && it.episode == null }
    }

    private fun showSeasonDialog() {
        val seasons = request?.qualityVariants
            .orEmpty()
            .filter(::matchesSelectedVoice)
            .mapNotNull { it.episode?.season }
            .distinct()
            .sorted()
        if (seasons.isEmpty()) {
            updateSeriesControlsVisibility()
            return
        }
        val labels = seasons.map { season ->
            if (season == selectedEpisodeInfo?.season) "✓ Season $season" else "Season $season"
        }.toTypedArray()
        showDialog(getString(R.string.season), labels) { index ->
            val selectedSeason = seasons[index]
            val firstEpisode = episodeGroups(selectedSeason).keys.minOrNull()
            val current = selectedEpisodeInfo
            selectedEpisodeInfo = current?.takeIf { it.season == selectedSeason }
                ?: firstEpisode?.let { episodeGroups(selectedSeason)[it]?.firstOrNull()?.episode }
            updateSeriesControlsUi()
        }
    }

    private fun showEpisodeDialog() {
        val season = selectedEpisodeInfo?.season
            ?: request?.qualityVariants?.mapNotNull { it.episode?.season }?.minOrNull()
            ?: return
        val groups = episodeGroups(season)
        if (groups.isEmpty()) return
        val episodeNumbers = groups.keys.sorted()
        val labels = episodeNumbers.map { episodeNumber ->
            val info = groups.getValue(episodeNumber).first().episode!!
            val watched = if (info.watchedPercent > WATCHED_EPISODE_PERCENT) "✓ " else ""
            val title = info.title?.let { ". $it" }.orEmpty()
            val progress = if (info.watchedPercent > 0) " · ${info.watchedPercent}%" else ""
            "$watched$episodeNumber$title$progress"
        }.toTypedArray()
        showDialog(getString(R.string.episode), labels) { index ->
            val variants = groups.getValue(episodeNumbers[index])
            val inCurrentVoice = variants.filter(::matchesSelectedVoice)
            val chosen = inCurrentVoice.ifEmpty {
                val fallback = variants.firstOrNull()?.episode?.voice
                if (fallback != null) {
                    Log.i(TAG, "Voice ${selectedVoiceoverLabel ?: "none"} lacks this episode; using $fallback")
                    selectedVoiceoverLabel = fallback
                }
                variants
            }
            chooseBestVariant(chosen)?.let(::switchToEpisode)
        }
    }

    private fun episodeGroups(season: Int): Map<Int, List<QualityVariant>> = request?.qualityVariants
        .orEmpty()
        .filter { it.episode?.season == season }
        .groupBy { requireNotNull(it.episode).episode }

    /** Entries in the voice being watched, or all of them when the source names no voices. */
    private fun matchesSelectedVoice(variant: QualityVariant): Boolean {
        val voice = variant.episode?.voice ?: return true
        val selected = selectedVoiceoverLabel ?: return true
        return voiceKey(voice) == voiceKey(selected)
    }

    private fun updateSeriesControlsVisibility() {
        if (!::seriesControls.isInitialized) return
        val hasEpisodes = request?.qualityVariants?.any { it.episode != null } == true
        seriesControls.visibility = if (hasEpisodes && controlsPanel.visibility == View.VISIBLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (hasEpisodes) {
            if (selectedEpisodeInfo == null) {
                selectedEpisodeInfo = request?.qualityVariants?.firstNotNullOfOrNull { it.episode }
            }
            updateSeriesControlsUi()
        }
    }

    private fun updateSeriesControlsUi() {
        val info = selectedEpisodeInfo ?: return
        seasonButton.text = "Season ${info.season}  ▾"
        episodeButton.text = "Episode ${info.episode}  ▾"
        episodeTitleText.text = listOfNotNull(
            "S${info.season}E${info.episode}",
            info.title?.trim()?.takeIf(String::isNotEmpty),
        ).joinToString(" · ")
    }

    private fun attachFocusPulse(vararg views: View) {
        views.forEach { view ->
            view.setOnFocusChangeListener { target, focused ->
                target.animate().cancel()
                if (focused) {
                    target.animate()
                        .scaleX(1.10f)
                        .scaleY(1.10f)
                        .setDuration(90L)
                        .withEndAction {
                            if (target.hasFocus()) {
                                target.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(110L)
                                    .start()
                            }
                        }
                        .start()
                } else {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                }
            }
        }
    }

    private fun showTrackDialog(trackType: Int) {
        val tracks = collectTracks(trackType).filter { it.group.isTrackSupported(it.index) }
        if (tracks.isEmpty()) {
            showTemporaryStatus(if (trackType == C.TRACK_TYPE_TEXT) "No subtitles" else "No audio tracks")
            return
        }
        val includeOff = trackType == C.TRACK_TYPE_TEXT
        val labels = buildList {
            if (includeOff) add(getString(R.string.off))
            addAll(tracks.map(::trackLabel))
        }.toTypedArray()
        showDialog(
            if (trackType == C.TRACK_TYPE_TEXT) getString(R.string.subtitles) else getString(R.string.audio),
            labels,
        ) { selectedIndex ->
            if (includeOff && selectedIndex == 0) {
                disableTextTracks()
            } else {
                applyTrackOverride(tracks[selectedIndex - if (includeOff) 1 else 0])
            }
        }
    }

    private fun showDialog(title: String, items: Array<String>, onSelected: (Int) -> Unit) {
        rememberDialogFocus()
        settingsDialogOpen = true
        mainHandler.removeCallbacks(hideControls)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which -> onSelected(which) }
            .setOnDismissListener { restoreDialogFocus() }
            .create()
        dialog.show()
    }

    private fun rememberDialogFocus() {
        val focused = currentFocus
        if (focused != null && isPlayerControl(focused)) dialogReturnFocus = focused
        else if (!settingsDialogOpen) dialogReturnFocus = playPauseButton
    }

    private fun restoreDialogFocus() {
        settingsDialogOpen = false
        controlsPanel.visibility = View.VISIBLE
        updateSeriesControlsVisibility()
        updateProgressUi()
        controlsPanel.post { (dialogReturnFocus ?: playPauseButton).requestFocus() }
        scheduleControlsHide()
    }

    private fun isPlayerControl(view: View): Boolean = view == timeSeekBar || view in listOf(
        seekBackButton,
        playPauseButton,
        seekForwardButton,
        qualityButton,
        audioButton,
        voiceoverButton,
        subtitlesButton,
        settingsButton,
        seasonButton,
        episodeButton,
    )

    private fun collectTracks(trackType: Int): List<SelectableTrack> =
        player?.currentTracks?.groups
            ?.filter { it.type == trackType }
            ?.flatMap { group ->
                (0 until group.length).map { index -> SelectableTrack(group, index) }
            }
            .orEmpty()

    private fun applyTrackOverride(track: SelectableTrack) {
        val activePlayer = player ?: return
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(track.group.type, false)
            .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
            .build()
    }

    private fun disableTextTracks() {
        val activePlayer = player ?: return
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun selectedTrackLabel(trackType: Int): String? = collectTracks(trackType)
        .firstOrNull { it.group.isTrackSelected(it.index) }
        ?.let(::trackLabel)

    private fun selectedVideoFormatLabel(): String? = collectTracks(C.TRACK_TYPE_VIDEO)
        .firstOrNull { it.group.isTrackSelected(it.index) }
        ?.let(::videoTrackLabel)

    private fun selectedVideoHeight(): Int? = collectTracks(C.TRACK_TYPE_VIDEO)
        .firstOrNull { it.group.isTrackSelected(it.index) }
        ?.let { it.group.getTrackFormat(it.index).height }
        ?.takeIf { it > 0 }

    private fun updateQualityBadge() {
        if (!::qualityButton.isInitialized) return
        val height = selectedQualityLabel?.let(QualityVariantParser::heightFromLabel) ?: selectedVideoHeight()
        val badge = qualityBadge(height)
        qualityButton.text = badge
        qualityButton.contentDescription = "${getString(R.string.quality)}: $badge"
    }

    private fun frameSize(format: Format): String? {
        if (format.width <= 0 || format.height <= 0) return null
        return "${format.width}×${format.height}"
    }

    private fun qualityBadge(height: Int?): String = when {
        height == null -> "Auto"
        height >= 1441 -> "4K"
        height >= 1081 -> "2K"
        height >= 721 -> "1080"
        height >= 577 -> "720"
        height >= 481 -> "576"
        height >= 361 -> "480"
        else -> "${height}p"
    }

    private fun updatePlaybackInfoUi() {
        if (!::playbackInfo.isInitialized) return
        val activePlayer = player
        val buffering = activePlayer?.playbackState == Player.STATE_BUFFERING
        val controlsVisible = controlsPanel.visibility == View.VISIBLE
        val activeRequest = request
        val title = activeRequest?.title?.trim().orEmpty()
        playbackTitle.text = title
        playbackTitleGroup.visibility = if (controlsVisible && title.isNotEmpty()) View.VISIBLE else View.GONE
        val source = activeRequest?.sourceName?.trim().orEmpty()
        playbackSource.text = source
        playbackSource.visibility = if (controlsVisible && source.isNotEmpty()) View.VISIBLE else View.GONE

        if (!buffering && !controlsVisible) {
            playbackInfo.visibility = View.GONE
            return
        }
        val parts = buildList {
            // How much is loaded ahead is worth seeing all the time on this television, not
            // only once playback has already stalled.
            val buffered = activePlayer?.bufferedPercentage ?: 0
            if (buffering) add("Buffering $buffered%") else add("Buffer $buffered%")
            videoInfo?.let(::add)
            decoderInfo?.let(::add)
            audioInfo?.let(::add)
        }
        playbackInfo.text = parts.joinToString("\n").ifBlank { if (buffering) "Buffering…" else "VibePlayer" }
        playbackInfo.visibility = View.VISIBLE
    }

    private fun videoCodecName(format: Format): String = when (format.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_VP9 -> "VP9"
        else -> format.codecs ?: "Video"
    }

    private fun audioCodecName(format: Format): String = when (format.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_DTS -> "DTS"
        else -> format.codecs ?: "Audio"
    }

    private fun trackLabel(track: SelectableTrack): String {
        val format = track.group.getTrackFormat(track.index)
        val language = format.language?.let { Locale.forLanguageTag(it).displayLanguage }.orEmpty()
        val base = format.label?.takeIf(String::isNotBlank) ?: language.takeIf(String::isNotBlank) ?: "Track ${track.index + 1}"
        val details = buildList {
            format.codecs?.let(::add)
            format.channelCount.takeIf { it > 0 }?.let { add("${it}ch") }
        }
        return if (details.isEmpty()) base else "$base (${details.joinToString()})"
    }

    private fun videoTrackLabel(track: SelectableTrack): String {
        val format = track.group.getTrackFormat(track.index)
        val resolution = format.height.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto"
        return listOfNotNull(resolution, format.codecs).joinToString(" · ")
    }

    private fun switchToExternalQuality(variant: QualityVariant) {
        val activeRequest = request ?: return
        if (
            activeRequest.uri == variant.uri &&
            selectedQualityLabel == variant.label &&
            selectedVoiceoverLabel == variant.voiceoverLabel
        ) return
        val position = player?.currentPosition ?: restorePositionMs
        selectedQualityLabel = variant.label
        selectedVoiceoverLabel = variant.voiceoverLabel
        variant.episode?.let { selectedEpisodeInfo = it }
        request = activeRequest.copy(uri = variant.uri)
        resetSourceLadder()
        updateVoiceoverButtonVisibility()
        updateSeriesControlsUi()
        val kind = if (variant.voiceoverLabel == null) "quality" else "voiceover"
        Log.i(TAG, "Switch $kind label=${variant.label} position=$position")
        showPersistentStatus(
            variant.voiceoverLabel?.let { "$it · ${variant.label}" } ?: "Quality: ${variant.label}",
        )
        startPlayback(position)
    }

    private fun switchToEpisode(variant: QualityVariant, resumeAt: Long? = null) {
        val activeRequest = request ?: return
        val episode = variant.episode ?: return
        recordWatchProgress()
        selectedEpisodeInfo = episode
        // The voice belongs to the entry now, so switching an episode stays inside the voice
        // being watched instead of dropping back to "whatever Lampa picked".
        selectedVoiceoverLabel = episode.voice ?: selectedVoiceoverLabel
        selectedQualityLabel = variant.label
        request = activeRequest.copy(uri = variant.uri)
        resetSourceLadder()
        updateVoiceoverButtonVisibility()
        updateSeriesControlsUi()
        updatePlaybackInfoUi()
        Log.i(
            TAG,
            "Switch episode season=${episode.season} episode=${episode.episode} " +
                "quality=${variant.label} resume=${episode.resumePositionMs}ms",
        )
        showPersistentStatus(
            selectedVoiceoverLabel?.takeIf { resumeAt != null }
                ?: "Season ${episode.season} · Episode ${episode.episode}",
        )
        startPlayback(resumeAt ?: episode.resumePositionMs)
    }

    /**
     * Notes how far the episode being watched has got, against the identity Lampa uses for it.
     * Lampa only ever learns about the episode it launched, so everything chosen afterwards
     * is remembered here and collected by the bridge.
     */
    private fun recordWatchProgress() {
        val activePlayer = player ?: return
        WatchProgressServer.record(
            hash = selectedEpisodeInfo?.timelineHash,
            positionMs = activePlayer.currentPosition,
            durationMs = activePlayer.duration,
        )
    }

    private fun finishWithPlaybackResult() {
        recordWatchProgress()
        val activeRequest = request
        val activePlayer = player
        val position = (activePlayer?.currentPosition ?: restorePositionMs).coerceAtLeast(0L)
        val duration = activePlayer?.duration?.takeIf { it > 0 } ?: 0L
        if (activeRequest != null) {
            val result = Intent()
                .setData(sourceLadder?.current?.url?.let(android.net.Uri::parse) ?: activeRequest.uri)
                .putExtra("position", position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .putExtra("duration", duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            setResult(RESULT_OK, result)
            Log.i(TAG, "Return playback position=${position}ms duration=${duration}ms")
        }
        finish()
    }

    /**
     * Sources answer a refused request with a few seconds of "error notice" video rather than
     * an HTTP failure, so the player has no way to tell it apart from real media. A duration
     * this short for something launched as a film or an episode is that notice, and saying so
     * in the log turns a silent mystery into one grep.
     */
    private fun reportSuspiciouslyShortMedia() {
        if (shortMediaReported) return
        val duration = player?.duration ?: return
        if (duration <= 0L || duration > STUB_MEDIA_MAX_MS) return
        shortMediaReported = true
        Log.w(
            TAG,
            "Source returned ${duration}ms of media - too short for a title; " +
                "this is what a refusal notice looks like source=${request?.sourceName ?: "unknown"}",
        )
        // The same address refused a moment ago often serves the real stream on a second
        // ask, which is what retrying by hand in Lampa does. Bounded, and spaced out - the
        // point is to survive a refusal, not to badger the source.
        if (stubRetries < MAX_STUB_RETRIES) {
            stubRetries += 1
            val position = (player?.currentPosition ?: restorePositionMs)
                .takeIf { it > STUB_MEDIA_MAX_MS } ?: restorePositionMs
            Log.w(TAG, "Refusal notice - asking again (${stubRetries}/$MAX_STUB_RETRIES)")
            showPersistentStatus("Source refused the stream — asking again…")
            releasePlayer()
            mainHandler.postDelayed({ if (isStarted) startPlayback(position) }, STUB_RETRY_DELAY_MS)
            return
        }

        val next = sourceLadder?.next(SourceFailure.UNAVAILABLE)
        if (next != null) {
            Log.w(TAG, "Refusal notice - moving to ${next.kind} ${LocationRedactor.redact(next.url)}")
            showPersistentStatus("Source refused the stream — trying a backup…")
            startPlayback(restorePositionMs)
            return
        }
        // No backup address, but another quality of the same episode is another address.
        val alternative = activeQualityVariants().firstOrNull { it.uri != request?.uri }
        if (alternative != null) {
            Log.w(TAG, "Refusal notice - trying quality ${alternative.label}")
            showPersistentStatus("Source refused the stream — trying ${alternative.label}…")
            switchToExternalQuality(alternative)
        }
    }

    private fun armFirstFrameWatchdog() {
        val activePlayer = player ?: return
        if (firstFrameRendered || !currentVideoIsDolbyVision || recovery.attempt != PlaybackAttempt.NATIVE) return
        if (activePlayer.playbackState != Player.STATE_READY || !activePlayer.playWhenReady) return
        watchdogStartPositionMs = activePlayer.currentPosition
        mainHandler.removeCallbacks(firstFrameWatchdog)
        mainHandler.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun retryWithBaseLayer(reason: String) {
        if (!recovery.requestBaseLayerRetry(currentVideoIsDolbyVision)) {
            recovery.markTerminal()
            showPersistentStatus(reason)
            return
        }
        val position = player?.currentPosition ?: restorePositionMs
        Log.w(TAG, "$reason; retrying with HEVC/AVC base layer at $position ms")
        showPersistentStatus("Dolby Vision fallback…")
        startPlayback(position)
    }

    /**
     * Moves to the next address the ladder offers, if any. Returns false when the ladder
     * is out of options, which is the signal to give up rather than keep poking the source.
     */
    private fun retryWithNextSource(error: PlaybackException): Boolean {
        val ladder = sourceLadder ?: return false
        val position = player?.currentPosition ?: restorePositionMs
        val next = ladder.next(SourceLadder.classify(error.errorCode)) ?: return false
        Log.w(
            TAG,
            "Retrying as ${next.kind} mime=${next.mimeType ?: "auto"} " +
                "location=${LocationRedactor.redact(next.url)}",
        )
        showPersistentStatus(
            when (next.kind) {
                SourceKind.CONTAINER_FALLBACK -> "Stream format differs — reopening…"
                else -> "Trying backup stream…"
            },
        )
        recovery.reset()
        startPlayback(position)
        return true
    }

    private fun showTemporaryStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideStatus)
        mainHandler.postDelayed(hideStatus, STATUS_TIMEOUT_MS)
    }

    private fun showPersistentStatus(message: String) {
        mainHandler.removeCallbacks(hideStatus)
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(TAG, "state=${stateName(playbackState)} attempt=${recovery.attempt}")
            when (playbackState) {
                Player.STATE_READY -> {
                    unsupportedVideoMessage?.let(::showPersistentStatus)
                        ?: run { statusText.visibility = View.GONE }
                    armFirstFrameWatchdog()
                    reportSuspiciouslyShortMedia()
                }
                Player.STATE_ENDED -> {
                    showPersistentStatus("Playback ended")
                    showControls(focusTimeline = false)
                }
                Player.STATE_BUFFERING,
                Player.STATE_IDLE,
                -> if (playbackState == Player.STATE_BUFFERING) statusText.visibility = View.GONE
            }
            updateProgressUi()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) armFirstFrameWatchdog()
            updateProgressUi()
        }

        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            mainHandler.removeCallbacks(firstFrameWatchdog)
            Log.i(TAG, "First video frame rendered attempt=${recovery.attempt}")
        }

        override fun onTracksChanged(tracks: Tracks) {
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            val videoFormats = videoGroups.flatMap { group ->
                (0 until group.length).map { index -> group.getTrackFormat(index) }
            }
            val hasSelectedVideo = videoGroups.any { it.isSelected }
            val selectedAv1Track = videoGroups.firstNotNullOfOrNull { group ->
                (0 until group.length)
                    .firstOrNull { index ->
                        group.isTrackSelected(index) &&
                            group.getTrackFormat(index).sampleMimeType == MimeTypes.VIDEO_AV1
                    }
                    ?.let { index -> group to index }
            }
            if (selectedAv1Track != null) useSoftwareVideoOutput(true)
            unsupportedVideoMessage = if (videoFormats.isNotEmpty() && !hasSelectedVideo) {
                val codecs = videoFormats.mapNotNull { it.codecs ?: it.sampleMimeType }.distinct().joinToString()
                "Unsupported video codec\n${codecs.ifBlank { "unknown" }}"
            } else {
                null
            }

            val selectedAv1Format = selectedAv1Track?.let { (group, index) -> group.getTrackFormat(index) }
            if (!oversizedAv1WarningLogged && selectedAv1Format != null &&
                (selectedAv1Format.width > MAX_SOFTWARE_AV1_WIDTH ||
                    selectedAv1Format.height > MAX_SOFTWARE_AV1_HEIGHT)
            ) {
                oversizedAv1WarningLogged = true
                Log.w(
                    TAG,
                    "Playing ${selectedAv1Format.width}x${selectedAv1Format.height} AV1 in software by user choice",
                )
            }

            videoGroups.forEachIndexed { groupIndex, group ->
                repeat(group.length) { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    Log.i(
                        TAG,
                        "Video track group=$groupIndex index=$trackIndex selected=${group.isTrackSelected(trackIndex)} " +
                            "supported=${group.isTrackSupported(trackIndex)} codecs=${format.codecs} " +
                            "mime=${format.sampleMimeType} size=${format.width}x${format.height}",
                    )
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Throwable messages from HTTP failures may contain a full signed URL.
            // Log only stable diagnostics; never emit tokens/cookies into logcat.
            Log.e(
                TAG,
                "Playback error ${error.errorCodeName} attempt=${recovery.attempt} " +
                    "cause=${error.cause?.javaClass?.simpleName ?: "none"}",
            )
            if (currentVideoIsDolbyVision && recovery.attempt == PlaybackAttempt.NATIVE) {
                retryWithBaseLayer("Native Dolby Vision decoder failed")
            } else if (!retryWithNextSource(error)) {
                recovery.markTerminal()
                showPersistentStatus("Playback failed\n${error.errorCodeName}")
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            currentVideoIsDolbyVision = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
            val profile = MediaCodecUtil.getCodecProfileAndLevel(format)?.first
            videoInfo = buildList {
                // The technical line reports what is actually being decoded. A badge rounds
                // 1920x864 up to "1080" and hides the crop, which is the one thing worth
                // seeing here; the quality button keeps the badge.
                add(frameSize(format) ?: qualityBadge(format.height.takeIf { it > 0 }))
                add(videoCodecName(format))
                format.frameRate.takeIf { it > 0f }?.let { add(String.format(Locale.US, "%.2f fps", it)) }
            }.joinToString(", ")
            updatePlaybackInfoUi()
            Log.i(
                TAG,
                "Video format mime=${format.sampleMimeType} codecs=${format.codecs} " +
                    "size=${format.width}x${format.height} fps=${format.frameRate} profile=$profile",
            )
            armFirstFrameWatchdog()
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            decoderInfo = if (decoderName.startsWith("OMX.realtek")) "HW" else if (decoderName == "libdav1d") "SW" else decoderName
            updatePlaybackInfoUi()
            Log.i(TAG, "Video decoder=$decoderName init=${initializationDurationMs}ms attempt=${recovery.attempt}")
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            audioInfo = buildString {
                append(audioCodecName(format))
                format.channelCount.takeIf { it > 0 }?.let { append(" ${it}ch") }
            }
            updatePlaybackInfoUi()
            Log.i(TAG, "Audio format mime=${format.sampleMimeType} channels=${format.channelCount}")
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            Log.w(TAG, "Dropped $droppedFrames video frames in ${elapsedMs}ms")
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            // loadEventInfo.uri is where the data was really read from, redirects included.
            // Remembering it here is what lets a container fallback target the redirect
            // target without asking the server anything a second time.
            sourceLadder?.noteResolvedLocation(loadEventInfo.uri.toString())
            Log.w(
                TAG,
                "Load error type=${mediaLoadData.dataType} canceled=$wasCanceled " +
                    "location=${LocationRedactor.redact(loadEventInfo.uri.toString())} " +
                    "error=${error.javaClass.simpleName} root=${rootCauseName(error)}",
            )
        }
    }

    private fun rootCauseName(error: Throwable): String {
        var current = error
        repeat(MAX_CAUSE_DEPTH) {
            current = current.cause ?: return current.javaClass.simpleName
        }
        return current.javaClass.simpleName
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    private fun activePlayerView(): PlayerView = if (usingSoftwareVideoOutput) softwarePlayerView else playerView

    private fun useSoftwareVideoOutput(useSoftware: Boolean) {
        if (usingSoftwareVideoOutput == useSoftware) return
        val activePlayer = player
        playerView.player = null
        softwarePlayerView.player = null
        usingSoftwareVideoOutput = useSoftware
        playerView.visibility = if (useSoftware) View.GONE else View.VISIBLE
        softwarePlayerView.visibility = if (useSoftware) View.VISIBLE else View.GONE
        activePlayerView().player = activePlayer
        Log.i(TAG, "Video output=${if (useSoftware) "decoder-gl" else "surface"}")
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        else "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    private val EpisodeVariantInfo.key: Pair<Int, Int>
        get() = season to episode

    private data class SelectableTrack(val group: Tracks.Group, val index: Int)

    private companion object {
        const val TAG = "VibePlayer"
        private const val MAX_CAUSE_DEPTH = 8
        const val STATE_POSITION = "position"
        const val DEFAULT_SEEK_MS = 10_000L
        const val MIN_SEEK_MS = 5_000L
        const val MAX_SEEK_MS = 90_000L
        const val AUDIO_OFFSET_STEP_MS = 50
        const val AUDIO_OFFSET_STEPS = AudioOffsetProcessor.MAX_OFFSET_MS / AUDIO_OFFSET_STEP_MS
        const val PREFERENCES_NAME = "vibeplayer_settings"
        const val PREF_SEEK_INTERVAL_MS = "seek_interval_ms"
        const val PREF_AUDIO_OFFSET_MS = "audio_offset_ms"
        val SEEK_INTERVAL_OPTIONS_SECONDS = listOf(5L, 10L, 15L, 30L, 60L, 90L)
        const val TIMELINE_STEPS = 100L
        const val FIRST_FRAME_TIMEOUT_MS = 7_000L
        const val STATUS_TIMEOUT_MS = 2_500L
        const val STUB_MEDIA_MAX_MS = 60_000L
        const val MAX_STUB_RETRIES = 2
        const val STUB_RETRY_DELAY_MS = 1_500L
        const val RAW_LABEL_LOG_LIMIT = 14
        const val MAX_RAW_LABEL_LENGTH = 70
        const val CONTROLS_TIMEOUT_MS = 8_000L
        const val PROGRESS_UPDATE_MS = 500L
        const val MAX_SOFTWARE_AV1_WIDTH = 1920
        const val MAX_SOFTWARE_AV1_HEIGHT = 1080
        const val WATCHED_EPISODE_PERCENT = 90
    }
}
