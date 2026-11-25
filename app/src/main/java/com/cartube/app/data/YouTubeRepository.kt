package com.cartube.app.data

import android.util.Log
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class FeedPage(
    val items: List<VideoItem>,
    val sourceGroup: MediaGroup?
)

data class PlaybackExtras(
    val metadata: MediaItemMetadata?,
    /** YouTube's Mix (radio) for this video, in YouTube's order; empty when it has none. */
    val mixVideos: List<VideoItem>,
    /** The Mix shelf itself, kept so more of it can be loaded later. */
    val mixGroup: MediaGroup?,
    val relatedVideos: List<VideoItem>,
    val sponsorSegments: List<SponsorSegment>
)

/** More Up Next items, and the shelf to continue from next time. */
data class MixPage(
    val items: List<VideoItem>,
    val group: MediaGroup?
)

sealed class SignInState {
    object Loading : SignInState()
    data class CodeReady(val code: String, val verifyUrl: String = "https://www.youtube.com/activate") : SignInState()
    data class Success(val account: Account?) : SignInState()
    data class Error(val message: String) : SignInState()
}

object YouTubeRepository {

    private val serviceManager
        get() = YouTubeServiceManager.instance()

    private val sponsorCategories = setOf(
        SponsorSegment.CATEGORY_SPONSOR,
        SponsorSegment.CATEGORY_SELF_PROMO,
        SponsorSegment.CATEGORY_INTERACTION,
        SponsorSegment.CATEGORY_INTRO,
        SponsorSegment.CATEGORY_OUTRO,
        SponsorSegment.CATEGORY_MUSIC_OFF_TOPIC
    )

    suspend fun loadHome(): FeedPage = withContext(Dispatchers.IO) {
        flattenGroups(serviceManager.contentService.homeObserve.blockingBatches())
    }

    suspend fun loadTrending(): FeedPage = withContext(Dispatchers.IO) {
        val groups = serviceManager.contentService.trendingObserve.blockingFirstOrNull().orEmpty()
        val page = flattenGroups(groups)
        if (page.items.isNotEmpty()) page else search("Video thịnh hành Việt Nam mới nhất")
    }

    suspend fun loadMusic(): FeedPage = withContext(Dispatchers.IO) {
        // The first batch is only the "liked music" row; the music page itself comes after it.
        flattenGroups(serviceManager.contentService.musicObserve.blockingBatches())
    }

    suspend fun loadNews(): FeedPage = withContext(Dispatchers.IO) {
        val groups = serviceManager.contentService.newsObserve.blockingFirstOrNull().orEmpty()
        val page = flattenGroups(groups)
        if (page.items.isNotEmpty()) page else search("Tin tức thời sự mới nhất hôm nay")
    }

    suspend fun loadLive(): FeedPage = withContext(Dispatchers.IO) {
        val groups = serviceManager.contentService.liveObserve.blockingFirstOrNull().orEmpty()
        val page = flattenGroups(groups)
        if (page.items.isNotEmpty()) page else search("Trực tiếp âm nhạc tin tức Việt Nam")
    }

    suspend fun loadSubscriptions(): FeedPage = withContext(Dispatchers.IO) {
        val group = serviceManager.contentService.subscriptionsObserve.blockingFirstOrNull()
        FeedPage(extractItems(group), group)
    }

    suspend fun loadHistory(): FeedPage = withContext(Dispatchers.IO) {
        val group = serviceManager.contentService.historyObserve.blockingFirstOrNull()
        FeedPage(extractItems(group), group)
    }

    suspend fun search(query: String): FeedPage = withContext(Dispatchers.IO) {
        val groups = serviceManager.contentService.getSearchObserve(query).blockingFirstOrNull().orEmpty()
        flattenGroups(groups)
    }

    suspend fun loadMore(group: MediaGroup): FeedPage = withContext(Dispatchers.IO) {
        val next = serviceManager.contentService.continueGroup(group)
        FeedPage(extractItems(next), next)
    }

    suspend fun loadFormatInfo(
        videoId: String,
        clickTrackingParams: String?
    ): MediaItemFormatInfo = withContext(Dispatchers.IO) {
        fetchFormatWithFallback(videoId, clickTrackingParams)
            ?: throw IllegalStateException("Không lấy được luồng phát cho video này.")
    }

    /**
     * True when YouTube answers at all. Tells a dead link (tunnel, phone hotspot without 4G: the
     * Wi-Fi stays "connected") apart from a stream source that is broken while the internet works.
     */
    suspend fun isInternetReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://www.youtube.com/generate_204").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.responseCode > 0
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Resolves the streams of a video that is about to play. One attempt only: unlike
     * [loadFormatInfo] it never switches the InnerTube client, so it cannot disturb the video
     * that is playing right now. Returns null when the result is not usable.
     */
    suspend fun prefetchFormatInfo(
        videoId: String,
        clickTrackingParams: String?
    ): MediaItemFormatInfo? = withContext(Dispatchers.IO) {
        runCatching {
            serviceManager.mediaItemService.getFormatInfo(videoId, clickTrackingParams)
        }.getOrNull()?.takeIf { isPlayableByMedia3(it) }
    }

