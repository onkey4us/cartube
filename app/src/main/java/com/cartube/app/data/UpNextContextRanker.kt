package com.cartube.app.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Context-aware ranker for CarTube's Up Next queue:
 * 1. Pushes Livestreams strictly to the bottom (Tier 3) whenever the seed video is NOT live,
 *    preventing Autoplay from getting stuck forever on a never-ending live stream.
 * 2. Prioritizes candidates whose duration closely matches the currently playing video
 *    (e.g. 3–6 min songs stay with 2–10 min songs; multi-hour streams are demoted to Tier 2).
 * 3. Preserves Music context (boosts songs/MVs and demotes unrelated News, Kids songs, Movie recaps,
 *    or Sports highlights injected by YouTube's generic personal shelves).
 * 4. Preserves YouTube's natural relevance order among equally well-matched candidates.
 */
object UpNextContextRanker {

    private val LIVE_BADGE_REGEX = Regex(
        """(?iu)\b(live|trực\s+tiếp|đang\s+phát|premiere|công\s+chiếu)\b"""
    )

    private val MUSIC_POSITIVE_REGEX = Regex(
        """(?iu)(\b(official\s+music\s+video|official\s+mv|official\s+video|official\s+audio|official\s+lyric|music\s+video|lyric\s+video|lyrics|lyric|karaoke|acoustic|unplugged|live\s+session|live\s+band|phòng\s+trà|remix|nonstop|vinahouse|edm|lofi|chill|mashup|cover|ost|nhạc\s+phim|single|album|playlist|tuyển\s+tập|liên\s+khúc|nhạc\s+trẻ|nhạc\s+vàng|nhạc\s+xưa|nhạc\s+trữ\s+tình|nhạc\s+hoa|nhạc\s+ngoại|nhạc\s+không\s+lời|hòa\s+tấu|bolero|v-pop|vpop|us-uk|k-pop|ballad|rap\s+việt|ca\s+khúc|bài\s+hát|mv|ft\.|feat\.|prod\.|vevo)\b|-\s*topic$)"""
    )

    private val KIDS_REGEX = Regex(
        """(?iu)\b(thiếu\s+nhi|nhạc\s+cho\s+bé|bé\s+yêu|bé\s+ngủ\s+ngon|cho\s+bé\s+ăn|dạy\s+bé|cho\s+trẻ\s+em|mầm\s+non|mẫu\s+giáo|hoạt\s+hình|siêu\s+nhân|baby\s+shark|cocomelon|peppa\s+pig|doraemon|con\s+cò\s+bé\s+bé|cháu\s+lên\s+ba|rửa\s+mặt\s+như\s+mèo|một\s+con\s+vịt|ba\s+thương\s+con|bống\s+bống\s+bang\s+bang|kids\s+song|nursery\s+rhymes)\b"""
    )

    private val NON_MUSIC_NEWS_REGEX = Regex(
        """(?iu)\b(thời\s+sự|bản\s+tin|tin\s+tức|tin\s+nóng|tin\s+nhanh|tin\s+24h|tin\s+buổi\s+sáng|tin\s+tối|điểm\s+tin|vtv1|vtv24|vtv3|antv|htv9|vtc14|dự\s+báo\s+thời\s+tiết|thời\s+tiết\s+hôm\s+nay|bão\s+số|áp\s+thấp|quốc\s+hội|chính\s+phủ|bắt\s+giữ|khởi\s+tố|xét\s+xử|tòa\s+án|tai\s+nạn\s+giao\s+thông|breaking\s+news)\b"""
    )

    private val NON_MUSIC_OTHER_REGEX = Regex(
        """(?iu)\b(tóm\s+tắt\s+phim|review\s+phim|thuyết\s+minh\s+phim|tập\s+\d+\s+full|trực\s+tiếp\s+bóng\s+đá|highlight\s+bóng\s+đá|bình\s+luận\s+bóng\s+đá|ngoại\s+hạng\s+anh|champions\s+league|v-league|liên\s+quân\s+mobile|lmht|pubg|minecraft|gta\s*5|hài\s+độc\s+thoại)\b"""
    )

    private val SECONDARY_TALK_REGEX = Regex(
        """(?iu)\b(phỏng\s+vấn|hậu\s+trường|reaction|react|talkshow|talk\s+show|podcast|tâm\s+sự|vlog)\b"""
    )

    private data class ScoredCandidate(
        val item: VideoItem,
        val tier: Int,
        val score: Double,
        val youtubeRank: Int
    )

    fun rank(seed: VideoItem?, candidates: List<VideoItem>): List<VideoItem> {
        if (candidates.size <= 1 || seed == null) return candidates

        val seedIsLive = isLiveItem(seed)
        val seedText = "${seed.title} ${seed.author}"
        val seedIsKids = KIDS_REGEX.containsMatchIn(seedText)
        val seedIsNonMusic = NON_MUSIC_NEWS_REGEX.containsMatchIn(seedText) ||
            NON_MUSIC_OTHER_REGEX.containsMatchIn(seedText)

        // Detect music context from seed metadata OR strong consensus among top YouTube suggestions
        val topSample = candidates.take(10)
        val musicNeighborCount = topSample.count { cand ->
            cand.isMusicType || MUSIC_POSITIVE_REGEX.containsMatchIn("${cand.title} ${cand.author}")
        }
        val seedHasMusicSignal = seed.isMusicType || MUSIC_POSITIVE_REGEX.containsMatchIn(seedText)
        val seedLooksLikeSongLength = !seedIsLive && seed.durationMs in 105_000L..480_000L
        val seedIsMusic = !seedIsNonMusic && (
            seedHasMusicSignal ||
                (seedLooksLikeSongLength && musicNeighborCount >= 3) ||
                (seed.durationMs == 0L && musicNeighborCount >= 5)
            )

        val seedDurationMs = seed.durationMs
        val normalizedSeedAuthor = seed.author.trim().lowercase()

        val scored = candidates.mapIndexed { index, cand ->
            scoreCandidate(
                cand = cand,
                youtubeRank = index,
                seedIsLive = seedIsLive,
                seedIsMusic = seedIsMusic,
                seedIsKids = seedIsKids,
                seedDurationMs = seedDurationMs,
                normalizedSeedAuthor = normalizedSeedAuthor
            )
        }

        return scored.sortedWith(
            compareBy<ScoredCandidate> { it.tier }
                .thenByDescending { it.score }
                .thenBy { it.youtubeRank }
        ).map { it.item }
    }

    private fun scoreCandidate(
        cand: VideoItem,
        youtubeRank: Int,
        seedIsLive: Boolean,
        seedIsMusic: Boolean,
        seedIsKids: Boolean,
        seedDurationMs: Long,
        normalizedSeedAuthor: String
    ): ScoredCandidate {
        val candIsLive = isLiveItem(cand)
        val candText = "${cand.title} ${cand.author} ${cand.subtitle}"

        // Base relevance from YouTube's own WatchNext ordering + primary shelf bonus
        var score = (40.0 - youtubeRank * 1.15).coerceAtLeast(4.0)
        if (cand.shelfIndex == 0) {
            score += 12.0
        }

        // Rule 1: If seed is NOT live, push all Livestreams strictly to Tier 3 (very bottom of queue)
        if (!seedIsLive && candIsLive) {
            return ScoredCandidate(
                item = cand,
                tier = 3,
                score = score - 100.0,
                youtubeRank = youtubeRank
            )
        }

        // If seed IS live, keep live streams in Tier 0
        if (seedIsLive) {
            if (candIsLive) score += 15.0
            return ScoredCandidate(
                item = cand,
                tier = 0,
                score = score,
                youtubeRank = youtubeRank
            )
        }

        // Disguised live / radio streams with 0:00 unknown duration when playing VOD
        val candDurationMs = cand.durationMs
        if (candDurationMs <= 0L) {
            return ScoredCandidate(
                item = cand,
                tier = 2,
                score = score - 40.0,
                youtubeRank = youtubeRank
            )
        }

        // Rule 3: Context filtering when playing Music
        var contextMismatch = false
        if (seedIsMusic) {
            val isNewsOrOther = NON_MUSIC_NEWS_REGEX.containsMatchIn(candText) ||
                NON_MUSIC_OTHER_REGEX.containsMatchIn(candText)
            val isUnwantedKids = !seedIsKids && KIDS_REGEX.containsMatchIn(candText)

            if (isNewsOrOther || isUnwantedKids) {
                contextMismatch = true
                score -= 55.0
            } else {
                if (cand.isMusicType || MUSIC_POSITIVE_REGEX.containsMatchIn(candText)) {
                    score += 14.0
                }
                if (normalizedSeedAuthor.isNotEmpty() &&
                    cand.author.trim().lowercase() == normalizedSeedAuthor
                ) {
                    score += 6.0
                }
                // Demote interviews / reactions / talkshows when listening to individual songs
                if (seedDurationMs in 1L..660_000L && SECONDARY_TALK_REGEX.containsMatchIn(candText)) {
                    score -= 18.0
                }
            }
        }

        // Rule 2: Duration tiering & closeness bonus relative to seed video
        val effectiveSeedMs = when {
            seedDurationMs > 0L -> seedDurationMs
            seedIsMusic -> 270_000L // Default ~4m30s song when seed duration wasn't resolved yet
            else -> 420_000L
        }

        var durationTier = 0
        when {
            // Seed is a short / single track (<= 12 minutes, e.g. 3–6 min MV)
            effectiveSeedMs <= 720_000L -> {
                when {
                    candDurationMs < 75_000L -> {
                        // Too short (< 1m15s teaser / intro)
                        durationTier = 2
                        score -= 22.0
                    }
                    candDurationMs <= 660_000L -> {
                        // Sweet spot: 1m15s – 11m single song / MV
                        durationTier = 0
                        score += closenessBonus(candDurationMs, effectiveSeedMs, sharpness = 1.30)
                    }
                    candDurationMs <= 1_800_000L -> {
                        // Medium 11m – 30m mini-playlist: acceptable secondary tier
                        durationTier = 1
                        score += closenessBonus(candDurationMs, effectiveSeedMs, sharpness = 1.15)
                    }
                    else -> {
                        // Multi-hour / > 30m compilation when playing a single song -> push near bottom
                        durationTier = 2
                        val hours = candDurationMs / 3_600_000.0
                        score -= (28.0 + hours * 6.0)
                    }
                }
            }

            // Seed is medium length (12m – 25m)
            effectiveSeedMs <= 1_500_000L -> {
                when {
                    candDurationMs < 120_000L || candDurationMs > 5_400_000L -> {
                        durationTier = 2
                        score -= 24.0
                    }
                    candDurationMs in 300_000L..2_700_000L -> {
                        durationTier = 0
                        score += closenessBonus(candDurationMs, effectiveSeedMs, sharpness = 1.05)
                    }
                    else -> {
                        durationTier = 1
                        score += closenessBonus(candDurationMs, effectiveSeedMs, sharpness = 1.05)
                    }
                }
            }

            // Seed is a long Mix / Nonstop / Podcast (> 25m)
            else -> {
                when {
                    candDurationMs < 180_000L || candDurationMs > 14_400_000L -> {
                        durationTier = 2
                        score -= 24.0
                    }
                    candDurationMs in 720_000L..9_000_000L -> {
                        durationTier = 0
                        score += closenessBonus(candDurationMs, effectiveSeedMs, sharpness = 0.85)
                    }
                    else -> {
                        durationTier = 1
                        score += closenessBonus(candDurationMs, effectiveSeedMs, sharpness = 0.85)
                    }
                }
            }
        }

        val finalTier = if (contextMismatch) 2 else durationTier
        return ScoredCandidate(
            item = cand,
            tier = finalTier,
            score = score,
            youtubeRank = youtubeRank
        )
    }

    private fun closenessBonus(candMs: Long, seedMs: Long, sharpness: Double): Double {
        if (candMs <= 0L || seedMs <= 0L) return 0.0
        val logDiff = abs(ln(candMs.toDouble() / seedMs.toDouble()))
        return 26.0 * exp(-sharpness * logDiff)
    }

    fun isLiveItem(item: VideoItem): Boolean {
        if (item.isLive) return true
        if (item.badgeText.isNotBlank() && LIVE_BADGE_REGEX.containsMatchIn(item.badgeText)) {
            return true
        }
        return false
    }
}
