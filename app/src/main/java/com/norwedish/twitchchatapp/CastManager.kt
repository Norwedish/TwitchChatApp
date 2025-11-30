package com.norwedish.twitchchatapp

/**
 * Helper to coordinate Google Cast session lifecycle and provide a simple API to start casting.
 */

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

object CastManager {
    private const val TAG = "CastManager"

    // Use a WeakReference to avoid keeping a static strong reference to an Android Context via CastSession
    private var currentCastSessionRef: WeakReference<CastSession?>? = null
    private fun currentCastSession(): CastSession? = currentCastSessionRef?.get()

    private var pendingUrl: String? = null
    private var pendingTitle: String? = null
    private var pendingPoster: String? = null
    // New: store the streamer's display name separately from the stream title
    private var pendingDisplayName: String? = null

    // Store application context if needed
    private var appContext: Context? = null
    private var initialized = false

    data class CastDebug(val lastRequestedUrl: String? = null, val lastStatusCode: Int? = null, val lastStatusMessage: String? = null)

    private val _debugState = MutableStateFlow(CastDebug())
    val debugState: StateFlow<CastDebug> = _debugState.asStateFlow()

    /**
     * Initialize the CastManager. This is idempotent and safe to call from Application.onCreate().
     * It attempts to prime the Cast SDK but silently logs failures so the app doesn't crash
     * if the Cast framework is not available or initialization fails.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext

        try {
            // Initialize the Cast SDK
            try {
                @Suppress("DEPRECATION")
                CastContext.getSharedInstance(appContext!!)
                Log.i(TAG, "CastContext initialized successfully")
            } catch (e: NoClassDefFoundError) {
                Log.w(TAG, "Cast SDK not available: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "CastContext initialization failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error during CastManager.initialize", e)
        }
    }

    /**
     * Update the current cast session (called from MainActivity when session starts)
     */
    fun setCurrentSession(session: CastSession?) {
        currentCastSessionRef = WeakReference(session)
        Log.d(TAG, "Cast session updated: ${if (session != null) "Connected" else "Disconnected"}")
        if (session != null) {
            // Try to load any pending content
            tryLoadPendingIfAny()
        } else {
            // Session ended/disconnected: stop any running local proxy to free resources
            try {
                LocalProxyServer.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop local proxy on session end: ${e.message}")
            }
        }
    }

    /**
     * Start casting a stream. If no active session, stores the URL as pending.
     */
    // Now accept displayName and streamTitle separately so we can set title/subtitle on receiver
    fun startCasting(url: String, displayName: String? = null, streamTitle: String? = null, poster: String? = null) {
        Log.d(TAG, "startCasting called - displayName=$displayName, streamTitle=$streamTitle, URL: $url")

        // record last requested url
        _debugState.value = CastDebug(lastRequestedUrl = url, lastStatusCode = null, lastStatusMessage = null)

        val session = currentCastSession()
        if (session == null || !session.isConnected) {
            Log.i(TAG, "No active cast session. Storing pending URL and waiting for connection...")
            pendingUrl = url
            // store both display name and stream title
            pendingDisplayName = displayName
            pendingTitle = streamTitle
            pendingPoster = poster
            return
        }

        loadMediaOnCastDevice(session, url, displayName, streamTitle, poster)
    }

