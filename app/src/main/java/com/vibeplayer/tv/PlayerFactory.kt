package com.vibeplayer.tv

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
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
            // Sources hand out stream tokens bound to the address they saw. The WebView that
            // obtained the token resolves normally and may well reach the host over IPv6, so
            // an IPv4-only player arrives with a different client address and gets refused.
            // Resolve the same way the browser does and the two stay on one address.
            .includeIPv6(true)
            .post(true)
            .resolvePrivateAddresses(true)
            .build()
        val httpClient = bootstrapClient.newBuilder()
            .dns(dnsOverHttps)
            .eventListener(ConnectionLogger)
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

    private const val FALLBACK_USER_AGENT = "VibePlayer/0.25 (TCL EP680; Android 9)"

    /**
     * Reports which address family each media host was actually reached over, once per host.
     * A token issued to one family and redeemed from the other is exactly the failure this
     * exists to make visible; host names and server addresses are not user data.
     */
    private object ConnectionLogger : okhttp3.EventListener() {
        private val reported = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        override fun connectEnd(
            call: okhttp3.Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: okhttp3.Protocol?,
        ) {
            val host = call.request().url.host
            val address = inetSocketAddress.address ?: return
            val family = if (address is java.net.Inet6Address) "IPv6" else "IPv4"
            if (reported.add("$host/$family")) {
                Log.i("VibePlayer", "Connected $host over $family (${address.hostAddress})")
            }
        }
    }

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
