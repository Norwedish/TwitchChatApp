package com.norwedish.twitchchatapp

/**
 * Local HTTP proxy used to rewrite Twitch HLS playlists so receivers (Cast) can access segments via LAN.
 * This module implements a tiny embedded server and playlist/segment rewrites.
 */

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.InetAddress
import java.net.NetworkInterface

object LocalProxyServer {
    private const val TAG = "LocalProxyServer"
    private var server: NanoProxy? = null
    private var port: Int = 0
    private var baseUrlCache: String? = null
    // Diagnostics
    @Volatile
    var lastPlaylistRequested: String? = null
        private set
    @Volatile
    var segmentRequestCount: Int = 0
        private set
    @Volatile
    var lastSegmentStatus: Int? = null
        private set

    // Start the local proxy on an automatically chosen free port.
    // onStarted(baseUrl) will be invoked on the main thread with a reachable base URL (http://<device-ip>:port)
    fun start(onStarted: ((baseUrl: String) -> Unit)? = null) {
        if (server != null) {
            onStarted?.invoke(getBaseUrl())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = NanoProxy(0)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                port = s.listeningPort

                // Determine a LAN-reachable IP address to present to Chromecast
                val ip = findLocalIpAddress() ?: "127.0.0.1"
                baseUrlCache = "http://$ip:$port"

                val base = baseUrlCache ?: ""
                Log.i(TAG, "Local proxy started at $base")
                withContext(Dispatchers.Main) {
                    onStarted?.invoke(base)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local proxy: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onStarted?.invoke("")
                }
            }
        }
    }

    fun stop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping local proxy: ${e.message}")
        }
        server = null
        port = 0
        baseUrlCache = null
    }

    fun isRunning(): Boolean = server != null

    fun getBaseUrl(): String = baseUrlCache ?: "http://127.0.0.1:${port}"

    fun localPlaylistUrlFor(remoteM3u8: String): String {
        // Ensure server is started asynchronously if not running
        if (!isRunning()) start()
        val encoded = URLEncoder.encode(remoteM3u8, "UTF-8")
        return "${getBaseUrl()}/stream?source=$encoded"
    }

    private fun findLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val host = addr.hostAddress
                        // prefer IPv4
                        if (host.indexOf(':') < 0) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces: ${e.message}")
        }
        return null
    }

    private class NanoProxy(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return try {
                if (uri == "/status") {
                    val body = "lastPlaylist=${lastPlaylistRequested ?: "<none>"}\nsegmentRequests=$segmentRequestCount\nlastSegmentStatus=${lastSegmentStatus ?: "<none>"}\n"
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", body)
                }
                when (uri) {
                    "/stream" -> handleStream(session)
                    "/segment" -> handleSegment(session)
                    else -> newFixedLengthResponse(Response.Status.OK, "text/plain", "LocalProxy running")
                }
            } catch (e: Exception) {
                Log.e(TAG, "NanoProxy serve error: ${e.message}", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "proxy error: ${e.message}")
            }
        }

        private fun handleStream(session: IHTTPSession): Response {
            val params = session.parameters
            val src = params["source"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing source")
            val remote = java.net.URL(java.net.URLDecoder.decode(src, "UTF-8"))

            try {
                Log.d(TAG, "handleStream request for remote playlist: ${remote}")
                lastPlaylistRequested = remote.toString()
                val conn = remote.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                // Use a browser-like User-Agent to increase acceptance by remote servers (Twitch sometimes blocks non-browser UAs)
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 10; Mobile; rv:115.0) Gecko/115.0 Firefox/115.0")
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Referer", "https://www.twitch.tv/")
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.connect()
                val code = conn.responseCode
                Log.d(TAG, "Fetched remote playlist: ${remote} -> HTTP $code")
                if (code !in 200..299) {
                    val msg = conn.responseMessage
                    conn.disconnect()
                    return newFixedLengthResponse(Response.Status.lookup(code), "text/plain", "fetch failed: $code $msg")
                }

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                // Rewrite URI lines
                val base = remote
                val hostHeader = session.headers["host"]
                val host = hostHeader ?: "${findLocalIpAddress() ?: "127.0.0.1"}:$listeningPort"
                val prefix = "http://$host"

                val rewritten = text.lines().joinToString(separator = "\r\n") { line ->
                    val trim = line.trim()
                    if (trim.isEmpty() || trim.startsWith("#")) return@joinToString line
                    try {
                        val resolved = URL(base, trim).toString()
                        val enc = URLEncoder.encode(resolved, "UTF-8")
                        Log.d(TAG, "Rewriting playlist line. original=$trim resolved=$resolved -> $prefix/segment?u=$enc")
                        "$prefix/segment?u=$enc"
                    } catch (e: Exception) {
                        line
                    }
                }

                // Use application/x-mpegURL which is commonly accepted for HLS playlists
                val resp = newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", rewritten)
                resp.addHeader("Access-Control-Allow-Origin", "*")
                resp.addHeader("Access-Control-Allow-Headers", "Range,Content-Type")
                Log.d(TAG, "Returning rewritten playlist to client: ${session.remoteIpAddress}")
                return resp
            } catch (e: Exception) {
                Log.e(TAG, "handleStream error: ${e.message}", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
            }
        }

        private fun handleSegment(session: IHTTPSession): Response {
            val params = session.parameters
            val enc = params["u"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing u")
            val remoteUrl = java.net.URL(java.net.URLDecoder.decode(enc, "UTF-8"))
            try {
                Log.d(TAG, "handleSegment request for: $remoteUrl from ${session.remoteIpAddress}; headers=${session.headers}")
                val conn = remoteUrl.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                // Forward Range header if present
                val range = session.headers["range"]
                if (!range.isNullOrEmpty()) conn.setRequestProperty("Range", range)
                if (!range.isNullOrEmpty()) Log.d(TAG, "Forwarding Range header to remote: $range")
                // Use a browser-like User-Agent for segment requests as well
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 10; Mobile; rv:115.0) Gecko/115.0 Firefox/115.0")
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Referer", "https://www.twitch.tv/")
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.connect()

                val code = conn.responseCode
                Log.d(TAG, "Remote segment responded with HTTP $code for $remoteUrl")
                // diagnostics
                synchronized(this@LocalProxyServer) { segmentRequestCount++ }
                lastSegmentStatus = code
                val contentType = conn.contentType ?: "application/octet-stream"

                // Read the remote response into memory so we can return a fixed-length response
                val bytes = conn.inputStream.use { it.readBytes() }
                val remoteContentLength = conn.getHeaderFieldInt("Content-Length", bytes.size)
                Log.d(TAG, "Remote Content-Length header: $remoteContentLength, buffered ${bytes.size} bytes")
                val resp = newFixedLengthResponse(Response.Status.lookup(code), contentType, bytes.inputStream(), bytes.size.toLong())
                // forward content-range/length if available
                conn.headerFields["Content-Range"]?.firstOrNull()?.let { resp.addHeader("Content-Range", it) }
                conn.getHeaderField("Accept-Ranges")?.let { resp.addHeader("Accept-Ranges", it) }
                // Ensure Content-Length is forwarded if available
                try { resp.addHeader("Content-Length", bytes.size.toString()) } catch (_: Exception) {}
                // CORS for clients that may enforce it
                try { resp.addHeader("Access-Control-Allow-Origin", "*") } catch (_: Exception) {}
                try { resp.addHeader("Access-Control-Allow-Headers", "Range,Content-Type") } catch (_: Exception) {}
                Log.d(TAG, "Responding to client ${session.remoteIpAddress} with contentType=$contentType, code=$code")
                return resp
            } catch (e: Exception) {
                Log.e(TAG, "handleSegment error: ${e.message}", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "segment error: ${e.message}")
            }
        }
    }
}
