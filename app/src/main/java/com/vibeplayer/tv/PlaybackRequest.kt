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
    /** True when [mimeType] is a guess from the address, not something the source declared. */
    val mimeTypeInferred: Boolean,
    val title: String?,
    val sourceName: String?,
    val headers: Map<String, String>,
    val startPositionMs: Long,
    val qualityVariants: List<QualityVariant>,
    val reserveUrls: List<String> = emptyList(),
    val bridgeProbe: String? = null,
    val currentEpisode: EpisodeVariantInfo? = null,
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

            val declared = declaredMimeType(intent.type)

            PlaybackRequest(
                uri = uri,
                mimeType = declared ?: SourceLadder.containerHint(uri.toString()),
                mimeTypeInferred = declared == null,
                title = cleanMetadataValue(
                    bridgeMetadata?.title
                        ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                        ?: intent.getStringExtra("title"),
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
                reserveUrls = QualityVariantParser.reservesFromIntent(intent),
                bridgeProbe = bridgeMetadata?.probe,
                currentEpisode = bridgeMetadata?.episode?.let { number ->
                    EpisodeVariantInfo(
                        season = bridgeMetadata.season ?: 0,
                        episode = number,
                        title = null,
                        watchedPercent = 0,
                        resumePositionMs = 0L,
                        voice = bridgeMetadata.voice,
                    )
                },
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

        /**
         * The container the caller actually committed to, or `null` when it committed to
         * nothing. Lampa hands over a wildcard video type, i.e. no container at all, which
         * leaves the address as the only hint — a guess [SourceLadder] may revise once the
         * response proves it wrong.
         */
        private fun declaredMimeType(intentType: String?): String? {
            return when (val normalized = intentType?.lowercase()) {
                "application/vnd.apple.mpegurl", "application/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                null, "video/*", "*/*" -> null
                else -> normalized
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
    /** Which voice this particular stream of the episode is in. */
    val voice: String? = null,
    /** How Lampa identifies this episode in its own timeline. */
    val timelineHash: String? = null,
    /** Where this episode's per-quality addresses can be asked for, when it has more. */
    val resolveUrl: String? = null,
)

internal data class BridgeMetadata(
    val title: String?,
    val source: String?,
    /** Opaque structural summary of the bridge's capture, for logs only. */
    val probe: String? = null,
    // What is playing right now. The launched address is the one for the chosen quality and
    // matches no entry's own address, so the current position has to be stated outright.
    val season: Int? = null,
    val episode: Int? = null,
    val voice: String? = null,
)

internal object QualityVariantParser {
    @Suppress("DEPRECATION")
    private fun labeledValues(intent: Intent): List<Pair<String, Any?>> {
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
    }

    fun fromIntent(intent: Intent): List<QualityVariant> {
        return labeledValues(intent)
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

    /**
     * Backup addresses the source shipped next to the chosen one. They are never offered
     * in the quality menu — they only exist for [SourceLadder] to fall back to.
     */
    fun reservesFromIntent(intent: Intent): List<String> = labeledValues(intent)
        .mapNotNull { (label, value) ->
            val order = parseReserveLabel(label) ?: return@mapNotNull null
            val url = uriFromValue(value)?.toString() ?: return@mapNotNull null
            order to url
        }
        .sortedBy { (order, _) -> order }
        .map { (_, url) -> url }
        .distinct()

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
        if (trimmed.startsWith(RESERVE_PREFIX)) return null
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
        val parts = trimmed.removePrefix(METADATA_PREFIX).split('|', limit = 6)
        if (parts.size < 2) return null
        return runCatching {
            val title = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()).trim().ifEmpty { null }
            val source = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()).trim().ifEmpty { null }
            val probe = parts.getOrNull(2)?.trim()?.takeIf { it.matches(PROBE_FORMAT) }
            val season = parts.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            val episode = parts.getOrNull(4)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val voice = parts.getOrNull(5)
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()).trim() }
                ?.ifEmpty { null }
            BridgeMetadata(title, source, probe, season, episode, voice)
                .takeIf { it.title != null || it.source != null || probe != null }
        }.getOrNull()
    }

    /** Returns the position of a reserve address within the source's own ordering. */
    internal fun parseReserveLabel(rawLabel: String): Int? {
        val trimmed = rawLabel.trim()
        if (!trimmed.startsWith(RESERVE_PREFIX)) return null
        return trimmed.removePrefix(RESERVE_PREFIX)
            .substringBefore('|')
            .toIntOrNull()
            ?.takeIf { it >= 0 }
    }

    private fun parseEpisodeLabel(rawLabel: String): ParsedVariantLabel {
        val parts = rawLabel.removePrefix(EPISODE_PREFIX).split('|', limit = 9)
        if (parts.size < 6) return ParsedVariantLabel(rawLabel)
        return runCatching {
            val season = parts[0].toInt().coerceAtLeast(0)
            val episodeNumber = parts[1].toInt().coerceAtLeast(0)
            val percent = parts[2].toInt().coerceIn(0, 100)
            val positionMs = parts[3].toLong().coerceAtLeast(0L) * 1_000L
            val title = URLDecoder.decode(parts[4], StandardCharsets.UTF_8.name()).trim().ifEmpty { null }
            val quality = URLDecoder.decode(parts[5], StandardCharsets.UTF_8.name()).trim()
            require(episodeNumber > 0 && quality.isNotEmpty())
            val voice = parts.getOrNull(6)
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()).trim() }
                ?.ifEmpty { null }
            val hash = parts.getOrNull(7)
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()).trim() }
                ?.ifEmpty { null }
            val resolveUrl = parts.getOrNull(8)
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()).trim() }
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ParsedVariantLabel(
                quality = quality,
                episode = EpisodeVariantInfo(
                    season, episodeNumber, title, percent, positionMs, voice, hash, resolveUrl,
                ),
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
    private const val RESERVE_PREFIX = "@VIBERESERVE@"

    /** Structural counters only, so nothing from a stream URL can reach a log through here. */
    private val PROBE_FORMAT = Regex("c[01]p\\d{1,4}v\\d{1,4}f\\d{1,4}(s\\d{1,4}w\\d{1,4})?")
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
