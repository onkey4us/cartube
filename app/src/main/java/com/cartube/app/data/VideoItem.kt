package com.cartube.app.data

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import java.io.Serializable

data class VideoItem(
    val videoId: String,
    val title: String,
    val author: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val durationMs: Long,
    val badgeText: String,
    val isLive: Boolean,
    val clickTrackingParams: String?,
    val isMusicType: Boolean = false,
    val shelfIndex: Int = 0
) : Serializable {
    companion object {
        fun from(
            item: MediaItem,
            allowShorts: Boolean = false,
            allowUpcoming: Boolean = false,
            shelfIndex: Int = 0
        ): VideoItem? {
            val id = item.videoId?.takeIf { it.isNotBlank() } ?: return null
            if (!allowUpcoming && item.isUpcoming) {
                return null
            }
            val rawTitle = item.title?.takeIf { it.isNotBlank() } ?: "Video"
            if (!allowShorts) {
                if (item.isShorts) return null
                if (rawTitle.contains("#shorts", ignoreCase = true) && item.durationMs in 1..65_000L) {
                    return null
                }
            }
            val badge = item.badgeText.orEmpty().trim()
            val detectedLive = item.isLive ||
                badge.equals("LIVE", ignoreCase = true) ||
                badge.contains("TRỰC TIẾP", ignoreCase = true) ||
                badge.contains("ĐANG PHÁT", ignoreCase = true)

            val second = item.secondTitle?.toString().orEmpty()
            return VideoItem(
                videoId = id,
                title = rawTitle,
                author = item.author?.takeIf { it.isNotBlank() } ?: second.substringBefore("•").trim(),
                subtitle = second,
                thumbnailUrl = item.cardImageUrl ?: item.backgroundImageUrl ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                durationMs = item.durationMs.coerceAtLeast(0L),
                badgeText = badge,
                isLive = detectedLive,
                clickTrackingParams = item.clickTrackingParams,
                isMusicType = item.type == MediaItem.TYPE_MUSIC,
                shelfIndex = shelfIndex
            )
        }
    }
}
