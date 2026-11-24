package com.cartube.app.data

import android.content.Context

data class ResumeTrack(
    val video: VideoItem,
    val positionMs: Long,
    val durationMs: Long
)

class CarPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 0 = Tự động (adaptive up to 1080p), or explicit cap 480 / 720 / 1080.
     */
    var maxVideoHeight: Int
        get() = prefs.getInt(KEY_MAX_VIDEO_HEIGHT, 0)
        set(value) = prefs.edit().putInt(KEY_MAX_VIDEO_HEIGHT, value).apply()

    var blockAv1: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_AV1, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_AV1, value).apply()

    var backgroundAudioOnly: Boolean
        get() = prefs.getBoolean(KEY_BG_AUDIO_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_BG_AUDIO_ONLY, value).apply()

    var sponsorBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPONSOR_BLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPONSOR_BLOCK, value).apply()

    var preferredClientName: String?
        get() = prefs.getString(KEY_PREFERRED_CLIENT, null)
        set(value) = prefs.edit().putString(KEY_PREFERRED_CLIENT, value).apply()

    fun saveLastPlayed(video: VideoItem, positionMs: Long, durationMs: Long) {
        if (video.videoId.isBlank()) return
        val effectiveDuration = if (durationMs > 0L) durationMs else video.durationMs
        prefs.edit()
            .putString(KEY_LAST_VIDEO_ID, video.videoId)
            .putString(KEY_LAST_VIDEO_TITLE, video.title)
            .putString(KEY_LAST_VIDEO_AUTHOR, video.author)
            .putString(KEY_LAST_VIDEO_SUBTITLE, video.subtitle)
            .putString(KEY_LAST_VIDEO_THUMB, video.thumbnailUrl)
            .putLong(KEY_LAST_VIDEO_DURATION_MS, effectiveDuration.coerceAtLeast(0L))
            .putLong(KEY_LAST_VIDEO_POSITION_MS, positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_LAST_VIDEO_IS_LIVE, video.isLive)
            .apply()
    }

    fun getLastPlayed(): ResumeTrack? {
        val id = prefs.getString(KEY_LAST_VIDEO_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val title = prefs.getString(KEY_LAST_VIDEO_TITLE, null)?.takeIf { it.isNotBlank() } ?: return null
        val author = prefs.getString(KEY_LAST_VIDEO_AUTHOR, "").orEmpty()
        val subtitle = prefs.getString(KEY_LAST_VIDEO_SUBTITLE, "").orEmpty()
        val thumb = prefs.getString(KEY_LAST_VIDEO_THUMB, null)
            ?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val durationMs = prefs.getLong(KEY_LAST_VIDEO_DURATION_MS, 0L).coerceAtLeast(0L)
        val positionMs = prefs.getLong(KEY_LAST_VIDEO_POSITION_MS, 0L).coerceAtLeast(0L)
        val isLive = prefs.getBoolean(KEY_LAST_VIDEO_IS_LIVE, false)
        val video = VideoItem(
            videoId = id,
            title = title,
            author = author,
            subtitle = subtitle,
            thumbnailUrl = thumb,
            durationMs = durationMs,
            badgeText = "",
            isLive = isLive,
            clickTrackingParams = null
        )
        return ResumeTrack(video = video, positionMs = positionMs, durationMs = durationMs)
    }

    companion object {
        private const val PREFS_NAME = "cartube_prefs"
        private const val KEY_MAX_VIDEO_HEIGHT = "max_video_height"
        private const val KEY_BLOCK_AV1 = "block_av1"
        private const val KEY_BG_AUDIO_ONLY = "bg_audio_only"
        private const val KEY_SPONSOR_BLOCK = "sponsor_block"
        private const val KEY_PREFERRED_CLIENT = "preferred_client_name"
        private const val KEY_LAST_VIDEO_ID = "last_video_id"
        private const val KEY_LAST_VIDEO_TITLE = "last_video_title"
        private const val KEY_LAST_VIDEO_AUTHOR = "last_video_author"
        private const val KEY_LAST_VIDEO_SUBTITLE = "last_video_subtitle"
        private const val KEY_LAST_VIDEO_THUMB = "last_video_thumb"
        private const val KEY_LAST_VIDEO_DURATION_MS = "last_video_duration_ms"
        private const val KEY_LAST_VIDEO_POSITION_MS = "last_video_position_ms"
        private const val KEY_LAST_VIDEO_IS_LIVE = "last_video_is_live"
    }
}
