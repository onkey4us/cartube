package com.cartube.app.data

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

data class FeedPage(
    val items: List<VideoItem>,
    val sourceGroup: MediaGroup?
)

data class PlaybackExtras(
    val metadata: MediaItemMetadata?,
    val relatedVideos: List<VideoItem>,
    val sponsorSegments: List<SponsorSegment>
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
        val groups = serviceManager.contentService.homeObserve.blockingFirstOrNull().orEmpty()
        flattenGroups(groups)
    }

    suspend fun loadTrending(): FeedPage = withContext(Dispatchers.IO) {
        val groups = serviceManager.contentService.trendingObserve.blockingFirstOrNull().orEmpty()
        val page = flattenGroups(groups)
        if (page.items.isNotEmpty()) page else search("Video thịnh hành Việt Nam mới nhất")
    }

    suspend fun loadMusic(): FeedPage = withContext(Dispatchers.IO) {
        val groups = serviceManager.contentService.musicObserve.blockingFirstOrNull().orEmpty()
        flattenGroups(groups)
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
     * [PlaybackExtras.relatedVideos] is returned in YouTube's own order; the playback service ranks
     * it once, together with what it knows about the listening session.
     */
    suspend fun loadPlaybackExtras(
        videoId: String,
        loadSponsorSegments: Boolean
    ): PlaybackExtras = withContext(Dispatchers.IO) {
        coroutineScope {
            val metaDeferred = async {
                runCatching { serviceManager.mediaItemService.getMetadata(videoId) }.getOrNull()
            }
            val sponsorDeferred = async {
                if (!loadSponsorSegments) emptyList()
                else runCatching {
                    serviceManager.mediaItemService.getSponsorSegments(videoId, sponsorCategories).orEmpty()
                }.getOrDefault(emptyList())
            }
            val metadata = metaDeferred.await()
            val related = buildRelatedList(metadata, currentVideoId = videoId)
            val segments = sponsorDeferred.await()
            PlaybackExtras(
                metadata = metadata,
                relatedVideos = related,
                sponsorSegments = segments
            )
        }
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

    private fun buildRelatedList(
        metadata: MediaItemMetadata?,
        currentVideoId: String
    ): List<VideoItem> {
        if (metadata == null) return emptyList()
        val seen = HashSet<String>()
        seen.add(currentVideoId)
        val rawList = ArrayList<VideoItem>()

        metadata.nextVideo
            ?.let { VideoItem.from(it, allowShorts = false, allowUpcoming = false, shelfIndex = 0) }
            ?.let {
                if (seen.add(it.videoId)) rawList.add(it)
            }

        metadata.suggestions.orEmpty().forEachIndexed { groupIdx, group ->
            if (group.type != MediaGroup.TYPE_SHORTS) {
                for (raw in group.mediaItems.orEmpty()) {
                    val video = VideoItem.from(
                        item = raw,
                        allowShorts = false,
                        allowUpcoming = false,
                        shelfIndex = groupIdx
                    ) ?: continue
                    if (seen.add(video.videoId)) {
                        rawList.add(video)
                    }
                }
            }
        }

        return rawList
    }

    private fun <T> Observable<T>.blockingFirstOrNull(): T? {
        return runCatching { this.firstElement().blockingGet() }.getOrNull()
    }
}
