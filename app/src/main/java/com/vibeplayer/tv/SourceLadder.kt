package com.vibeplayer.tv

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException

internal enum class SourceKind {
    PRIMARY,
    CONTAINER_FALLBACK,
    RESERVE,
}

/** A stream address together with the container hint we intend to open it with. */
internal data class SourceCandidate(
    val url: String,
    val mimeType: String?,
    val kind: SourceKind,
)

internal enum class SourceFailure {
    /** The bytes are not the container the address promised. */
    CONTAINER_MISMATCH,

    /** The address itself did not deliver: bad status, missing file, dead host. */
    UNAVAILABLE,

    /** Nothing about a different address or container would help. */
    FATAL,
}

/**
 * Decides what to open next when a stream fails, without knowing anything about the
 * source that produced it.
 *
 * Two generic rules, each usable at most once, capped at [MAX_ATTEMPTS] in total so a
 * failing balancer can never be hammered:
 *
 *  1. A URL is a promise, the response is the fact. When a `.m3u8` address turns out to
 *     serve a plain media file — commonly because it redirects onto a CDN — reopen the
 *     address the data actually came from and let the extractors sniff the container.
 *  2. When an address does not deliver at all, move on to the reserves the source itself
 *     supplied alongside it.
 */
internal class SourceLadder(
    primaryUrl: String,
    primaryMimeType: String?,
    reserveUrls: List<String> = emptyList(),
) {
    private val reserves = ArrayDeque(
        reserveUrls
            .map(String::trim)
            .filter { it.isNotEmpty() && it != primaryUrl.trim() }
            .distinct(),
    )

    var current: SourceCandidate = SourceCandidate(primaryUrl, primaryMimeType, SourceKind.PRIMARY)
        private set

    private var attemptsUsed = 1
    private var containerFallbackUsed = false
    private var resolvedUrl: String? = null

    /**
     * The address the most recent load actually read from, after any server redirects.
     * Media3 reports it on load errors, so learning it costs no extra request.
     */
    fun noteResolvedLocation(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) resolvedUrl = trimmed
    }

    fun next(failure: SourceFailure): SourceCandidate? {
        if (failure == SourceFailure.FATAL || attemptsUsed >= MAX_ATTEMPTS) return null
        val candidate = containerFallback(failure) ?: nextReserve() ?: return null
        attemptsUsed += 1
        resolvedUrl = null
        current = candidate
        return candidate
    }

    private fun containerFallback(failure: SourceFailure): SourceCandidate? {
        if (failure != SourceFailure.CONTAINER_MISMATCH || containerFallbackUsed) return null
        // Already sniffing: a second guess at the container would be the same guess.
        if (current.mimeType == SNIFF_CONTAINER) return null
        containerFallbackUsed = true
        return SourceCandidate(resolvedUrl ?: current.url, SNIFF_CONTAINER, SourceKind.CONTAINER_FALLBACK)
    }

    private fun nextReserve(): SourceCandidate? = reserves
        .removeFirstOrNull()
        ?.let { url -> SourceCandidate(url, containerHint(url), SourceKind.RESERVE) }

    companion object {
        /** Total media opens per playback, including the first one. */
        const val MAX_ATTEMPTS = 3

        /**
         * Media3 routes an item to a manifest parser only for the m3u8/mpd/ism mime types.
         * Any other value puts it on the progressive path, where the extractors sniff the
         * real container from the bytes. This marker means "not a manifest" — it is not a
         * claim that the stream is MP4.
         */
        const val SNIFF_CONTAINER: String = MimeTypes.VIDEO_MP4

        /** The container a bare address suggests, or `null` when it suggests nothing. */
        fun containerHint(url: String): String? {
            val path = url.substringBefore('#').substringBefore('?')
            return when {
                path.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        fun classify(errorCode: Int): SourceFailure = when (errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> SourceFailure.CONTAINER_MISMATCH

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> SourceFailure.UNAVAILABLE

            else -> SourceFailure.FATAL
        }
    }
}
