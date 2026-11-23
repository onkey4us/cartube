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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.VideoItem
import com.cartube.app.data.YouTubeRepository
import com.cartube.app.ui.PlayerActivity
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
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

@UnstableApi
class CarPlaybackService : MediaSessionService() {

    private lateinit var prefs: CarPrefs
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: CarForwardingPlayer
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeSegments: List<SponsorSegment> = emptyList()
    private val skippedSegmentKeys = HashSet<String>()
    private var isVideoTrackEnabled: Boolean = true
    private var lastHistorySyncVideoId: String? = null
    private var lastHistorySyncSec: Long = -1L
    private var errorRetryCount: Int = 0
    private var lastAttemptedVideoId: String? = null

    private val sponsorTickRunnable = object : Runnable {
        override fun run() {
            if (::exoPlayer.isInitialized && exoPlayer.isPlaying) {
                checkSponsorSegments()
                syncHistoryPeriodically()
            }
            mainHandler.postDelayed(this, SPONSOR_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = CarPrefs(this)

        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(1920, prefs.maxVideoHeight)
                .setForceHighestSupportedBitrate(false)
                .build()
        }

        // Lean buffer tuned for car head units (fast start, low RAM usage)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                35_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true) // Auto handle car AudioFocus (navi/reverse)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackStateStore.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error (${error.errorCodeName}): ${error.message}", error)
                val current = PlaybackStateStore.state.value.currentVideo
                val currentQueue = PlaybackStateStore.state.value.queue
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
                    playVideoInternal(current, currentQueue, resetRetry = false)
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
            .build()

        mainHandler.postDelayed(sponsorTickRunnable, SPONSOR_CHECK_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(sponsorTickRunnable)
        loadJob?.cancel()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        exoPlayer.release()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun getPlayer(): ExoPlayer = exoPlayer

    fun playVideo(video: VideoItem, queue: List<VideoItem> = emptyList()) {
        playVideoInternal(video, queue, resetRetry = true)
    }

    private fun playVideoInternal(video: VideoItem, queue: List<VideoItem>, resetRetry: Boolean) {
        if (resetRetry || lastAttemptedVideoId != video.videoId) {
            errorRetryCount = 0
            lastAttemptedVideoId = video.videoId
        }
        val effectiveQueue = if (queue.isNotEmpty()) queue else listOf(video)
        val idx = effectiveQueue.indexOfFirst { it.videoId == video.videoId }.let {
            if (it >= 0) it else 0
        }

        PlaybackStateStore.update {
            it.copy(
                currentVideo = video,
                queue = effectiveQueue,
                currentIndex = idx,
                sponsorSegments = emptyList(),
                isLoading = true,
                errorMessage = null,
                statusNotice = if (resetRetry) null else it.statusNotice
            )
        }
        activeSegments = emptyList()
        skippedSegmentKeys.clear()
        lastHistorySyncVideoId = null
        lastHistorySyncSec = -1L

        loadJob?.cancel()
        loadJob = serviceScope.launch {
            try {
                val bundle = YouTubeRepository.loadPlaybackBundle(
                    videoId = video.videoId,
                    clickTrackingParams = video.clickTrackingParams,
                    loadSponsorSegments = prefs.sponsorBlockEnabled
                )

                val enrichedVideo = video.copy(
                    title = bundle.formatInfo.title?.takeIf { it.isNotBlank() } ?: video.title,
                    author = bundle.formatInfo.author?.takeIf { it.isNotBlank() } ?: video.author
                )

                activeSegments = bundle.sponsorSegments
                val mergedQueue = mergeQueueWithRelated(effectiveQueue, idx, bundle.relatedVideos)

                PlaybackStateStore.update {
                    it.copy(
                        currentVideo = enrichedVideo,
                        queue = mergedQueue,
                        currentIndex = idx,
                        relatedVideos = bundle.relatedVideos,
                        sponsorSegments = bundle.sponsorSegments,
                        isLoading = false,
                        statusNotice = null,
                        errorMessage = null
                    )
                }

                val mediaSource = withContext(Dispatchers.IO) {
                    buildMediaSource(bundle.formatInfo)
                }

                applyTrackPreferences()
                exoPlayer.setMediaSource(mediaSource)
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
    }

    fun playNext() {
        val state = PlaybackStateStore.state.value
        val nextIndex = state.currentIndex + 1
        if (nextIndex in state.queue.indices) {
            playVideo(state.queue[nextIndex], state.queue)
        } else if (state.relatedVideos.isNotEmpty()) {
            val next = state.relatedVideos.first()
            playVideo(next, state.queue + state.relatedVideos)
        }
    }

    fun playPrevious() {
        if (exoPlayer.currentPosition > 5_000L) {
            exoPlayer.seekTo(0L)
            return
        }
        val state = PlaybackStateStore.state.value
        val prevIndex = state.currentIndex - 1
        if (prevIndex in state.queue.indices) {
            playVideo(state.queue[prevIndex], state.queue)
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

    fun updateMaxVideoHeight(maxHeight: Int) {
        prefs.maxVideoHeight = maxHeight
        applyTrackPreferences()
    }

    private fun applyTrackPreferences() {
        val maxHeight = prefs.maxVideoHeight
        val maxWidth = when (maxHeight) {
            480 -> 854
            720 -> 1280
            else -> 1920
        }
        val builder = trackSelector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !isVideoTrackEnabled)
            .setMaxVideoSize(maxWidth, maxHeight)

        if (prefs.blockAv1) {
            // Prefer hardware-decoded H.264 / VP9 on i.MX8Q instead of software AV1
            builder.setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9)
        } else {
            builder.setPreferredVideoMimeTypes()
        }

        trackSelector.parameters = builder.build()
    }

    private fun buildMediaSource(formatInfo: MediaItemFormatInfo): MediaSource {
        val clientName = formatInfo.clientInfo?.clientName ?: "unknown"
        Log.i(
            TAG,
            "Building MediaSource client=$clientName dash=${formatInfo.containsDashFormats()} " +
                "sabr=${formatInfo.containsSabrFormats()} hls=${formatInfo.containsHlsUrl()} " +
                "dashUrl=${formatInfo.containsDashUrl()} urls=${formatInfo.urlFormats?.size ?: 0}"
        )

        val headers = HashMap<String, String>()
        headers["Referer"] = "https://www.youtube.com/"
        headers["Origin"] = "https://www.youtube.com"
        formatInfo.visitorCookie?.takeIf { it.isNotBlank() }?.let {
            headers["Cookie"] = it
        }
        val userAgent = formatInfo.clientInfo?.userAgent?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 9; CarTube) AppleWebKit/537.36"

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)

        // 1. Live or HLS stream
        if (formatInfo.isLive && formatInfo.containsHlsUrl()) {
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
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest)
            }.onFailure {
                Log.w(TAG, "MPD parse failed, trying direct adaptive fallback: ${it.message}")
            }.getOrNull()

            if (dashSource != null) return dashSource
        }

        // 3. Direct adaptive streams (MergingMediaSource: best H.264/VP9 video + best audio)
        buildDirectAdaptiveSource(formatInfo, dataSourceFactory)?.let { return it }

        // 4. Remote DASH manifest URL
        if (formatInfo.containsDashUrl()) {
            val dashItem = Media3Item.fromUri(formatInfo.dashManifestUrl)
            return DashMediaSource.Factory(dataSourceFactory).createMediaSource(dashItem)
        }

        // 5. HLS manifest URL (used by VISIONOS / IOS clients)
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
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): MediaSource? {
        val adaptive = formatInfo.adaptiveFormats?.filter { !it.url.isNullOrBlank() } ?: return null
        if (adaptive.isEmpty()) return null

        val maxH = prefs.maxVideoHeight
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

    private fun mergeQueueWithRelated(
        baseQueue: List<VideoItem>,
        currentIdx: Int,
        related: List<VideoItem>
    ): List<VideoItem> {
        // If user tapped a single video (or is near end of queue), append YouTube related items
        if (baseQueue.size - 1 - currentIdx >= 3 || related.isEmpty()) {
            return baseQueue
        }
        val existingIds = baseQueue.mapTo(HashSet()) { it.videoId }
        val extra = related.filter { existingIds.add(it.videoId) }.take(15)
        return baseQueue + extra
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

        @Volatile
        var instance: CarPlaybackService? = null
            private set
    }
}
