package com.vibeplayer.tv

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Hands this session's watch progress to the Lampa bridge.
 *
 * Lampa attributes a playback result to the entry it launched, so episodes chosen inside the
 * player leave no trace in its timeline. The bridge can record them itself - Lampa.Timeline
 * .update is the same call Lampa uses - but it runs as a page inside a WebView and cannot be
 * addressed from another process. So the player offers a door instead: a loopback endpoint
 * the page can read whenever it comes back to the foreground.
 *
 * Bound to the loopback address only. Nothing here ever leaves the device, and the source is
 * never contacted - this exists precisely so that no extra request has to be made.
 */
internal object WatchProgressServer {
    const val PORT = 47615

    private data class Entry(val timeSec: Long, val durationSec: Long, val percent: Int)

    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Any()
    private var server: ServerSocket? = null

    /** Identifies this run, so the page can tell fresh progress from what it already applied. */
    val session: String = java.util.UUID.randomUUID().toString().take(8)

    fun start() {
        synchronized(lock) {
            if (server != null) return
            val socket = runCatching {
                ServerSocket(PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
            }.getOrElse { error ->
                Log.w(TAG, "Progress endpoint unavailable: ${error.javaClass.simpleName}")
                return
            }
            server = socket
            thread(isDaemon = true, name = "vibe-progress") { serve(socket) }
            Log.i(TAG, "Progress endpoint listening on 127.0.0.1:$PORT session=$session")
        }
    }

    /**
     * Records where an episode was left. Only the furthest point is kept: rewinding to check
     * something is not the same as having watched less.
     */
    fun record(hash: String?, positionMs: Long, durationMs: Long) {
        if (hash.isNullOrBlank() || positionMs <= 0L || durationMs <= 0L) return
        val timeSec = positionMs / 1_000L
        val durationSec = durationMs / 1_000L
        val percent = ((positionMs * 100L) / durationMs).coerceIn(0L, 100L).toInt()
        synchronized(lock) {
            val known = entries[hash]
            if (known != null && known.timeSec >= timeSec) return
            entries[hash] = Entry(timeSec, durationSec, percent)
        }
    }

    private fun snapshot(): String = synchronized(lock) {
        val items = entries.entries.joinToString(",") { (hash, entry) ->
            """{"hash":"${hash.replace("\"", "")}","time":${entry.timeSec},""" +
                """"duration":${entry.durationSec},"percent":${entry.percent}}"""
        }
        """{"session":"$session","items":[$items]}"""
    }

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            runCatching { respond(client) }
            runCatching { client.close() }
        }
    }

    private fun respond(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine().orEmpty()
        // Drain the headers so the client sees a clean response rather than a reset.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val body = if (requestLine.startsWith("GET /progress")) snapshot() else "{}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            // The page lives on the source's origin, so it needs permission to read this.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private const val TAG = "VibePlayer"
    private const val BACKLOG = 4
    private const val CLIENT_TIMEOUT_MS = 3_000
}
