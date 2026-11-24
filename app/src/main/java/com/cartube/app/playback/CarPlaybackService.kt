package com.cartube.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.ClientSourceHelper
import com.cartube.app.data.SessionSignals
import com.cartube.app.data.UpNextContextRanker
import com.cartube.app.data.VideoItem
import com.cartube.app.data.VoiceAndLinkHelper
import com.cartube.app.data.VoiceOrLinkTarget
import com.cartube.app.data.YouTubeRepository
import com.cartube.app.ui.PlayerActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
import com.liskovsoft.sharedutils.cronet.CronetManager
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@UnstableApi
class CarPlaybackService : MediaSessionService() {

    private lateinit var prefs: CarPrefs
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: CarForwardingPlayer
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cronetExecutor = Executors.newCachedThreadPool()
    private var loadJob: Job? = null
    private var extrasJob: Job? = null
    private var nextActionJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Smart related-only Up Next queue + Played History stack (prevents jumping across unrelated Home categories)
    private val playedHistory = ArrayDeque<VideoItem>()
    private val playedIdsInSession = LinkedHashSet<String>()
    private val upNextQueue = ArrayList<VideoItem>()

    // Listener-aware Up Next. A video reached through Next / autoplay only becomes the new source
    // of suggestions once it has really been listened to ("accepted"); until then the queue keeps
    // following the last accepted video, so skipping a song never drags the queue toward it.
    private var queueSeed: VideoItem? = null
    private var queueSourceRaw: List<VideoItem> = emptyList()
    private var currentAccepted: Boolean = false
    private var currentListenedMs: Long = 0L
    private var pendingRelatedSeed: VideoItem? = null
    private var pendingRelated: List<VideoItem>? = null
    private val skippedAuthors = HashMap<String, Int>()
    private val relatedHits = LinkedHashMap<String, Int>()
    private val relatedHitsCountedFor = HashSet<String>()

    private var activeSegments: List<SponsorSegment> = emptyList()
    private val skippedSegmentKeys = HashSet<String>()
    private var isVideoTrackEnabled: Boolean = true
    private var lastHistorySyncVideoId: String? = null
    private var lastHistorySyncSec: Long = -1L
    private var errorRetryCount: Int = 0
    private var lastAttemptedVideoId: String? = null
    private var lastResumeSaveTimeMs: Long = 0L

    // User-chosen "Chỉ tiếng" mode; survives PlayerActivity recreation (unlike temporary background video-off)
    private var userForcedAudioOnly: Boolean = false

