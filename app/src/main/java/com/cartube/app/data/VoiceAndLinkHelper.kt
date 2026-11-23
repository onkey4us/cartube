package com.cartube.app.data

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.speech.RecognizerIntent

sealed class VoiceOrLinkTarget {
    data class Video(val videoId: String) : VoiceOrLinkTarget()
    data class SearchAndPlay(val query: String) : VoiceOrLinkTarget()
}

object VoiceAndLinkHelper {

    private const val ACTION_GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"
    private const val ACTION_VIDEO_PLAY_FROM_SEARCH = "android.media.action.VIDEO_PLAY_FROM_SEARCH"

    private val YT_URL_REGEX = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com/\S+|youtu\.be/\S+)""",
        RegexOption.IGNORE_CASE
    )
    private val VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{11}$""")

    private val TRAILING_APP_SUFFIX_REGEX = Regex(
        """\s+(?:trên|bằng|ở|qua|với|trong)\s+(?:ứng\s+dụng\s+|app\s+)?(?:youtube|you\s+tube|cartube|car\s+tube)\s*$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val LEADING_VIDEO_OR_SONG_PREFIX_REGEX = Regex(
        """^(?:hãy\s+|ơi\s+|làm\s+ơn\s+)?(?:mở|bật|phát|chơi|xem|nghe|tìm\s+kiếm|tìm)\s+(?:giúp\s+|hộ\s+|cho\s+tôi\s+|cho\s+anh\s+)?(?:video\s+bài\s+hát|video\s+ca\s+khúc|video\s+nhạc|bài\s+hát|ca\s+khúc|video|mv|clip|bài)\s+""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val LEADING_MUSIC_VERB_REGEX = Regex(
        """^(?:hãy\s+|ơi\s+)?(?:mở|bật|phát|chơi|nghe|xem|tìm\s+kiếm|tìm)\s+(nhạc\b.*)$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val LEADING_GENERIC_VERB_REGEX = Regex(
        """^(?:hãy\s+|ơi\s+)?(?:mở|bật|phát|tìm\s+kiếm|tìm)\s+(.+)$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun buildSpeechRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên bài hát hoặc video cần mở...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    /**
     * Cleans natural Vietnamese voice commands such as:
     * - "Mở video Lệ Quyên" -> "Lệ Quyên"
     * - "Mở bài hát Vùng lá me bay trên YouTube" -> "Vùng lá me bay"
     * - "Mở nhạc trẻ remix" -> "nhạc trẻ remix"
     */
    fun cleanVoiceQuery(raw: String): String {
        var q = raw.trim()
        if (q.isEmpty()) return ""

        q = TRAILING_APP_SUFFIX_REGEX.replace(q, "").trim()

        when {
            LEADING_VIDEO_OR_SONG_PREFIX_REGEX.containsMatchIn(q) -> {
                q = LEADING_VIDEO_OR_SONG_PREFIX_REGEX.replaceFirst(q, "").trim()
            }
            LEADING_MUSIC_VERB_REGEX.matches(q) -> {
                q = LEADING_MUSIC_VERB_REGEX.find(q)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
            LEADING_GENERIC_VERB_REGEX.matches(q) -> {
                q = LEADING_GENERIC_VERB_REGEX.find(q)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
        }

        val normalizedLower = q.lowercase()
        if (q.isEmpty() ||
            normalizedLower == "nhạc" ||
            normalizedLower == "video" ||
            normalizedLower == "bài hát" ||
            normalizedLower == "youtube" ||
            normalizedLower == "cartube"
        ) {
            return "Nhạc tuyển chọn hay nhất"
        }
        return q
    }

    fun parseIntent(intent: Intent?): VoiceOrLinkTarget? {
        if (intent == null) return null
        val action = intent.action.orEmpty()

        // 1. Direct URI / Deep-link (vnd.youtube:..., youtube.com/watch?v=..., youtu.be/..., youtube.com/results?...)
        val dataUri = intent.data
        if (dataUri != null && (action == Intent.ACTION_VIEW || action.isEmpty())) {
            parseUri(dataUri)?.let { return it }
        }

        // 2. Voice Play-From-Search ("Mở bài hát...", "Mở nhạc...", "Mở video...")
        if (action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ||
            action == ACTION_VIDEO_PLAY_FROM_SEARCH
        ) {
            dataUri?.let { uri ->
                parseUri(uri)?.let { return it }
            }
            val rawQuery = extractVoiceQueryFromExtras(intent)
            val cleaned = if (rawQuery.isBlank()) {
                "Nhạc tuyển chọn hay nhất"
            } else {
                cleanVoiceQuery(rawQuery)
            }
            return VoiceOrLinkTarget.SearchAndPlay(cleaned)
        }

        // 3. Search Actions (Google Assistant / System Search)
        if (action == Intent.ACTION_SEARCH || action == ACTION_GMS_SEARCH) {
            val rawQuery = extractVoiceQueryFromExtras(intent)
            if (rawQuery.isNotBlank()) {
                return VoiceOrLinkTarget.SearchAndPlay(cleanVoiceQuery(rawQuery))
            }
        }

        // 4. Shared Link or Text (Intent.ACTION_SEND)
        if (action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (sharedText.isNotEmpty()) {
                val urlMatch = YT_URL_REGEX.find(sharedText)?.value
                if (urlMatch != null) {
                    parseUri(Uri.parse(urlMatch))?.let { return it }
                }
                return VoiceOrLinkTarget.SearchAndPlay(cleanVoiceQuery(sharedText))
            }
        }

        return null
    }

    fun parseUri(uri: Uri): VoiceOrLinkTarget? {
        val scheme = uri.scheme?.lowercase().orEmpty()

        // Handle vnd.youtube:VIDEO_ID and vnd.youtube://...
        if (scheme == "vnd.youtube" || scheme == "vnd.youtube.launch") {
            uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let {
                return VoiceOrLinkTarget.Video(it.trim())
            }
            uri.getQueryParameter("search_query")?.takeIf { it.isNotBlank() }?.let {
                return VoiceOrLinkTarget.SearchAndPlay(cleanVoiceQuery(it))
            }
            uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let {
                return VoiceOrLinkTarget.SearchAndPlay(cleanVoiceQuery(it))
            }

            val ssp = uri.schemeSpecificPart?.trim().orEmpty()
            if (ssp.isNotEmpty() && !ssp.startsWith("//")) {
                val candidate = ssp.substringBefore('?').substringBefore('&').substringBefore('/').trim()
                if (VIDEO_ID_REGEX.matches(candidate)) {
                    return VoiceOrLinkTarget.Video(candidate)
                }
            }

            val host = uri.host?.trim().orEmpty()
            if (VIDEO_ID_REGEX.matches(host)) {
                return VoiceOrLinkTarget.Video(host)
            }
            val seg0 = uri.pathSegments.firstOrNull()?.trim().orEmpty()
            if (VIDEO_ID_REGEX.matches(seg0)) {
                return VoiceOrLinkTarget.Video(seg0)
            }
            val seg1 = uri.pathSegments.getOrNull(1)?.trim().orEmpty()
            if (VIDEO_ID_REGEX.matches(seg1)) {
                return VoiceOrLinkTarget.Video(seg1)
            }
            return null
        }

        // Handle http / https YouTube links
        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.lowercase().orEmpty()
            if (host == "youtu.be" || host.endsWith(".youtu.be")) {
                val id = uri.pathSegments.firstOrNull()?.trim().orEmpty()
                if (id.isNotBlank()) {
                    return VoiceOrLinkTarget.Video(id)
                }
            }

            if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let {
                    return VoiceOrLinkTarget.Video(it.trim())
                }
                val q = uri.getQueryParameter("search_query") ?: uri.getQueryParameter("q")
                if (!q.isNullOrBlank()) {
                    return VoiceOrLinkTarget.SearchAndPlay(cleanVoiceQuery(q))
                }

                val segments = uri.pathSegments
                if (segments.size >= 2) {
                    val prefix = segments[0].lowercase()
                    val candidateId = segments[1].trim()
                    if ((prefix == "shorts" || prefix == "live" || prefix == "embed" || prefix == "v") &&
                        candidateId.isNotBlank()
                    ) {
                        return VoiceOrLinkTarget.Video(candidateId)
                    }
                }
            }
        }

        return null
    }

    fun buildPlaceholderVideoItem(videoId: String): VideoItem {
        return VideoItem(
            videoId = videoId,
            title = "Đang tải video...",
            author = "",
            subtitle = "",
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationMs = 0L,
            badgeText = "",
            isLive = false,
            clickTrackingParams = null
        )
    }

    private fun extractVoiceQueryFromExtras(intent: Intent): String {
        intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let { return it }
        intent.getStringExtra("query")?.takeIf { it.isNotBlank() }?.let { return it }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }

        val title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)?.trim().orEmpty()
        val artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST)?.trim().orEmpty()
        val album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM)?.trim().orEmpty()
        return listOf(title, artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
