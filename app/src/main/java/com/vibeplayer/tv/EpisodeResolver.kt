package com.vibeplayer.tv

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Asks a source for the addresses of one episode.
 *
 * A source keeps a single default address per episode and hands out the per-quality ones only
 * when asked. Lampa asks when the viewer picks an episode; the player has to ask for the same
 * reason, or an episode chosen here plays at whatever the source considers default - 4K AV1
 * on a television that decodes it in software.
 *
 * One request per episode, made when that episode is chosen and never speculatively, and the
 * answer is kept so returning to an episode costs nothing.
 */
internal object EpisodeResolver {
    data class Resolved(val defaultUrl: String?, val qualities: Map<String, String>)

    private val cache = java.util.Collections.synchronizedMap(HashMap<String, Resolved>())
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    fun cached(endpoint: String): Resolved? = cache[endpoint]

    /** Blocking; call from a background thread. Returns null when the source says nothing useful. */
    fun resolve(endpoint: String, headers: Map<String, String>): Resolved? {
        cache[endpoint]?.let { return it }

        val request = Request.Builder()
            .url(endpoint)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val body = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return null

        val resolved = runCatching { parse(body) }.getOrNull() ?: return null
        if (resolved.defaultUrl == null && resolved.qualities.isEmpty()) return null
        cache[endpoint] = resolved
        Log.i(TAG, "Resolved episode qualities=${resolved.qualities.keys}")
        return resolved
    }

    private fun parse(body: String): Resolved {
        val json = JSONObject(body)
        val qualities = LinkedHashMap<String, String>()
        json.optJSONObject("quality")?.let { map ->
            map.keys().forEach { label ->
                val value = map.opt(label)
                val url = when (value) {
                    is String -> value
                    is JSONObject -> value.optString("url").takeIf(String::isNotEmpty)
                    else -> null
                }
                if (!url.isNullOrBlank()) qualities[label] = url
            }
        }
        return Resolved(json.optString("url").takeIf(String::isNotEmpty), qualities)
    }

    private const val TAG = "VibePlayer"
    private const val TIMEOUT_MS = 12_000L
}
