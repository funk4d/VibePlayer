package com.vibeplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeaderParserTest {
    @Test
    fun findsHeadersRegardlessOfHowTheSourceSpelledThem() {
        val headers = mapOf(" user-agent " to "Lampa WebView", "Referer" to "http://lampa.mx/")

        assertEquals("Lampa WebView", HeaderParser.valueOf(headers, "User-Agent"))
        assertEquals("http://lampa.mx/", HeaderParser.valueOf(headers, "referer"))
        assertEquals(null, HeaderParser.valueOf(headers, "Cookie"))
    }

    @Test
    fun parsesAlternatingNameValueArray() {
        val parsed = HeaderParser.parseRaw(arrayOf("Referer", "https://example.test/", "Cookie", "a=b"))
        assertEquals("https://example.test/", parsed["Referer"])
        assertEquals("a=b", parsed["Cookie"])
    }

    @Test
    fun parsesColonEntriesAndKeepsColonInValue() {
        val parsed = HeaderParser.parseRaw(arrayOf("Referer: https://example.test:8443/page"))
        assertEquals("https://example.test:8443/page", parsed["Referer"])
    }

    @Test
    fun rejectsHeaderInjection() {
        val parsed = HeaderParser.parseRaw(arrayOf("Cookie", "safe\r\nInjected: bad"))
        assertFalse(parsed.containsKey("Cookie"))
    }

    @Test
    fun primaryHeadersWinCaseInsensitively() {
        val parsed = HeaderParser.mergeByPriority(
            primary = mapOf("User-Agent" to "primary"),
            secondary = mapOf("user-agent" to "secondary"),
            rawValues = arrayOf("USER-AGENT", "raw"),
        )
        assertEquals(1, parsed.size)
        assertEquals("primary", parsed["User-Agent"])
    }
}