    private val sponsorTickRunnable = object : Runnable {
        override fun run() {
            if (::exoPlayer.isInitialized) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                val currentState = PlaybackStateStore.state.value
                if (currentState.currentVideo != null &&
                    (kotlin.math.abs(currentState.positionMs - pos) >= 1_000L ||
                        (dur > 0L && currentState.durationMs != dur))
                ) {
                    PlaybackStateStore.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = if (dur > 0L) dur else it.durationMs
                        )
                    }
                }
                if (exoPlayer.isPlaying) {
                    trackListening(dur)
                    checkSponsorSegments()
                    syncHistoryPeriodically()
                    val now = System.currentTimeMillis()
                    if (now - lastResumeSaveTimeMs >= 5_000L) {
                        persistCurrentResumeState()
                    }
                }
            }
            mainHandler.postDelayed(this, SPONSOR_CHECK_INTERVAL_MS)
        }
    }

    private fun persistCurrentResumeState() {
        if (!::exoPlayer.isInitialized || !::prefs.isInitialized) return
        // While a new track is loading (or the player was just stopped), exoPlayer.currentPosition
        // still reflects the previous track; never overwrite the new track's saved position with it.
        if (PlaybackStateStore.state.value.isLoading || exoPlayer.playbackState == Player.STATE_IDLE) return
        val video = PlaybackStateStore.state.value.currentVideo ?: return
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: video.durationMs
        prefs.saveLastPlayed(video, pos, dur)
        lastResumeSaveTimeMs = System.currentTimeMillis()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = CarPrefs(this)

        val effectiveMaxHeight = effectiveMaxVideoHeight()
        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(1920, effectiveMaxHeight)
                .setForceHighestSupportedBitrate(false)
                .build()
        }

        // Start bandwidth estimate at 6 Mbps so ABR starts sharp (720p/1080p) and smoothly adapts on 4G without stuttering
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(6_000_000L)
            .build()

        // Fast 1s playback start + generous 60s buffer ceiling to absorb mobile hotspot fluctuations
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000, // minBufferMs
                60_000, // maxBufferMs
                1_000,  // bufferForPlaybackMs (fast start)
                2_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true) // Auto handle car AudioFocus (navi/reverse)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackStateStore.update { it.copy(isPlaying = isPlaying) }
                if (!isPlaying) {
                    persistCurrentResumeState()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    Log.i(TAG, "Active video resolution: ${videoSize.width}x${videoSize.height}")
                    PlaybackStateStore.update { it.copy(actualVideoHeight = videoSize.height) }
                    rememberWorkingClient()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    rememberWorkingClient()
                } else if (playbackState == Player.STATE_ENDED) {
                    // Played to the end: the strongest "I liked this" signal there is.
                    acceptCurrentVideo()
                    advanceToNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error (${error.errorCodeName}): ${error.message}", error)
                val current = PlaybackStateStore.state.value.currentVideo
                if (current != null && errorRetryCount < MAX_ERROR_RETRIES) {
                    errorRetryCount++
                    Log.w(TAG, "Auto-switching YouTube client & retrying ($errorRetryCount/$MAX_ERROR_RETRIES)...")
                    PlaybackStateStore.update {
                        it.copy(
                            isLoading = true,
                            statusNotice = "Đang đổi nguồn phát (${errorRetryCount}/$MAX_ERROR_RETRIES)...",
                            errorMessage = null
                        )
                    }
                    YouTubeServiceManager.instance().switchNextClientNow()
                    playVideoInternal(
                        current,
                        pushCurrentToHistory = false,
                        resetRetry = false,
                        chosenByUser = currentAccepted
                    )
                } else {
                    PlaybackStateStore.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Không phát được video (${error.errorCodeName})"
                        )
                    }
                }
            }
        })

        forwardingPlayer = CarForwardingPlayer(exoPlayer)

        val sessionIntent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<Media3Item>
                ): ListenableFuture<MutableList<Media3Item>> {
                    val first = mediaItems.firstOrNull()
                    val searchQuery = first?.requestMetadata?.searchQuery
                    val mediaUri = first?.requestMetadata?.mediaUri
                    if (!searchQuery.isNullOrBlank()) {
                        playVoiceSearch(searchQuery)
                        return Futures.immediateFuture<MutableList<Media3Item>>(ArrayList())
                    }
                    if (mediaUri != null) {
                        VoiceAndLinkHelper.parseUri(mediaUri)?.let { target ->
                            handleVoiceOrLinkTarget(target)
                            return Futures.immediateFuture<MutableList<Media3Item>>(ArrayList())
                        }
                    }
                    return super.onAddMediaItems(mediaSession, controller, mediaItems)
                }
            })
            .build()

        mainHandler.postDelayed(sponsorTickRunnable, SPONSOR_CHECK_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        persistCurrentResumeState()
        mainHandler.removeCallbacks(sponsorTickRunnable)
        loadJob?.cancel()
        extrasJob?.cancel()
        nextActionJob?.cancel()
        serviceScope.cancel()
        cronetExecutor.shutdown()
        mediaSession?.release()
        mediaSession = null
        exoPlayer.release()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun getPlayer(): ExoPlayer = exoPlayer

    /**
     * Starts a newly selected video from Home / Music / Search / Subscriptions / History.
     * Clears unrelated old Up Next items so Next strictly follows YouTube's related Mix/suggestions
     * for THIS video's genre/artist.
     */
    fun playVideo(video: VideoItem, startPositionMs: Long = 0L) {
        upNextQueue.clear()
        queueSeed = null
        queueSourceRaw = emptyList()
        playVideoInternal(
            video = video,
            pushCurrentToHistory = true,
            resetRetry = true,
            chosenByUser = true,
            startPositionMs = startPositionMs
        )
    }

    /**
     * Plays an item chosen from the Up Next drawer while preserving remaining fallback suggestions
     * until the newly selected video's own related suggestions arrive.
     */
    fun playFromUpNext(video: VideoItem) {
        upNextQueue.removeAll { it.videoId == video.videoId }
        playVideoInternal(video, pushCurrentToHistory = true, resetRetry = true, chosenByUser = true)
    }

    fun handleVoiceOrLinkTarget(target: VoiceOrLinkTarget) {
        when (target) {
            is VoiceOrLinkTarget.Video -> {
                playVideo(VoiceAndLinkHelper.buildPlaceholderVideoItem(target.videoId))
            }
            is VoiceOrLinkTarget.SearchAndPlay -> {
                playVoiceSearch(target.query)
            }
        }
    }

    fun playVoiceSearch(rawQuery: String) {
        val cleaned = VoiceAndLinkHelper.cleanVoiceQuery(rawQuery)
        PlaybackStateStore.update {
            it.copy(isLoading = true, statusNotice = "Đang mở: $cleaned...")
        }
        serviceScope.launch {
            runCatching {
                YouTubeRepository.search(cleaned)
            }.onSuccess { page ->
                val first = page.items.firstOrNull()
                if (first != null) {
                    playVideo(first)
                } else {
                    PlaybackStateStore.update {
                        it.copy(isLoading = false, errorMessage = "Không tìm thấy: $cleaned")
                    }
                }
            }.onFailure { err ->
                PlaybackStateStore.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Lỗi tìm kiếm giọng nói")
                }
            }
        }
    }

    private fun recordPlayedSessionId(videoId: String) {
        playedIdsInSession.remove(videoId)
        playedIdsInSession.add(videoId)
        while (playedIdsInSession.size > MAX_SESSION_MEMORY) {
            val oldest = playedIdsInSession.iterator().next()
            playedIdsInSession.remove(oldest)
        }
    }

    private fun pushHistoryIfNeeded(previous: VideoItem?) {
        if (previous == null) return
        if (playedHistory.lastOrNull()?.videoId == previous.videoId) return
        playedHistory.addLast(previous)
        while (playedHistory.size > MAX_HISTORY_SIZE) {
            playedHistory.removeFirst()
        }
    }

    private fun playVideoInternal(
        video: VideoItem,
        pushCurrentToHistory: Boolean,
        resetRetry: Boolean,
        chosenByUser: Boolean,
        startPositionMs: Long = 0L
    ) {
        nextActionJob?.cancel()
        var effectiveStartMs = if (video.isLive) 0L else startPositionMs.coerceAtLeast(0L)
        // A retry of the same video (client switch after an error) keeps its listening progress.
        if (PlaybackStateStore.state.value.currentVideo?.videoId != video.videoId) {
            currentListenedMs = 0L
            currentAccepted = chosenByUser
            pendingRelatedSeed = null
            pendingRelated = null
        } else if (chosenByUser) {
            currentAccepted = true
        }
        if (chosenByUser) {
            // Picking a video on purpose forgives earlier skips of that channel.
            skippedAuthors.remove(UpNextContextRanker.normalizeAuthor(video.author))
        }
        if (pushCurrentToHistory) {
            val prev = PlaybackStateStore.state.value.currentVideo
            if (prev != null && prev.videoId != video.videoId) {
                pushHistoryIfNeeded(prev)
            }
        }
        recordPlayedSessionId(video.videoId)

        if (resetRetry || lastAttemptedVideoId != video.videoId) {
            errorRetryCount = 0
            lastAttemptedVideoId = video.videoId
        }

        PlaybackStateStore.update {
            it.copy(
                currentVideo = video,
                upNextVideos = upNextQueue.toList(),
                isLoadingUpNext = true,
                canGoPrevious = playedHistory.isNotEmpty(),
                sponsorSegments = emptyList(),
                isLoading = true,
                actualVideoHeight = 0,
                positionMs = effectiveStartMs,
                durationMs = video.durationMs,
                errorMessage = null,
                statusNotice = if (resetRetry) null else it.statusNotice
            )
        }
        activeSegments = emptyList()
        skippedSegmentKeys.clear()
        lastHistorySyncVideoId = null
        lastHistorySyncSec = -1L

        // Stop previous media right away so track switching feels snappy
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        loadJob?.cancel()
        extrasJob?.cancel()

        // 1. Fetch stream URLs ONLY and start playback immediately (never block on SponsorBlock or /next!)
        loadJob = serviceScope.launch {
            try {
                val formatInfo = YouTubeRepository.loadFormatInfo(
                    videoId = video.videoId,
                    clickTrackingParams = video.clickTrackingParams
                )

                val formatDurationMs = formatInfo.lengthSeconds
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.times(1000L)
                    ?: 0L

                val enrichedVideo = video.copy(
                    title = formatInfo.title?.takeIf { it.isNotBlank() } ?: video.title,
                    author = formatInfo.author?.takeIf { it.isNotBlank() } ?: video.author,
                    durationMs = video.durationMs.takeIf { it > 0L } ?: formatDurationMs,
                    isLive = video.isLive || formatInfo.isLive
                )

                // Live streams and nearly-finished videos start cleanly from the beginning
                if (enrichedVideo.isLive ||
                    (enrichedVideo.durationMs > 10_000L && effectiveStartMs >= enrichedVideo.durationMs - 5_000L)
                ) {
                    effectiveStartMs = 0L
                }

                PlaybackStateStore.update {
                    it.copy(
                        currentVideo = enrichedVideo,
                        upNextVideos = upNextQueue.toList(),
                        canGoPrevious = playedHistory.isNotEmpty(),
                        isLoading = false,
                        positionMs = effectiveStartMs,
                        statusNotice = null,
                        errorMessage = null
                    )
                }
                prefs.saveLastPlayed(enrichedVideo, effectiveStartMs, enrichedVideo.durationMs)
                lastResumeSaveTimeMs = System.currentTimeMillis()

                val mediaSource = withContext(Dispatchers.IO) {
                    buildMediaSource(formatInfo)
                }

                applyTrackPreferences()
                if (effectiveStartMs > 0L) {
                    exoPlayer.setMediaSource(mediaSource, effectiveStartMs)
                } else {
                    exoPlayer.setMediaSource(mediaSource)
                }
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare video ${video.videoId}: ${e.message}", e)
                PlaybackStateStore.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Không thể phát video này"
                    )
                }
            }
        }

        // 2. Fetch YouTube's related Up Next suggestions & SponsorBlock segments asynchronously in background
        extrasJob = serviceScope.launch {
            runCatching {
                YouTubeRepository.loadPlaybackExtras(
                    videoId = video.videoId,
                    loadSponsorSegments = prefs.sponsorBlockEnabled
                )
            }.onSuccess { extras ->
                val currentActive = PlaybackStateStore.state.value.currentVideo
                if (currentActive?.videoId != video.videoId) {
                    return@onSuccess
                }
                val meta = extras.metadata
                val resolvedSeed = currentActive.copy(
                    title = meta?.title?.takeIf { it.isNotBlank() } ?: currentActive.title,
                    author = meta?.author?.takeIf { it.isNotBlank() } ?: currentActive.author,
                    durationMs = currentActive.durationMs.takeIf { it > 0L }
                        ?: (meta?.durationMs?.coerceAtLeast(0L) ?: 0L),
                    isLive = currentActive.isLive || (meta?.isLive == true)
                )
                activeSegments = extras.sponsorSegments
                pendingRelatedSeed = resolvedSeed
                pendingRelated = extras.relatedVideos
                // Not accepted yet: hold these suggestions back and keep the current queue,
                // unless there is nothing else to offer.
                if (currentAccepted || upNextQueue.isEmpty()) {
                    adoptPendingRelated()
                }
                PlaybackStateStore.update {
                    it.copy(
                        currentVideo = resolvedSeed,
                        upNextVideos = upNextQueue.toList(),
                        isLoadingUpNext = false,
                        sponsorSegments = extras.sponsorSegments
                    )
                }
            }.onFailure {
                if (PlaybackStateStore.state.value.currentVideo?.videoId == video.videoId) {
                    PlaybackStateStore.update { it.copy(isLoadingUpNext = false) }
                }
            }
        }
    }

    private fun updateUpNextFromRelated(seedVideo: VideoItem, freshRelated: List<VideoItem>) {
        if (freshRelated.isEmpty()) return
        val currentVideoId = seedVideo.videoId

        // Prefer related videos not yet played in this session so Next never loops A <-> B
        val unseenFresh = freshRelated.filter {
            it.videoId != currentVideoId && !playedIdsInSession.contains(it.videoId)
        }
        val primary = unseenFresh.ifEmpty {
            freshRelated.filter { it.videoId != currentVideoId }
        }

        val seenIds = HashSet<String>()
        seenIds.add(currentVideoId)
        val merged = ArrayList<VideoItem>()
        for (item in primary) {
            if (seenIds.add(item.videoId)) {
                merged.add(item)
            }
        }
        // Keep a few unseen fallback items from previous related list at the tail
        for (tailItem in upNextQueue) {
            if (merged.size >= 25) break
            if (!playedIdsInSession.contains(tailItem.videoId) && seenIds.add(tailItem.videoId)) {
                merged.add(tailItem)
            }
        }

        queueSeed = seedVideo
        queueSourceRaw = merged
        val ranked = UpNextContextRanker.rank(seedVideo, merged, buildSessionSignals())
        upNextQueue.clear()
        upNextQueue.addAll(ranked.take(25))
    }

    private fun buildSessionSignals(): SessionSignals {
        val recent = ArrayList(playedHistory.takeLast(RECENT_PLAYED_FOR_RANKING))
        val current = PlaybackStateStore.state.value.currentVideo
        if (current != null && recent.lastOrNull()?.videoId != current.videoId) {
            recent.add(current)
        }
        return SessionSignals(
            recentlyPlayed = recent,
            skippedAuthors = HashMap(skippedAuthors),
            relatedHits = HashMap(relatedHits)
        )
    }

    /** Counts real listening time (not seek position) and accepts the video once it has had enough. */
    private fun trackListening(playerDurationMs: Long) {
        if (currentAccepted) return
        val video = PlaybackStateStore.state.value.currentVideo ?: return
        currentListenedMs += SPONSOR_CHECK_INTERVAL_MS
        val durationMs = if (playerDurationMs > 0L) playerDurationMs else video.durationMs
        val acceptAfterMs = if (durationMs > 0L) {
            minOf(durationMs / 2, ACCEPT_LISTEN_CAP_MS)
        } else {
            ACCEPT_LISTEN_UNKNOWN_DURATION_MS
        }
        if (currentListenedMs >= acceptAfterMs) {
            acceptCurrentVideo()
        }
    }

    /** The listener stayed with this video: it becomes the source of what plays next. */
    private fun acceptCurrentVideo() {
        if (currentAccepted) return
        val video = PlaybackStateStore.state.value.currentVideo ?: return
        currentAccepted = true
        skippedAuthors.remove(UpNextContextRanker.normalizeAuthor(video.author))
        if (adoptPendingRelated()) {
            PlaybackStateStore.update { it.copy(upNextVideos = upNextQueue.toList()) }
        }
    }

    private fun adoptPendingRelated(): Boolean {
        val seed = pendingRelatedSeed ?: return false
        val related = pendingRelated ?: return false
        pendingRelatedSeed = null
        pendingRelated = null
        if (related.isEmpty()) return false
        if (currentAccepted && relatedHitsCountedFor.add(seed.videoId)) {
            for (item in related) {
                relatedHits[item.videoId] = (relatedHits[item.videoId] ?: 0) + 1
            }
            val overflow = relatedHits.size - MAX_RELATED_HITS_MEMORY
            if (overflow > 0) {
                val oldest = relatedHits.keys.take(overflow)
                oldest.forEach { relatedHits.remove(it) }
            }
        }
        updateUpNextFromRelated(seed, related)
        return true
    }

    /**
     * Next pressed shortly after an autoplayed video started = "not this". Its channel is demoted
     * for the rest of the session and the remaining queue is re-ranked without it leading.
     */
    private fun registerSkipIfEarly() {
        if (currentAccepted) return
        val video = PlaybackStateStore.state.value.currentVideo ?: return
        // Never heard at all (still loading / failed to play) says nothing about taste.
        if (currentListenedMs < MIN_LISTEN_FOR_SKIP_SIGNAL_MS) return
        val durationMs = exoPlayer.duration.takeIf { it > 0L } ?: video.durationMs
        val earlySkipBeforeMs = if (durationMs > 0L) {
            minOf(durationMs * 3 / 10, EARLY_SKIP_CAP_MS)
        } else {
            EARLY_SKIP_UNKNOWN_DURATION_MS
        }
        if (currentListenedMs >= earlySkipBeforeMs) return
        val author = UpNextContextRanker.normalizeAuthor(video.author)
        if (author.isEmpty()) return
        skippedAuthors[author] = (skippedAuthors[author] ?: 0) + 1
        Log.i(TAG, "Early skip after ${currentListenedMs}ms: demoting channel \"${video.author}\"")
        rerankQueueAfterSkip()
    }

    private fun rerankQueueAfterSkip() {
        val seed = queueSeed ?: return
        if (upNextQueue.size <= 1) return
        val queuedIds = upNextQueue.mapTo(HashSet()) { it.videoId }
        val pool = queueSourceRaw.filter { it.videoId in queuedIds }
        // Queue holds something that did not come from the related list (e.g. re-queued by Previous)
        if (pool.size != upNextQueue.size) return
        val ranked = UpNextContextRanker.rank(seed, pool, buildSessionSignals())
        upNextQueue.clear()
        upNextQueue.addAll(ranked)
    }

    /** Next requested by the listener (touch, steering wheel, media key). */
    fun playNext() {
        registerSkipIfEarly()
        advanceToNext()
    }

    private fun advanceToNext() {
        if (upNextQueue.isNotEmpty()) {
            val next = upNextQueue.removeAt(0)
            playVideoInternal(next, pushCurrentToHistory = true, resetRetry = true, chosenByUser = false)
            return
        }

        // If user pressed Next right before background /next call finished, wait briefly for it
        val current = PlaybackStateStore.state.value.currentVideo ?: return
        nextActionJob?.cancel()
        nextActionJob = serviceScope.launch {
            PlaybackStateStore.update {
                it.copy(isLoading = true, statusNotice = "Đang tìm bài tiếp theo cùng thể loại...")
            }
            extrasJob?.join()
            if (upNextQueue.isEmpty()) {
                adoptPendingRelated()
            }
            if (upNextQueue.isEmpty()) {
                runCatching {
                    YouTubeRepository.loadPlaybackExtras(
                        videoId = current.videoId,
                        loadSponsorSegments = false
                    )
                }.onSuccess { extras ->
                    updateUpNextFromRelated(current, extras.relatedVideos)
                }
            }
            if (upNextQueue.isNotEmpty()) {
                val next = upNextQueue.removeAt(0)
                playVideoInternal(next, pushCurrentToHistory = true, resetRetry = true, chosenByUser = false)
            } else {
                PlaybackStateStore.update {
                    it.copy(isLoading = false, statusNotice = "Chưa có gợi ý bài tiếp theo")
                }
                mainHandler.postDelayed({ PlaybackStateStore.clearNotice() }, 2_000L)
            }
        }
    }

    fun playPrevious() {
        if (exoPlayer.currentPosition > 5_000L) {
            exoPlayer.seekTo(0L)
            return
        }
        if (playedHistory.isNotEmpty()) {
            val current = PlaybackStateStore.state.value.currentVideo
            val prev = playedHistory.removeLast()
            if (current != null && upNextQueue.firstOrNull()?.videoId != current.videoId) {
                upNextQueue.add(0, current)
            }
            playVideoInternal(prev, pushCurrentToHistory = false, resetRetry = true, chosenByUser = true)
        } else {
            exoPlayer.seekTo(0L)
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration)
        exoPlayer.seekTo(target)
    }

    fun setVideoEnabled(enabled: Boolean) {
        isVideoTrackEnabled = enabled
        applyTrackPreferences()
        PlaybackStateStore.update { it.copy(isAudioOnly = !enabled) }
    }

    fun isUserForcedAudioOnly(): Boolean = userForcedAudioOnly

    fun setUserForcedAudioOnly(audioOnly: Boolean) {
        userForcedAudioOnly = audioOnly
        setVideoEnabled(!audioOnly)
    }

    fun updateMaxVideoHeight(maxHeight: Int) {
        prefs.maxVideoHeight = maxHeight
        applyTrackPreferences()
    }

    private fun rememberWorkingClient() {
        val currentKey = ClientSourceHelper.getCurrentClientKey() ?: return
        if (prefs.preferredClientName != currentKey) {
            prefs.preferredClientName = currentKey
            Log.i(TAG, "Remembered working playback source: $currentKey")
        }
    }

    private fun effectiveMaxVideoHeight(): Int {
        val raw = prefs.maxVideoHeight
        // "Tự động" caps at 720p to match the 720px car display; explicit 1080p remains selectable
        return if (raw <= 0) 720 else raw
    }

    private fun applyTrackPreferences() {
        val maxHeight = effectiveMaxVideoHeight()
        val maxWidth = when (maxHeight) {
            480 -> 854
            720 -> 1280
            else -> 1920
        }
        val builder = trackSelector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !isVideoTrackEnabled)
            .setMaxVideoSize(maxWidth, maxHeight)
            .setForceHighestSupportedBitrate(false)

        if (prefs.blockAv1) {
            // Prefer hardware-decoded H.264 / VP9 on i.MX8Q instead of software AV1
            builder.setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9)
        } else {
            builder.setPreferredVideoMimeTypes()
        }

        trackSelector.parameters = builder.build()
    }

    private fun createHttpDataSourceFactory(
        userAgent: String,
        headers: Map<String, String>
    ): HttpDataSource.Factory {
        val cronetEngine = runCatching { CronetManager.getEngine(this) }.getOrNull()
        if (cronetEngine != null) {
            Log.i(TAG, "Using CronetDataSource (HTTP/3 QUIC + HTTP/2)")
            return CronetDataSource.Factory(cronetEngine, cronetExecutor)
                .setUserAgent(userAgent)
                .setConnectionTimeoutMs(10_000)
                .setReadTimeoutMs(12_000)
                .setResetTimeoutOnRedirects(true)
                .apply {
                    if (headers.isNotEmpty()) {
                        setDefaultRequestProperties(headers)
                    }
                }
        }
        Log.w(TAG, "Cronet unavailable, falling back to DefaultHttpDataSource")
        return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
            .apply {
                if (headers.isNotEmpty()) {
                    setDefaultRequestProperties(headers)
                }
            }
    }

    private fun buildMediaSource(formatInfo: MediaItemFormatInfo): MediaSource {
        val clientName = formatInfo.clientInfo?.clientName ?: "unknown"
        Log.i(
            TAG,
            "Building MediaSource client=$clientName dash=${formatInfo.containsDashFormats()} " +
                "sabr=${formatInfo.containsSabrFormats()} hls=${formatInfo.containsHlsUrl()} " +
                "extHls=${formatInfo.hasExtendedHlsFormats()} dashUrl=${formatInfo.containsDashUrl()} " +
                "adaptive=${formatInfo.adaptiveFormats?.size ?: 0} urls=${formatInfo.urlFormats?.size ?: 0}"
        )

        val headers = HashMap<String, String>()
        if (clientName.startsWith("WEB", ignoreCase = true) || clientName.equals("MWEB", ignoreCase = true)) {
            headers["Referer"] = "https://www.youtube.com/"
            headers["Origin"] = "https://www.youtube.com"
        }
        formatInfo.visitorCookie?.takeIf { it.isNotBlank() }?.let {
            headers["Cookie"] = it
        }
        val userAgent = formatInfo.clientInfo?.userAgent?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 9; CarTube) AppleWebKit/537.36"

        val dataSourceFactory = createHttpDataSourceFactory(userAgent, headers)

        // 1. Prefer HLS first for Live, VISIONOS/IOS, or when SABR is present alongside HLS
        val preferHls = formatInfo.containsHlsUrl() && (
            formatInfo.isLive ||
                formatInfo.hasExtendedHlsFormats() ||
                formatInfo.containsSabrFormats() ||
                clientName.equals("IOS", ignoreCase = true) ||
                clientName.equals("VISIONOS", ignoreCase = true)
            )
        if (preferHls) {
            Log.i(TAG, "Selected HLS stream for client=$clientName")
            val hlsItem = Media3Item.fromUri(formatInfo.hlsManifestUrl)
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(hlsItem)
        }

        // 2. Adaptive DASH via MediaServiceCore MPD (with safe fallback to direct MergingMediaSource)
        if (formatInfo.containsDashFormats()) {
            val dashSource = runCatching {
                val rawStream = formatInfo.createMpdStream() ?: return@runCatching null
                val mpdXml = rawStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                if (!mpdXml.contains("<Representation")) return@runCatching null
                val manifest = DashManifestParser().parse(
                    Uri.parse("https://manifest.googlevideo.com/"),
                    ByteArrayInputStream(mpdXml.toByteArray(StandardCharsets.UTF_8))
                )
                Log.i(TAG, "Selected DASH MPD stream for client=$clientName")
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest)
            }.onFailure {
                Log.w(TAG, "MPD parse failed, trying direct adaptive fallback: ${it.message}")
            }.getOrNull()

            if (dashSource != null) return dashSource
        }

        // 3. Direct adaptive streams (MergingMediaSource: best H.264/VP9 video + best audio)
        buildDirectAdaptiveSource(formatInfo, dataSourceFactory)?.let {
            Log.i(TAG, "Selected Direct MergingMediaSource for client=$clientName")
            return it
        }

        // 4. Remote DASH manifest URL
        if (formatInfo.containsDashUrl()) {
            val dashItem = Media3Item.fromUri(formatInfo.dashManifestUrl)
            return DashMediaSource.Factory(dataSourceFactory).createMediaSource(dashItem)
        }

        // 5. HLS manifest fallback
        if (formatInfo.containsHlsUrl()) {
            val hlsItem = Media3Item.fromUri(formatInfo.hlsManifestUrl)
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(hlsItem)
        }

        // 6. Progressive MP4 fallback
        val fallbackUrl = formatInfo.urlFormats?.firstOrNull { !it.url.isNullOrBlank() }?.url
            ?: formatInfo.createUrlList()?.firstOrNull()
            ?: throw IllegalStateException(
                "Video không có luồng phát tương thích (client=$clientName, sabr=${formatInfo.containsSabrFormats()})"
            )

        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Media3Item.fromUri(fallbackUrl))
    }

    private fun buildDirectAdaptiveSource(
        formatInfo: MediaItemFormatInfo,
        dataSourceFactory: HttpDataSource.Factory
    ): MediaSource? {
        val adaptive = formatInfo.adaptiveFormats?.filter { !it.url.isNullOrBlank() } ?: return null
        if (adaptive.isEmpty()) return null

        val maxH = effectiveMaxVideoHeight()
        val videoFormats = adaptive.filter { it.mimeType?.startsWith("video/", ignoreCase = true) == true }
        val audioFormats = adaptive.filter { it.mimeType?.startsWith("audio/", ignoreCase = true) == true }
        if (videoFormats.isEmpty() || audioFormats.isEmpty()) return null

        val nonAv1Videos = if (prefs.blockAv1) {
            videoFormats.filterNot { it.mimeType?.contains("av01", ignoreCase = true) == true }
                .ifEmpty { videoFormats }
        } else {
            videoFormats
        }

        val preferredVideos = nonAv1Videos
            .filter { it.height in 1..maxH }
            .ifEmpty { nonAv1Videos }

        val bestVideo = preferredVideos.maxWithOrNull(
            compareBy<MediaFormat>(
                { if (it.mimeType?.contains("avc1", ignoreCase = true) == true) 1 else 0 },
                { it.height },
                { it.bitrate?.toIntOrNull() ?: 0 }
            )
        ) ?: return null

        val bestAudio = audioFormats.maxWithOrNull(
            compareBy<MediaFormat>(
                { if (it.mimeType?.contains("mp4a", ignoreCase = true) == true) 1 else 0 },
                { it.bitrate?.toIntOrNull() ?: 0 }
            )
        ) ?: return null

        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Media3Item.fromUri(bestVideo.url))
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Media3Item.fromUri(bestAudio.url))
        return MergingMediaSource(videoSource, audioSource)
    }

    private fun checkSponsorSegments() {
        if (!prefs.sponsorBlockEnabled || activeSegments.isEmpty()) return
        val posMs = exoPlayer.currentPosition
        for (segment in activeSegments) {
            val key = "${segment.startMs}-${segment.endMs}"
            if (skippedSegmentKeys.contains(key)) continue
            if (posMs in segment.startMs until (segment.endMs - 500L)) {
                skippedSegmentKeys.add(key)
                exoPlayer.seekTo(segment.endMs)
                val label = when (segment.category) {
                    SponsorSegment.CATEGORY_SPONSOR -> "Đã bỏ qua đoạn tài trợ"
                    SponsorSegment.CATEGORY_SELF_PROMO -> "Đã bỏ qua tự quảng cáo"
                    SponsorSegment.CATEGORY_INTRO -> "Đã bỏ qua intro"
                    SponsorSegment.CATEGORY_OUTRO -> "Đã bỏ qua outro"
                    SponsorSegment.CATEGORY_INTERACTION -> "Đã bỏ qua kêu gọi đăng ký"
                    else -> "Đã bỏ qua đoạn không liên quan"
                }
                PlaybackStateStore.update { it.copy(statusNotice = label) }
                mainHandler.postDelayed({ PlaybackStateStore.clearNotice() }, 3_000L)
                break
            }
        }
    }

    private fun syncHistoryPeriodically() {
        val video = PlaybackStateStore.state.value.currentVideo ?: return
        val sec = exoPlayer.currentPosition / 1000L
        if (sec < 10L) return
        if (lastHistorySyncVideoId == video.videoId && kotlin.math.abs(sec - lastHistorySyncSec) < 30L) {
            return
        }
        lastHistorySyncVideoId = video.videoId
        lastHistorySyncSec = sec
        serviceScope.launch {
            YouTubeRepository.updateHistoryPosition(video.videoId, sec.toFloat())
        }
    }

    /**
     * Intercepts MediaSession commands from car steering-wheel buttons (Next/Prev/Play/Pause/Seek)
     * and exposes current video title/channel/artwork to the car head unit widget & ODO cluster.
     */
    private inner class CarForwardingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(COMMAND_SEEK_FORWARD)
                .add(COMMAND_SEEK_BACK)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_FORWARD,
                COMMAND_SEEK_BACK -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true

        override fun seekToNext() = playNext()
        override fun seekToNextMediaItem() = playNext()

        override fun seekToPrevious() = playPrevious()
        override fun seekToPreviousMediaItem() = playPrevious()

        override fun seekForward() = seekBy(10_000L)
        override fun seekBack() = seekBy(-10_000L)

        override fun getMediaMetadata(): MediaMetadata {
            val video = PlaybackStateStore.state.value.currentVideo ?: return super.getMediaMetadata()
            return MediaMetadata.Builder()
                .setTitle(video.title)
                .setArtist(video.author)
                .setDisplayTitle(video.title)
                .setArtworkUri(Uri.parse(video.thumbnailUrl))
                .build()
        }
    }

    companion object {
        private const val TAG = "CarPlaybackService"
        private const val SPONSOR_CHECK_INTERVAL_MS = 500L
        private const val MAX_ERROR_RETRIES = 3
        private const val MAX_HISTORY_SIZE = 50
        private const val MAX_SESSION_MEMORY = 100

        // Listener signals for Up Next
        private const val ACCEPT_LISTEN_CAP_MS = 120_000L
        private const val ACCEPT_LISTEN_UNKNOWN_DURATION_MS = 60_000L
        private const val EARLY_SKIP_CAP_MS = 45_000L
        private const val EARLY_SKIP_UNKNOWN_DURATION_MS = 30_000L
        private const val MIN_LISTEN_FOR_SKIP_SIGNAL_MS = 2_000L
        private const val RECENT_PLAYED_FOR_RANKING = 15
        private const val MAX_RELATED_HITS_MEMORY = 1_500

        @Volatile
        var instance: CarPlaybackService? = null
            private set
    }
}