    /**
     * Load media on the currently connected cast device
     */
    private fun loadMediaOnCastDevice(session: CastSession, url: String, displayName: String?, streamTitle: String?, poster: String?) {
        try {
            Log.d(TAG, "Loading media on cast device: $url")

            val remoteMediaClient = session.remoteMediaClient
            if (remoteMediaClient == null) {
                Log.e(TAG, "RemoteMediaClient is null; cannot load media")
                _debugState.value = _debugState.value.copy(lastStatusCode = -1, lastStatusMessage = "RemoteMediaClient is null")
                return
            }

            // Build metadata
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
            // Primary title should be the streamer's display name; subtitle contains the stream title
            displayName?.let {
                metadata.putString(MediaMetadata.KEY_TITLE, it)
                Log.d(TAG, "Set metadata title (displayName): $it")
            }
            streamTitle?.let {
                metadata.putString(MediaMetadata.KEY_SUBTITLE, it)
                Log.d(TAG, "Set metadata subtitle (streamTitle): $it")
            }

            // Add poster image if provided
            poster?.let {
                try {
                    val webImage = com.google.android.gms.common.images.WebImage(it.toUri())
                    metadata.addImage(webImage)
                    Log.d(TAG, "Added poster image: $it")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add poster image: ${e.message}")
                }
            }

            // Detect HLS streams
            val isHls = url.contains(".m3u8", ignoreCase = true)
            val initialContentType = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4"
            Log.d(TAG, "Stream type: ${if (isHls) "LIVE (HLS)" else "BUFFERED"}, initial Content-Type: $initialContentType")

            // Helper to build MediaInfo
            fun buildMediaInfo(contentUrl: String, contentType: String, streamTypeForCandidate: Int): MediaInfo {
                return MediaInfo.Builder(contentUrl)
                    .setContentType(contentType)
                    .setStreamType(streamTypeForCandidate)
                    .setMetadata(metadata)
                    .build()
            }

            // core loader logic using an effective content URL (may be proxied)
            fun proceedWithUrl(effectiveContentUrl: String) {
                // Only use a single MIME/stream-type candidate. Prefer application/vnd.apple.mpegurl + STREAM_TYPE_BUFFERED
                // Always prefer HLS MIME + buffered stream type; this has worked best across receivers.
                val contentCandidates: List<Pair<String, Int>> = listOf(Pair("application/vnd.apple.mpegurl", MediaInfo.STREAM_TYPE_BUFFERED))

                // RemoteMediaClient listener for diagnostics
                val rmcListener = object : RemoteMediaClient.Listener {
                    override fun onStatusUpdated() {
                        try {
                            val info = try { remoteMediaClient.mediaInfo } catch (_: Exception) { null }
                            val contentId = info?.contentId
                            Log.d(TAG, "RemoteMediaClient status updated. contentId=$contentId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to read media status: ${e.message}")
                        }
                    }

                    override fun onMetadataUpdated() { Log.d(TAG, "RemoteMediaClient metadata updated") }
                    override fun onQueueStatusUpdated() { Log.d(TAG, "RemoteMediaClient queue status updated") }
                    override fun onSendingRemoteMediaRequest() { Log.d(TAG, "RemoteMediaClient is sending remote media request") }
                    override fun onPreloadStatusUpdated() { Log.d(TAG, "RemoteMediaClient preload status updated") }
                    override fun onAdBreakStatusUpdated() { Log.d(TAG, "RemoteMediaClient ad break status updated") }
                }

                try {
                    remoteMediaClient.addListener(rmcListener)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add RemoteMediaClient listener: ${e.message}")
                }

                var candidateIndex = 0
                var attempts = 0
                val maxAttemptsBeforeTryAlternate = 4
                val maxTotalAttempts = 12

                val exec = Executors.newSingleThreadScheduledExecutor()

                // Guard to avoid overlapping load() calls on the receiver which can cause restarts
                val isLoadInProgress = AtomicBoolean(false)

                fun attemptLoadWithCurrentCandidate() {
                    // If a load is already in progress, defer the next attempt slightly to avoid overlapping loads
                    if (isLoadInProgress.get()) {
                        Log.d(TAG, "Load already in progress; deferring next attempt")
                        try {
                            exec.schedule({ attemptLoadWithCurrentCandidate() }, 500, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to schedule deferred load attempt: ${e.message}")
                        }
                        return
                    }

                    val (contentType, candidateStreamType) = contentCandidates.getOrElse(candidateIndex) { contentCandidates.first() }
                    val mInfo = buildMediaInfo(effectiveContentUrl, contentType, candidateStreamType)

                    val callLoad = Runnable {
                        try {
                            // set guard before issuing the load
                            isLoadInProgress.set(true)

                            @Suppress("DEPRECATION")
                            val pendingResult = remoteMediaClient.load(mInfo, true)
                            Log.i(TAG, "Media load requested on cast device (contentType=$contentType, streamType=$candidateStreamType)")
                            _debugState.value = _debugState.value.copy(lastStatusCode = 0, lastStatusMessage = "Media load requested (contentType=$contentType)")

                            try {
                                pendingResult.setResultCallback { result ->
                                    try {
                                        val status = result.status
                                        Log.i(TAG, "Load result callback - statusCode=${status.statusCode}, msg=${status.statusMessage}")
                                        if (!status.isSuccess) {
                                            _debugState.value = _debugState.value.copy(lastStatusCode = status.statusCode, lastStatusMessage = status.statusMessage)
                                        } else {
                                            _debugState.value = _debugState.value.copy(lastStatusCode = 0, lastStatusMessage = "Load succeeded (status=${status.statusCode})")
                                            // best-effort play + request status
                                            if (Looper.myLooper() == Looper.getMainLooper()) {
                                                try { remoteMediaClient.play() } catch (_: Exception) {}
                                                try { remoteMediaClient.requestStatus() } catch (_: Exception) {}
                                            } else {
                                                Handler(Looper.getMainLooper()).post {
                                                    try { remoteMediaClient.play() } catch (_: Exception) {}
                                                    try { remoteMediaClient.requestStatus() } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Exception in load result callback: ${e.message}")
                                    } finally {
                                        // clear the guard when load result arrives
                                        try { isLoadInProgress.set(false) } catch (_: Exception) {}
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to attach result callback to load result: ${e.message}")
                                try { isLoadInProgress.set(false) } catch (_: Exception) {}
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load media on cast device with contentType=$contentType (main-thread call): ${e.message}", e)
                            _debugState.value = _debugState.value.copy(lastStatusCode = -4, lastStatusMessage = e.message)
                            try { isLoadInProgress.set(false) } catch (_: Exception) {}
                        }
                    }

                    if (Looper.myLooper() == Looper.getMainLooper()) callLoad.run() else Handler(Looper.getMainLooper()).post(callLoad)
                }

                // initial attempt
                attemptLoadWithCurrentCandidate()

                // diagnostic reflection to inspect player-state getters
                try {
                    val diagExec = Executors.newSingleThreadScheduledExecutor()
                    diagExec.schedule({
                        try {
                            val clazz = remoteMediaClient.javaClass
                            val methodNames = clazz.methods.map { it.name }.sorted().distinct()
                            Log.d(TAG, "RemoteMediaClient methods: ${methodNames.joinToString(",")}")

                            // Check for common player state methods
                            val playerStateMethods = listOf("getPlayerState", "isPlaying", "getMediaStatus")
                            for (methodName in playerStateMethods) {
                                try {
                                    val m = clazz.getMethod(methodName)
                                    val res = m.invoke(remoteMediaClient)
                                    Log.d(TAG, "$methodName() => $res")
                                } catch (_: NoSuchMethodException) {}
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Diagnostic reflection failed: ${e.message}")
                        } finally {
                            try { diagExec.shutdownNow() } catch (_: Exception) {}
                        }
                    }, 1500, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to schedule diagnostic reflection: ${e.message}")
                }

                // Polling task
                val polling = object : Runnable {
                    override fun run() {
                        attempts++
                        try {
                            val info = try { remoteMediaClient.mediaInfo } catch (_: Exception) { null }
                            val contentId = info?.contentId
                            Log.d(TAG, "Post-load check #$attempts: mediaInfo.contentId=$contentId")

                            // heuristics: try several getters
                            var playerStarted = false
                            try {
                                try {
                                    val m = remoteMediaClient.javaClass.getMethod("getPlayerState")
                                    val res = m.invoke(remoteMediaClient)
                                    Log.d(TAG, "getPlayerState() => $res")
                                    if (res is Number && res.toInt() != 0) playerStarted = true
                                } catch (_: NoSuchMethodException) {}

                                if (!playerStarted) {
                                    try {
                                        val getMediaStatus = remoteMediaClient.javaClass.getMethod("getMediaStatus")
                                        val mediaStatus = getMediaStatus.invoke(remoteMediaClient)
                                        if (mediaStatus != null) {
                                            try {
                                                val m2 = mediaStatus.javaClass.getMethod("getPlayerState")
                                                val res2 = m2.invoke(mediaStatus)
                                                Log.d(TAG, "mediaStatus.getPlayerState() => $res2")
                                                if (res2 is Number && res2.toInt() != 0) playerStarted = true
                                            } catch (_: NoSuchMethodException) {}
                                        }
                                    } catch (_: NoSuchMethodException) {}
                                }

                                if (!playerStarted) {
                                    try {
                                        val m3 = remoteMediaClient.javaClass.getMethod("isPlaying")
                                        val res3 = m3.invoke(remoteMediaClient)
                                        Log.d(TAG, "isPlaying() => $res3")
                                        if (res3 is Boolean && res3) playerStarted = true
                                    } catch (_: NoSuchMethodException) {}
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Exception while probing player state: ${e.message}")
                            }

                            if (playerStarted) {
                                Log.i(TAG, "Detected player started on receiver (heuristic).")
                                _debugState.value = _debugState.value.copy(lastStatusCode = 0, lastStatusMessage = "Receiver player started")
                                try { exec.shutdownNow() } catch (_: Exception) {}
                                // remove listener and clear any guard
                                try { remoteMediaClient.removeListener(rmcListener) } catch (_: Exception) {}
                                try { isLoadInProgress.set(false) } catch (_: Exception) {}
                                return
                            }

                            if (attempts % maxAttemptsBeforeTryAlternate == 0 && candidateIndex < contentCandidates.size - 1) {
                                candidateIndex++
                                val (ct, st) = contentCandidates[candidateIndex]
                                Log.w(TAG, "Attempting alternate candidate: contentType=$ct, streamType=$st")
                                attemptLoadWithCurrentCandidate()
                            }

                            if (attempts >= maxTotalAttempts) {
                                Log.w(TAG, "Receiver did not accept or start the media after $attempts attempts")
                                _debugState.value = _debugState.value.copy(lastStatusCode = -6, lastStatusMessage = "Receiver did not start playback after polling")
                                try { exec.shutdownNow() } catch (_: Exception) {}

                                // Sender-side fallback: try queueLoad once via reflection
                                try {
                                    val candidate = contentCandidates.getOrElse(candidateIndex) { contentCandidates.first() }
                                    val mInfo = buildMediaInfo(effectiveContentUrl, candidate.first, candidate.second)
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            val mqItem = try {
                                                val builderClass = Class.forName("com.google.android.gms.cast.framework.media.MediaQueueItem\$Builder")
                                                val ctor = builderClass.getConstructor(com.google.android.gms.cast.MediaInfo::class.java)
                                                val builder = ctor.newInstance(mInfo)
                                                val buildMethod = builderClass.getMethod("build")
                                                buildMethod.invoke(builder)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "MediaQueueItem builder reflection failed: ${e.message}")
                                                null
                                            }

                                            if (mqItem != null) {
                                                try {
                                                    val methods = remoteMediaClient.javaClass.methods.filter { it.name == "queueLoad" }
                                                    var invoked = false
                                                    for (m in methods) {
                                                        try {
                                                            val args = arrayOf<Any?>(arrayOf(mqItem), 0, 0, null)
                                                            m.invoke(remoteMediaClient, *args)
                                                            invoked = true
                                                            break
                                                        } catch (_: Exception) {}
                                                    }
                                                    if (!invoked) {
                                                        Log.w(TAG, "No suitable queueLoad overload invoked; will fall back to load")
                                                        try { remoteMediaClient.load(mInfo, true) } catch (_: Exception) {}
                                                    } else {
                                                        Log.d(TAG, "queueLoad fallback requested via reflection")
                                                        try { remoteMediaClient.play() } catch (_: Exception) {}
                                                        try { remoteMediaClient.requestStatus() } catch (_: Exception) {}
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "queueLoad reflection/path failed: ${e.message}")
                                                    try { remoteMediaClient.load(mInfo, true) } catch (_: Exception) {}
                                                }
                                            } else {
                                                try { remoteMediaClient.load(mInfo, true) } catch (_: Exception) {}
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "queueLoad fallback runnable failed: ${e.message}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to prepare queueLoad fallback: ${e.message}")
                                }
                            }

                        } catch (e: Exception) {
                            Log.w(TAG, "Exception during post-load polling: ${e.message}")
                        }
                    }
                }

                try {
                    exec.scheduleWithFixedDelay(polling, 1, 1, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to schedule post-load poll: ${e.message}")
                }

                // Clear pending
                pendingUrl = null
                pendingDisplayName = null
                pendingTitle = null
                pendingPoster = null
            }

            // Decide whether to use local proxy for Twitch usher HLS URLs
            val shouldUseLocalProxy = isHls && (url.contains("usher.ttvnw.net") || url.contains("ttvnw.net"))
            if (shouldUseLocalProxy) {
                Log.i(TAG, "Detected Twitch usher URL; starting local proxy and will cast via local playlist")
                try {
                    LocalProxyServer.start { base ->
                        if (base.isNullOrEmpty()) {
                            Log.w(TAG, "Local proxy failed to start; falling back to direct URL")
                            proceedWithUrl(url)
                        } else {
                            try {
                                val proxyPlaylist = LocalProxyServer.localPlaylistUrlFor(url)
                                Log.i(TAG, "Using proxy playlist: $proxyPlaylist")
                                proceedWithUrl(proxyPlaylist)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to create proxy playlist URL: ${e.message}; falling back to direct URL")
                                proceedWithUrl(url)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start local proxy: ${e.message}; casting direct URL instead")
                    proceedWithUrl(url)
                }
            } else {
                // No proxy needed; proceed with original URL
                proceedWithUrl(url)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load media on cast device: ${e.message}", e)
            _debugState.value = _debugState.value.copy(lastStatusCode = -5, lastStatusMessage = e.message)
        }
    }

    /**
     * Retry loading the last requested URL (best-effort)
     */
    fun retryLast() {
        val last = _debugState.value.lastRequestedUrl ?: pendingUrl
        if (last != null) {
            Log.d(TAG, "Retrying last cast URL: $last")
            startCasting(last, pendingDisplayName, pendingTitle, pendingPoster)
        } else {
            Log.d(TAG, "No last URL to retry")
        }
    }

    /**
     * Helper to attempt to load any pending URL when a cast session becomes active.
     */
    fun tryLoadPendingIfAny() {
        if (pendingUrl != null) {
            Log.d(TAG, "Loading pending URL: $pendingUrl")
            val url = pendingUrl ?: return
            val displayName = pendingDisplayName
            val title = pendingTitle
            val poster = pendingPoster

            startCasting(url, displayName, title, poster)
        }
    }

}