    /**
     * [PlaybackExtras.relatedVideos] is returned in YouTube's own order; the playback service ranks
     * it once, together with what it knows about the listening session.
     */
    suspend fun loadPlaybackExtras(
        videoId: String,
        loadSponsorSegments: Boolean
    ): PlaybackExtras = withContext(Dispatchers.IO) {
        coroutineScope {
            val metaDeferred = async {
                // Asking for the video inside its Mix playlist returns the Mix AND the usual
                // suggestions in one request. Videos without a Mix fall back to the plain request.
                runCatching {
                    serviceManager.mediaItemService.getMetadata(videoId, mixPlaylistId(videoId), 0, null)
                }.getOrNull()?.takeIf { !it.suggestions.isNullOrEmpty() }
                    ?: runCatching { serviceManager.mediaItemService.getMetadata(videoId) }.getOrNull()
            }
            val sponsorDeferred = async {
                if (!loadSponsorSegments) emptyList()
                else runCatching {
                    serviceManager.mediaItemService.getSponsorSegments(videoId, sponsorCategories).orEmpty()
                }.getOrDefault(emptyList())
            }
            val metadata = metaDeferred.await()
            val mixShelf = findMixShelf(metadata, videoId)
            val mix = extractItems(mixShelf).filter { it.videoId != videoId }
            val related = buildRelatedList(metadata, currentVideoId = videoId, skipShelf = mixShelf)
            Log.i(RECS_TAG, "EXTRAS seed=$videoId mix=${mix.size} related=${related.size}")
            val segments = sponsorDeferred.await()
            PlaybackExtras(
                metadata = metadata,
                mixVideos = mix,
                mixGroup = mixShelf,
                relatedVideos = related,
                sponsorSegments = segments
            )
        }
    }

    /**
     * More songs for the Up Next list: the next page of the Mix shelf when YouTube offers one,
     * otherwise the Mix of [fallbackVideoId] (the last song already listed), so the list can go
     * on for as long as the listener keeps scrolling.
     */
    suspend fun loadMoreMix(group: MediaGroup?, fallbackVideoId: String?): MixPage = withContext(Dispatchers.IO) {
        if (group != null && !group.nextPageKey.isNullOrBlank()) {
            val next = runCatching { serviceManager.contentService.continueGroup(group) }.getOrNull()
            val items = extractItems(next)
            if (items.isNotEmpty()) {
                return@withContext MixPage(items, next)
            }
        }
        if (fallbackVideoId.isNullOrBlank()) {
            return@withContext MixPage(emptyList(), null)
        }
        val metadata = runCatching {
            serviceManager.mediaItemService.getMetadata(fallbackVideoId, mixPlaylistId(fallbackVideoId), 0, null)
        }.getOrNull()
        val shelf = findMixShelf(metadata, fallbackVideoId)
        MixPage(extractItems(shelf).filter { it.videoId != fallbackVideoId }, shelf)
    }

    private fun fetchFormatWithFallback(
        videoId: String,
        clickTrackingParams: String?
    ): MediaItemFormatInfo? {
        var lastInfo: MediaItemFormatInfo? = null
        // Cycle through InnerTube clients until we get a Media3-playable format (DASH / HLS / Progressive).
        repeat(10) { attempt ->
            val info = runCatching {
                serviceManager.mediaItemService.getFormatInfo(videoId, clickTrackingParams)
            }.getOrNull()
            lastInfo = info
            if (isPlayableByMedia3(info)) {
                return info
            }
            if (attempt < 9) {
                serviceManager.switchNextClientNow()
            }
        }
        return lastInfo
    }

    fun isPlayableByMedia3(info: MediaItemFormatInfo?): Boolean {
        if (info == null || info.isUnplayable) return false
        val hasDirectAdaptive = info.adaptiveFormats?.any { !it.url.isNullOrBlank() } == true
        val hasUrlFormats = info.urlFormats?.any { !it.url.isNullOrBlank() } == true
        return info.containsDashFormats() ||
            info.containsHlsUrl() ||
            info.containsDashUrl() ||
            hasDirectAdaptive ||
            hasUrlFormats
    }

    suspend fun updateHistoryPosition(videoId: String, positionSec: Float) = withContext(Dispatchers.IO) {
        if (serviceManager.signInService.isSigned) {
            runCatching {
                serviceManager.mediaItemService.updateHistoryPosition(videoId, positionSec)
            }
        }
    }

    fun isSignedIn(): Boolean = runCatching { serviceManager.signInService.isSigned }.getOrDefault(false)

    fun getSelectedAccount(): Account? = runCatching { serviceManager.signInService.selectedAccount }.getOrNull()

