package com.vibeplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a failure that reached the television first. They are the reason this
 * logic left the activity: each one looked like a different bug and came from the same place.
 */
class PlaybackSelectionTest {
    private fun episode(
        season: Int,
        number: Int,
        voice: String,
        quality: String = "Auto",
        url: String = "https://s.example/$voice/$season/$number/$quality",
    ) = Stream(url = url, quality = quality, voice = voice, season = season, episode = number)

    private val launchedQualities = listOf(
        Stream(url = "https://s.example/launched/1080", quality = "1080p"),
        Stream(url = "https://s.example/launched/720", quality = "720p"),
        Stream(url = "https://s.example/launched/480", quality = "480p"),
    )

    private val series = listOf(
        episode(1, 1, "AlexFilm"), episode(1, 2, "AlexFilm"), episode(1, 3, "AlexFilm"),
        episode(1, 1, "Coldfilm"), episode(1, 2, "Coldfilm"),
        episode(2, 1, "AlexFilm"),
    )

    @Test
    fun launchedQualitiesApplyUntilTheViewerLeavesThatStream() {
        val selection = PlaybackSelection(launchedQualities + series)
        val onLaunch = SelectionState(quality = "1080p", voice = "AlexFilm", season = 1, episode = 1)

        assertEquals(listOf("1080p", "720p", "480p"), selection.qualities(onLaunch).map { it.quality })

        val afterSwitch = onLaunch.copy(episode = 2, leftLaunchedStream = true)
        assertTrue(
            "the launched stream's qualities must not be offered for another episode",
            selection.qualities(afterSwitch).none { it.quality == "1080p" },
        )
    }

    @Test
    fun resolvedAddressesBecomeTheQualityMenu() {
        val resolved = listOf(
            Stream("https://s.example/e2/2160", "2160p"),
            Stream("https://s.example/e2/1080", "1080p"),
        )
        val selection = PlaybackSelection(launchedQualities + series, resolved)
        val state = SelectionState(quality = "1080p", season = 1, episode = 2, leftLaunchedStream = true)

        assertEquals(listOf("2160p", "1080p"), selection.qualities(state).map { it.quality })
    }

    @Test
    fun voicesAreThoseOfTheEpisodeBeingWatched() {
        val selection = PlaybackSelection(series)

        assertEquals(
            setOf("AlexFilm", "Coldfilm"),
            selection.voices(SelectionState(season = 1, episode = 1)).keys,
        )
        // Coldfilm has no third episode, so it is not offered there.
        assertEquals(
            setOf("AlexFilm"),
            selection.voices(SelectionState(season = 1, episode = 3)).keys,
        )
    }

    @Test
    fun voicesSurviveSwitchingEpisodeAndSwitchingVoice() {
        val selection = PlaybackSelection(series)
        val afterEpisodeSwitch = SelectionState(voice = "AlexFilm", season = 1, episode = 2, leftLaunchedStream = true)
        assertEquals(2, selection.voices(afterEpisodeSwitch).size)

        val afterVoiceSwitch = afterEpisodeSwitch.copy(voice = "Coldfilm")
        assertEquals(2, selection.voices(afterVoiceSwitch).size)
    }

    @Test
    fun oneVoiceNamedTwiceIsOneChoice() {
        val selection = PlaybackSelection(
            listOf(episode(1, 1, "AlexFilm"), episode(1, 1, "Алексфильм (AlexFilm)")),
        )

        val voices = selection.voices(SelectionState(season = 1, episode = 1))
        assertEquals(listOf("AlexFilm"), voices.keys.toList())
    }

    @Test
    fun switchingEpisodeStaysInTheVoiceWhenItHasThatEpisode() {
        val selection = PlaybackSelection(series)
        val state = SelectionState(voice = "Coldfilm", season = 1, episode = 1)

        assertEquals("Coldfilm", selection.pickEpisode(state, season = 1, episode = 2)?.voice)
    }

    @Test
    fun switchingToAnEpisodeThisVoiceLacksMovesToOneThatHasIt() {
        val selection = PlaybackSelection(series)
        val state = SelectionState(voice = "Coldfilm", season = 1, episode = 1)

        assertEquals("AlexFilm", selection.pickEpisode(state, season = 1, episode = 3)?.voice)
    }

    @Test
    fun switchingKeepsTheQualityAlreadyBeingWatched() {
        val streams = listOf(
            episode(1, 2, "AlexFilm", "2160p"),
            episode(1, 2, "AlexFilm", "1080p"),
            episode(1, 2, "AlexFilm", "480p"),
        )
        val selection = PlaybackSelection(streams)

        assertEquals(
            "1080p",
            selection.pickEpisode(SelectionState(quality = "1080p", voice = "AlexFilm"), 1, 2)?.quality,
        )
        // No exact match: the nearest height, not the largest.
        assertEquals(
            "1080p",
            selection.pickEpisode(SelectionState(quality = "1440p", voice = "AlexFilm"), 1, 2)?.quality,
        )
    }

    @Test
    fun seasonsAreThoseOfTheVoiceBeingWatched() {
        val selection = PlaybackSelection(series)

        assertEquals(listOf(1, 2), selection.seasons(SelectionState(voice = "AlexFilm")))
        assertEquals(listOf(1), selection.seasons(SelectionState(voice = "Coldfilm")))
    }

    @Test
    fun aMenuNeverRepeatsTheSameLabel() {
        val selection = PlaybackSelection(
            listOf(
                episode(1, 1, "AlexFilm", "Auto", "https://s.example/a"),
                episode(1, 1, "AlexFilm", "Auto", "https://s.example/b"),
                episode(1, 1, "AlexFilm", "1080p", "https://s.example/c"),
            ),
        )
        val state = SelectionState(voice = "AlexFilm", season = 1, episode = 1, leftLaunchedStream = true)

        assertEquals(listOf("1080p"), selection.qualities(state).map { it.quality })
    }

    @Test
    fun aSingleChoiceIsNoChoiceAndLeavesItToTheStream() {
        val selection = PlaybackSelection(listOf(episode(1, 5, "AlexFilm")))
        val state = SelectionState(voice = "AlexFilm", season = 1, episode = 5, leftLaunchedStream = true)

        assertTrue(selection.qualities(state).isEmpty())
    }

    @Test
    fun sourcesWithoutEpisodesStillOfferTheirVoices() {
        val selection = PlaybackSelection(
            listOf(
                Stream("https://s.example/dub", "1080p", voice = "Dub"),
                Stream("https://s.example/orig", "1080p", voice = "Original"),
            ),
        )

        assertEquals(setOf("Dub", "Original"), selection.voices(SelectionState()).keys)
    }
}
