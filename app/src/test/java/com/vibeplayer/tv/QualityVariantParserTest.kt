package com.vibeplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityVariantParserTest {
    @Test
    fun understandsCommonQualityLabels() {
        assertEquals(2160, QualityVariantParser.heightFromLabel("4K HDR"))
        assertEquals(1440, QualityVariantParser.heightFromLabel("2K"))
        assertEquals(1080, QualityVariantParser.heightFromLabel("1080p"))
        assertEquals(720, QualityVariantParser.heightFromLabel("HD 720"))
    }

    @Test
    fun extractsUrlFromLampaJsonVariant() {
        val wrapped =
            """{"label":"2K","url":"https:\/\/media.example\/stream\/file.m3u8?token=secret","reserve":[]}"""

        assertEquals(
            "https://media.example/stream/file.m3u8?token=secret",
            QualityVariantParser.extractUrl(wrapped),
        )
    }

    @Test
    fun preservesDirectVariantUrl() {
        assertEquals(
            "https://media.example/file.m3u8",
            QualityVariantParser.extractUrl(" https://media.example/file.m3u8 "),
        )
    }

    @Test
    fun decodesVoiceoverBridgeLabel() {
        val parsed = QualityVariantParser.parseLabel(
            "@VIBEVOICE@%D0%94%D1%83%D0%B1%D0%BB%D1%8F%D0%B6|1080p",
        )

        assertEquals("Дубляж", parsed?.voiceover)
        assertEquals("1080p", parsed?.quality)
    }

    @Test
    fun leavesOrdinaryQualityLabelUntouched() {
        val parsed = QualityVariantParser.parseLabel("4K HDR")

        assertEquals(null, parsed?.voiceover)
        assertEquals("4K HDR", parsed?.quality)
    }

    @Test
    fun decodesEpisodeBridgeLabel() {
        val parsed = QualityVariantParser.parseLabel(
            "@VIBEEPISODE@2|8|94|122|The%20Finale|1080p",
        )

        assertEquals("1080p", parsed?.quality)
        assertEquals(2, parsed?.episode?.season)
        assertEquals(8, parsed?.episode?.episode)
        assertEquals("The Finale", parsed?.episode?.title)
        assertEquals(94, parsed?.episode?.watchedPercent)
        assertEquals(122_000L, parsed?.episode?.resumePositionMs)
    }

    @Test
    fun decodesMetadataBridgeLabel() {
        val parsed = QualityVariantParser.parseMetadataLabel(
            "@VIBEMETA@The%20Series|Alloha",
        )

        assertEquals("The Series", parsed?.title)
        assertEquals("Alloha", parsed?.source)
        assertEquals(null, QualityVariantParser.parseLabel("@VIBEMETA@The%20Series|Alloha"))
    }
}