    fun signOutAll() {
        runCatching {
            val signIn = serviceManager.signInService
            signIn.accounts?.toList()?.forEach { account ->
                signIn.removeAccount(account)
            }
            serviceManager.invalidateCache()
        }
    }

    fun signInFlow(): Flow<SignInState> = callbackFlow {
        trySend(SignInState.Loading)
        val disposable: Disposable = serviceManager.signInService
            .signInObserve()
            .subscribeOn(Schedulers.io())
            .subscribe(
                { code ->
                    trySend(SignInState.CodeReady(code))
                },
                { err ->
                    trySend(SignInState.Error(err.message ?: "Lỗi đăng nhập OAuth2"))
                    close()
                },
                {
                    serviceManager.invalidateCache()
                    trySend(SignInState.Success(getSelectedAccount()))
                    close()
                }
            )
        awaitClose {
            if (!disposable.isDisposed) {
                disposable.dispose()
            }
        }
    }

    private fun flattenGroups(groups: List<MediaGroup>): FeedPage {
        val seen = HashSet<String>()
        val items = ArrayList<VideoItem>()
        var continuableGroup: MediaGroup? = null
        for (group in groups) {
            if (group.type == MediaGroup.TYPE_SHORTS) continue
            if (continuableGroup == null && !group.nextPageKey.isNullOrBlank()) {
                continuableGroup = group
            }
            for (raw in group.mediaItems.orEmpty()) {
                val video = VideoItem.from(raw, allowShorts = false) ?: continue
                if (seen.add(video.videoId)) {
                    items.add(video)
                }
            }
        }
        return FeedPage(items, continuableGroup ?: groups.firstOrNull { it.type != MediaGroup.TYPE_SHORTS })
    }

    private fun extractItems(group: MediaGroup?): List<VideoItem> {
        if (group == null || group.type == MediaGroup.TYPE_SHORTS) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<VideoItem>()
        for (raw in group.mediaItems.orEmpty()) {
            val video = VideoItem.from(raw, allowShorts = false) ?: continue
            if (seen.add(video.videoId)) {
                result.add(video)
            }
        }
        return result
    }

    private const val RECS_TAG = "CarTubeRecs"
    private const val MIN_MIX_ITEMS = 5
    private const val MAX_FEED_BATCHES = 4L
    private const val FEED_BATCH_WINDOW_SEC = 10L

    private fun mixPlaylistId(videoId: String) = "RD$videoId"

    /** The shelf that holds this video's Mix: its items all belong to the playlist "RD<videoId>". */
    private fun findMixShelf(metadata: MediaItemMetadata?, videoId: String): MediaGroup? {
        val mixId = mixPlaylistId(videoId)
        return metadata?.suggestions.orEmpty().firstOrNull { group ->
            group.mediaItems.orEmpty().count { it.playlistId == mixId } >= MIN_MIX_ITEMS
        }
    }

    private fun buildRelatedList(
        metadata: MediaItemMetadata?,
        currentVideoId: String,
        skipShelf: MediaGroup? = null
    ): List<VideoItem> {
        if (metadata == null) return emptyList()
        val seen = HashSet<String>()
        seen.add(currentVideoId)
        val rawList = ArrayList<VideoItem>()

        metadata.nextVideo
            ?.let { VideoItem.from(it, allowShorts = false, allowUpcoming = false, shelfIndex = 0) }
            ?.let {
                if (seen.add(it.videoId)) rawList.add(it.copy(isAutoplayPick = true))
            }

        metadata.suggestions.orEmpty().forEachIndexed { groupIdx, group ->
            if (group !== skipShelf && group.type != MediaGroup.TYPE_SHORTS) {
                for (raw in group.mediaItems.orEmpty()) {
                    val video = VideoItem.from(
                        item = raw,
                        allowShorts = false,
                        allowUpcoming = false,
                        shelfIndex = groupIdx
                    ) ?: continue
                    if (seen.add(video.videoId)) {
                        rawList.add(video)
                    } else if (video.durationMs > 0L) {
                        // The autoplay pick comes without a duration; take it from its shelf copy.
                        val first = rawList.firstOrNull()
                        if (first != null && first.videoId == video.videoId && first.durationMs <= 0L) {
                            rawList[0] = video.copy(isAutoplayPick = true)
                        }
                    }
                }
            }
        }

        return rawList
    }

    /**
     * YouTube sends a feed page in several batches of shelves. Collect the first few instead of
     * only the first one, without waiting forever on a slow link.
     */
    private fun Observable<List<MediaGroup>>.blockingBatches(): List<MediaGroup> {
        return runCatching {
            this.take(MAX_FEED_BATCHES)
                .take(FEED_BATCH_WINDOW_SEC, TimeUnit.SECONDS)
                .onErrorReturnItem(emptyList())
                .toList()
                .blockingGet()
                .flatten()
        }.getOrDefault(emptyList())
    }

    private fun <T> Observable<T>.blockingFirstOrNull(): T? {
        return runCatching { this.firstElement().blockingGet() }.getOrNull()
    }
}
