package com.vibeplayer.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class PlaybackRequest(
    val uri: Uri,
    val mimeType: String?,
    val title: String?,
    val sourceName: String?,
    val headers: Map<String, String>,
    val startPositionMs: Long,
    val qualityVariants: List<QualityVariant>,
) {
    val safeLocation: String
        get() = LocationRedactor.redact(uri.toString())

    companion object {
        private const val MEDIA_HTTP_HEADERS = "android.media.intent.extra.HTTP_HEADERS"
        private const val GENERIC_HTTP_HEADERS = "android.intent.extra.HTTP_HEADERS"

        fun from(intent: Intent): Result<PlaybackRequest> = runCatching {
            val uri = requireNotNull(intent.data) { "Missing media URI" }
            require(uri.scheme?.lowercase() in setOf("http", "https", "content", "file")) {
                "Unsupported URI scheme"
            }

            val headers = HeaderParser.mergeByPriority(
                bundleToMap(intent.getBundleExtra(MEDIA_HTTP_HEADERS)),
                bundleToMap(intent.getBundleExtra(GENERIC_HTTP_HEADERS)),
                intent.getStringArrayExtra("headers"),
            )
            val bridgeMetadata = QualityVariantParser.metadataFromIntent(intent)

            PlaybackRequest(
                uri = uri,
                mimeType = normalizeMimeType(intent.type, uri),
                title = cleanMetadataValue(
                    intent.getStringExtra(Intent.EXTRA_TITLE)
                        ?: intent.getStringExtra("title")
                        ?: bridgeMetadata?.title,
                ),
                sourceName = cleanMetadataValue(
                    bridgeMetadata?.source
                        ?: intent.getStringExtra("source_name")
                        ?: intent.getStringExtra("source")
                        ?: intent.getStringExtra("provider"),
                ),
                headers = headers,
                startPositionMs = (intent.extras?.get("position") as? Number)
                    ?.toLong()
                    ?.coerceAtLeast(0L)
                    ?: 0L,
                qualityVariants = QualityVariantParser.fromIntent(intent),
            )
        }

        private fun bundleToMap(bundle: Bundle?): Map<String, String> {
            if (bundle == null) return emptyMap()
            return buildMap {
                bundle.keySet().forEach { key ->
                    (bundle.get(key) as? String)?.let { value -> put(key, value) }
                }
            }
        }

        private fun cleanMetadataValue(value: String?): String? = value
            ?.replace(Regex("[\\p{Cc}]"), " ")
            ?.trim()
            ?.take(MAX_METADATA_LENGTH)
            ?.takeIf(String::isNotEmpty)

        private fun normalizeMimeType(intentType: String?, uri: Uri): String? {
            return when (intentType?.lowercase()) {
                "application/vnd.apple.mpegurl", "application/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                null, "video/*" -> when {
                    uri.toString().substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                    uri.toString().substringBefore('?').endsWith(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
                else -> intentType
            }
        }

        private const val MAX_METADATA_LENGTH = 160
    }
}

internal data class QualityVariant(
    val label: String,
    val uri: Uri,
    val voiceoverLabel: String? = null,
    val episode: EpisodeVariantInfo? = null,
) {
    val height: Int?
        get() = QualityVariantParser.heightFromLabel(label)
}

internal data class EpisodeVariantInfo(
    val season: Int,
    val episode: Int,
    val title: String?,
    val watchedPercent: Int,
    val resumePositionMs: Long,
)

internal data class BridgeMetadata(
    val title: String?,
    val source: String?,
)

internal object QualityVariantParser {
    @Suppress("DEPRECATION")
    fun fromIntent(intent: Intent): List<QualityVariant> {
        val labels = intent.getStringArrayExtra("quality_levels")?.toList().orEmpty()
        val rawUrls = intent.extras?.get("quality_urls")
        val values: List<Any?> = when (rawUrls) {
            is Array<*> -> rawUrls.toList()
            is List<*> -> rawUrls
            else -> intent.getStringArrayExtra("quality_urls")?.toList().orEmpty()
        }

        // Official Lampa can send either a direct Uri/String or a JSON object from
        // its source resolver. Keep values paired with their labels so one bad
        // entry cannot shift every following quality label onto the wrong URL.
        return labels.zip(values)
            .mapNotNull { (label, value) ->
                val uri = uriFromValue(value) ?: return@mapNotNull null
                val parsedLabel = parseLabel(label) ?: return@mapNotNull null
                QualityVariant(
                    label = parsedLabel.quality,
                    uri = uri,
                    voiceoverLabel = parsedLabel.voiceover,
                    episode = parsedLabel.episode,
                )
            }
            .distinctBy { listOf(it.voiceoverLabel, it.episode?.season, it.episode?.episode, it.label, it.uri) }
    }

    fun metadataFromIntent(intent: Intent): BridgeMetadata? = intent
        .getStringArrayExtra("quality_levels")
        ?.firstNotNullOfOrNull(::parseMetadataLabel)

    private fun uriFromValue(value: Any?): Uri? {
        val raw = when (value) {
            is Uri -> value.toString()
            is String -> value
            else -> return null
        }
        val extracted = extractUrl(raw) ?: return null
        return Uri.parse(extracted).takeIf {
            it.scheme?.lowercase() in setOf("http", "https", "content", "file")
        }
    }

    internal fun extractUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith('{')) return trimmed
        return runCatching { JSONObject(trimmed).optString("url").trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
    }

    fun zip(labels: List<String>, urls: List<Uri>): List<QualityVariant> =
        labels.zip(urls)
            .mapNotNull { (label, uri) ->
                val parsedLabel = parseLabel(label) ?: return@mapNotNull null
                QualityVariant(parsedLabel.quality, uri, parsedLabel.voiceover, parsedLabel.episode)
            }
            .distinctBy { listOf(it.voiceoverLabel, it.episode?.season, it.episode?.episode, it.label, it.uri) }

    internal fun parseLabel(rawLabel: String): ParsedVariantLabel? {
        val trimmed = rawLabel.trim().takeIf(String::isNotEmpty) ?: return null
        if (trimmed.startsWith(METADATA_PREFIX)) return null
        if (trimmed.startsWith(EPISODE_PREFIX)) return parseEpisodeLabel(trimmed)
        if (!trimmed.startsWith(VOICEOVER_PREFIX)) return ParsedVariantLabel(trimmed)

        val parts = trimmed.removePrefix(VOICEOVER_PREFIX).split('|', limit = 2)
        if (parts.size != 2) return ParsedVariantLabel(trimmed)
        return runCatching {
            val voiceover = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()).trim()
            val quality = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()).trim()
            require(voiceover.isNotEmpty() && quality.isNotEmpty())
            ParsedVariantLabel(quality, voiceover = voiceover)
        }.getOrElse { ParsedVariantLabel(trimmed) }
    }

    internal fun parseMetadataLabel(rawLabel: String): BridgeMetadata? {
        val trimmed = rawLabel.trim()
        if (!trimmed.startsWith(METADATA_PREFIX)) return null
        val parts = trimmed.removePrefix(METADATA_PREFIX).split('|', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val title = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()).trim().ifEmpty { null }
            val source = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()).trim().ifEmpty { null }
            BridgeMetadata(title, source).takeIf { it.title != null || it.source != null }
        }.getOrNull()
    }

    private fun parseEpisodeLabel(rawLabel: String): ParsedVariantLabel {
        val parts = rawLabel.removePrefix(EPISODE_PREFIX).split('|', limit = 6)
        if (parts.size != 6) return ParsedVariantLabel(rawLabel)
        return runCatching {
            val season = parts[0].toInt().coerceAtLeast(0)
            val episodeNumber = parts[1].toInt().coerceAtLeast(0)
            val percent = parts[2].toInt().coerceIn(0, 100)
            val positionMs = parts[3].toLong().coerceAtLeast(0L) * 1_000L
            val title = URLDecoder.decode(parts[4], StandardCharsets.UTF_8.name()).trim().ifEmpty { null }
            val quality = URLDecoder.decode(parts[5], StandardCharsets.UTF_8.name()).trim()
            require(episodeNumber > 0 && quality.isNotEmpty())
            ParsedVariantLabel(
                quality = quality,
                episode = EpisodeVariantInfo(season, episodeNumber, title, percent, positionMs),
            )
        }.getOrElse { ParsedVariantLabel(rawLabel) }
    }

    fun heightFromLabel(label: String): Int? {
        val normalized = label.lowercase().replace(" ", "")
        return when {
            "4k" in normalized || "uhd" in normalized -> 2160
            "2k" in normalized -> 1440
            else -> Regex("(2160|1440|1080|720|576|480|360)p?")
                .find(normalized)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        }
    }

    internal data class ParsedVariantLabel(
        val quality: String,
        val voiceover: String? = null,
        val episode: EpisodeVariantInfo? = null,
    )

    private const val VOICEOVER_PREFIX = "@VIBEVOICE@"
    private const val EPISODE_PREFIX = "@VIBEEPISODE@"
    private const val METADATA_PREFIX = "@VIBEMETA@"
}

internal object LocationRedactor {
    private val safeMediaExtensions = setOf("m3u8", "mpd", "mp4", "mkv", "webm")

    fun redact(rawLocation: String): String = runCatching {
        val uri = java.net.URI(rawLocation)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching "redacted"
        if (scheme !in setOf("http", "https")) return@runCatching "$scheme://…"

        val fileName = uri.path
            ?.substringAfterLast('/')
            ?.takeIf { name -> name.substringAfterLast('.', "").lowercase() in safeMediaExtensions }
            ?.takeLast(MAX_FILE_NAME_LENGTH)
        buildString {
            append(scheme)
            append("://")
            append(uri.host ?: "redacted")
            append("/…")
            fileName?.let {
                append('/')
                append(it)
            }
        }
    }.getOrDefault("redacted")

    private const val MAX_FILE_NAME_LENGTH = 96
}
