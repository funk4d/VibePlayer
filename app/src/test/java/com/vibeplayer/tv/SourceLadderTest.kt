package com.vibeplayer.tv

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLadderTest {
    @Test
    fun reopensRedirectTargetWhenPlaylistTurnsOutToBeAFile() {
        val ladder = SourceLadder("https://balancer.example/v/17.m3u8", MimeTypes.APPLICATION_M3U8)
        ladder.noteResolvedLocation("https://cdn.example/a/b.mp4")

        val next = ladder.next(SourceFailure.CONTAINER_MISMATCH)

        assertEquals("https://cdn.example/a/b.mp4", next?.url)
        assertEquals(SourceKind.CONTAINER_FALLBACK, next?.kind)
        assertEquals(SourceLadder.SNIFF_CONTAINER, next?.mimeType)
        assertEquals(next, ladder.current)
    }

    @Test
    fun stopsTrustingTheAddressEvenWithoutARedirect() {
        val ladder = SourceLadder("https://balancer.example/v/17.m3u8", MimeTypes.APPLICATION_M3U8)

        val next = ladder.next(SourceFailure.CONTAINER_MISMATCH)

        assertEquals("https://balancer.example/v/17.m3u8", next?.url)
        assertEquals(SourceLadder.SNIFF_CONTAINER, next?.mimeType)
    }

    @Test
    fun guessesContainerOnlyForManifestExtensions() {
        assertEquals(MimeTypes.APPLICATION_M3U8, SourceLadder.containerHint("https://a.example/x.m3u8?t=1"))
        assertEquals(MimeTypes.APPLICATION_MPD, SourceLadder.containerHint("https://a.example/x.mpd#f"))
        assertNull(SourceLadder.containerHint("https://a.example/x.mp4"))
        assertNull(SourceLadder.containerHint("https://a.example/stream"))
    }

    @Test
    fun fallsBackToReservesWhenAnAddressDoesNotDeliver() {
        val ladder = SourceLadder(
            primaryUrl = "https://dead.example/a.mp4",
            primaryMimeType = null,
            reserveUrls = listOf("https://backup.example/a.m3u8"),
        )

        val next = ladder.next(SourceFailure.UNAVAILABLE)

        assertEquals("https://backup.example/a.m3u8", next?.url)
        assertEquals(SourceKind.RESERVE, next?.kind)
        assertEquals(MimeTypes.APPLICATION_M3U8, next?.mimeType)
    }

    @Test
    fun triesEachReserveOnceAndThenGivesUp() {
        val ladder = SourceLadder(
            primaryUrl = "https://dead.example/a.mp4",
            primaryMimeType = null,
            reserveUrls = listOf("https://one.example/a.mp4", "https://two.example/a.mp4"),
        )

        assertEquals("https://one.example/a.mp4", ladder.next(SourceFailure.UNAVAILABLE)?.url)
        assertEquals("https://two.example/a.mp4", ladder.next(SourceFailure.UNAVAILABLE)?.url)
        assertNull(ladder.next(SourceFailure.UNAVAILABLE))
    }

    @Test
    fun neverOpensMoreThanTheAttemptCap() {
        val ladder = SourceLadder(
            primaryUrl = "https://dead.example/a.m3u8",
            primaryMimeType = MimeTypes.APPLICATION_M3U8,
            reserveUrls = listOf("https://one.example/a.mp4", "https://two.example/a.mp4"),
        )

        assertEquals(SourceKind.CONTAINER_FALLBACK, ladder.next(SourceFailure.CONTAINER_MISMATCH)?.kind)
        assertEquals(SourceKind.RESERVE, ladder.next(SourceFailure.CONTAINER_MISMATCH)?.kind)
        assertNull(ladder.next(SourceFailure.UNAVAILABLE))
    }

    @Test
    fun doesNotGuessTheContainerTwice() {
        val ladder = SourceLadder("https://a.example/x.m3u8", MimeTypes.APPLICATION_M3U8)
        ladder.next(SourceFailure.CONTAINER_MISMATCH)

        assertNull(ladder.next(SourceFailure.CONTAINER_MISMATCH))
    }

    @Test
    fun ignoresReservesThatRepeatThePrimaryAddress() {
        val ladder = SourceLadder(
            primaryUrl = "https://a.example/x.mp4",
            primaryMimeType = null,
            reserveUrls = listOf(" https://a.example/x.mp4 ", "", "https://b.example/x.mp4", "https://b.example/x.mp4"),
        )

        assertEquals("https://b.example/x.mp4", ladder.next(SourceFailure.UNAVAILABLE)?.url)
        assertNull(ladder.next(SourceFailure.UNAVAILABLE))
    }

    @Test
    fun doesNotRetryFailuresAnotherAddressCannotFix() {
        val ladder = SourceLadder(
            primaryUrl = "https://a.example/x.mp4",
            primaryMimeType = null,
            reserveUrls = listOf("https://b.example/x.mp4"),
        )

        assertNull(ladder.next(SourceFailure.FATAL))
    }

    @Test
    fun readsTheContainerMismatchOutOfMedia3ErrorCodes() {
        assertEquals(
            SourceFailure.CONTAINER_MISMATCH,
            SourceLadder.classify(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED),
        )
        assertEquals(
            SourceFailure.CONTAINER_MISMATCH,
            SourceLadder.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            SourceFailure.UNAVAILABLE,
            SourceLadder.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            SourceFailure.FATAL,
            SourceLadder.classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
        )
    }
}
