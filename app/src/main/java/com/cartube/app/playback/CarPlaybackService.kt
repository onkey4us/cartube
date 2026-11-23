package com.cartube.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.VideoItem
import com.cartube.app.data.YouTubeRepository
import com.cartube.app.ui.PlayerActivity
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
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
                PlaybackStateStore.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Lỗi phát video: ${error.errorCodeName}"
                    )
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
                statusNotice = null
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
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !isVideoTrackEnabled)
            .setMaxVideoSize(maxWidth, maxHeight)
            .build()
    }

    private fun buildMediaSource(formatInfo: MediaItemFormatInfo): MediaSource {
        val headers = HashMap<String, String>()
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
            .apply {
                if (headers.isNotEmpty()) {
                    setDefaultRequestProperties(headers)
                }
            }

        // 1. Live or HLS stream
        if (formatInfo.isLive && formatInfo.containsHlsUrl()) {
            val hlsItem = Media3Item.fromUri(formatInfo.hlsManifestUrl)
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(hlsItem)
        }

        // 2. Adaptive DASH (ad-free MPD generated by MediaServiceCore)
        if (formatInfo.containsDashFormats()) {
            val rawStream = formatInfo.createMpdStream()
            if (rawStream != null) {
                val mpdXml = rawStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val filteredXml = if (prefs.blockAv1) stripAv1RepresentationsIfSafe(mpdXml) else mpdXml
                val manifest = DashManifestParser().parse(
                    Uri.parse("https://manifest.googlevideo.com/"),
                    ByteArrayInputStream(filteredXml.toByteArray(StandardCharsets.UTF_8))
                )
                return DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest)
            }
        }

        // 3. Remote DASH manifest URL
        if (formatInfo.containsDashUrl()) {
            val dashItem = Media3Item.fromUri(formatInfo.dashManifestUrl)
            return DashMediaSource.Factory(dataSourceFactory).createMediaSource(dashItem)
        }

        // 4. HLS fallback
        if (formatInfo.containsHlsUrl()) {
            val hlsItem = Media3Item.fromUri(formatInfo.hlsManifestUrl)
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(hlsItem)
        }

        // 5. Progressive MP4 fallback
        val fallbackUrl = formatInfo.urlFormats?.firstOrNull { !it.url.isNullOrBlank() }?.url
            ?: formatInfo.createUrlList()?.firstOrNull()
            ?: throw IllegalStateException("Video không có định dạng phát khả dụng")

        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Media3Item.fromUri(fallbackUrl))
    }

    /**
     * Prevents software AV1 decoding on i.MX8Q / Android 9 head units by removing AV1
     * representations from the MPD when H.264 (avc1) or VP9 (vp9/vp09) tracks are present.
     */
    private fun stripAv1RepresentationsIfSafe(mpdXml: String): String {
        val hasHardwareCodec = mpdXml.contains("codecs=\"avc1", ignoreCase = true) ||
            mpdXml.contains("codecs=\"vp9", ignoreCase = true) ||
            mpdXml.contains("codecs=\"vp09", ignoreCase = true)
        if (!hasHardwareCodec) return mpdXml

        val av1BlockRegex = Regex(
            pattern = "<Representation\\b[^>]*codecs=\"av01[^\"]*\"[^>]*>.*?</Representation>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return av1BlockRegex.replace(mpdXml, "")
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
        private const val SPONSOR_CHECK_INTERVAL_MS = 500L

        @Volatile
        var instance: CarPlaybackService? = null
            private set
    }
}
