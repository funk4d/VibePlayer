package com.vibeplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocationRedactorTest {
    @Test
    fun removesSignedPathAndQueryButKeepsMediaFileName() {
        val redacted = LocationRedactor.redact(
            "http://media.example/on/play/secret-token/Episode.S01E01.m3u8?auth=also-secret",
        )

        assertEquals("http://media.example/…/Episode.S01E01.m3u8", redacted)
        assertFalse(redacted.contains("secret"))
    }

    @Test
    fun hidesNonMediaLastSegment() {
        assertEquals(
            "https://media.example/…",
            LocationRedactor.redact("https://media.example/stream/secret-token"),
        )
    }

    @Test
    fun hidesLocalPaths() {
        assertEquals("file://…", LocationRedactor.redact("file:///storage/movie.mkv"))
    }
}
