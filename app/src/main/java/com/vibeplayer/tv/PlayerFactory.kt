package com.vibeplayer.tv

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@UnstableApi
internal object PlayerFactory {
    data class Result(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector,
    )

    fun create(
        context: Context,
        request: PlaybackRequest,
        attempt: PlaybackAttempt,
        audioOffsetProcessor: AudioOffsetProcessor,
        nightModeAudioProcessor: NightModeAudioProcessor,
    ): Result {
        val codecSelector = if (attempt == PlaybackAttempt.BASE_LAYER) {
            MediaCodecSelector { mimeType, secure, tunneling ->
                if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                    emptyList()
                } else {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                }
            }
        } else {
            MediaCodecSelector.DEFAULT
        }

        val renderersFactory = VibeRenderersFactory(context, audioOffsetProcessor, nightModeAudioProcessor)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .clearViewportSizeConstraints()
                .clearVideoSizeConstraints()
                .setMaxVideoSize(4096, 2176)
                .setPreferredVideoMimeTypes(
                    MimeTypes.VIDEO_H265,
                    MimeTypes.VIDEO_H264,
                    MimeTypes.VIDEO_VP9,
                    MimeTypes.VIDEO_DOLBY_VISION,
                ),
        )

        val activeNetwork = context
            .getSystemService(ConnectivityManager::class.java)
            ?.activeNetwork
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(15_000, TimeUnit.MILLISECONDS)
            .readTimeout(30_000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply {
                if (activeNetwork != null) {
                    socketFactory(activeNetwork.socketFactory)
                }
            }
            .build()
        val dnsOverHttps = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(CLOUDFLARE_DNS_ADDRESSES)
            .includeIPv6(false)
            .post(true)
            .resolvePrivateAddresses(true)
            .build()
        val httpClient = bootstrapClient.newBuilder()
            .dns(dnsOverHttps)
            .build()

        // OkHttpDataSource applies its own user agent with addHeader(), after the request
        // properties have already been set. Supplying both therefore puts *two* User-Agent
        // lines on every request — the source's own and ours — which some CDNs read as a
        // bot. Ours is only a last resort, for callers that sent no user agent at all.
        val httpFactory = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(request.headers)
            .apply {
                if (HeaderParser.valueOf(request.headers, "User-Agent") == null) {
                    setUserAgent(FALLBACK_USER_AGENT)
                }
            }

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setReleaseTimeoutMs(10_000L)
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )

        return Result(player, trackSelector)
    }

    fun mediaItem(source: SourceCandidate): MediaItem {
        return MediaItem.Builder()
            .setUri(source.url)
            .apply { source.mimeType?.let(::setMimeType) }
            .build()
    }

    private const val FALLBACK_USER_AGENT = "VibePlayer/0.21 (TCL EP680; Android 9)"

    private val CLOUDFLARE_DNS_ADDRESSES = listOf(
        InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
        InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)),
    )
}

@UnstableApi
private class VibeRenderersFactory(
    context: Context,
    private val audioOffsetProcessor: AudioOffsetProcessor,
    private val nightModeAudioProcessor: NightModeAudioProcessor,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf(audioOffsetProcessor, nightModeAudioProcessor))
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
