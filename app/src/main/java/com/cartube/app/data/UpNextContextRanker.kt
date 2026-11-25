package com.cartube.app.data

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * What the listener has told us so far in this playback session, without pressing any extra button.
 *
 * @param recentlyPlayed videos already played, oldest first, INCLUDING the one playing right now.
 * @param skippedAuthors normalized channel name -> how many of its videos were skipped early.
 * @param relatedHits videoId -> in how many fully-listened videos' related lists it appeared.
 */
data class SessionSignals(
    val recentlyPlayed: List<VideoItem> = emptyList(),
    val skippedAuthors: Map<String, Int> = emptyMap(),
    val relatedHits: Map<String, Int> = emptyMap()
)

/**
 * Context-aware ranker for CarTube's Up Next queue:
 * 1. Pushes Livestreams strictly to the bottom (Tier 3) whenever the seed video is NOT live,
 *    preventing Autoplay from getting stuck forever on a never-ending live stream.
 * 2. Prioritizes candidates whose duration closely matches the currently playing video
 *    (e.g. 3–6 min songs stay with 2–10 min songs; multi-hour streams are demoted to Tier 2).
 * 3. Preserves Music context (boosts songs/MVs and demotes unrelated News, Kids songs, Movie recaps,
 *    or Sports highlights injected by YouTube's generic personal shelves).
 * 4. Preserves YouTube's natural relevance order among equally well-matched candidates.
 * 5. Follows the listener: demotes channels they skipped early and boosts candidates that keep
 *    showing up next to videos they listened to in full (see [SessionSignals]).
 * 6. Avoids repeats: other uploads of a song already heard (MV / lyric / cover / remix) sink to the
 *    bottom, and no more than [MAX_SAME_ARTIST_RUN] videos of one channel play back to back.
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

    private val BRACKETED_REGEX = Regex("""[(\[{【（][^)\]}】）]*[)\]}】）]""")
    private val COMBINING_MARKS_REGEX = Regex("""\p{Mn}+""")
    private val NON_WORD_REGEX = Regex("""[^\p{L}\p{N}]+""")

    // Words (already lowercased, diacritics stripped) that describe the upload, not the song.
    private val TITLE_NOISE_WORDS = setOf(
        "official", "music", "video", "mv", "audio", "lyric", "lyrics", "karaoke", "cover", "remix",
        "live", "version", "ver", "hd", "4k", "2k", "full", "ft", "feat", "prod", "by", "x",
        "vietsub", "engsub", "sub", "visualizer", "performance", "acoustic", "lofi", "topic", "vevo",
        "channel", "tv", "entertainment", "records", "beat"
    )

    private const val MAX_SAME_ARTIST_RUN = 2
    private const val RECENT_SONG_MEMORY = 15
    private const val MAX_SONG_DURATION_MS = 900_000L

    private data class ScoredCandidate(
        val item: VideoItem,
        val tier: Int,
        val score: Double,
        val youtubeRank: Int
    )

    fun rank(
        seed: VideoItem?,
        candidates: List<VideoItem>,
        session: SessionSignals = SessionSignals()
    ): List<VideoItem> {
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
                normalizedSeedAuthor = normalizedSeedAuthor,
                authorSkipCount = session.skippedAuthors[normalizeAuthor(cand.author)] ?: 0,
                relatedHitCount = session.relatedHits[cand.videoId] ?: 0
            )
        }

        val sorted = scored.sortedWith(
            compareBy<ScoredCandidate> { it.tier }
                .thenByDescending { it.score }
                .thenBy { it.youtubeRank }
        )
        return diversify(seed, sorted, session.recentlyPlayed)
    }

    fun normalizeAuthor(author: String): String = author.trim().lowercase()

    /**
     * Post-pass over the score-sorted list:
     * - a candidate that is the same song as something already heard (or as a better-ranked
     *   candidate) is moved to the very end instead of being played again in another version;
     * - a channel may not play more than [MAX_SAME_ARTIST_RUN] times in a row, as long as the same
     *   tier still has a candidate from another channel to put in between.
     */
    private fun diversify(
        seed: VideoItem,
        sorted: List<ScoredCandidate>,
        recentlyPlayed: List<VideoItem>
    ): List<VideoItem> {
        val played = if (recentlyPlayed.any { it.videoId == seed.videoId }) {
            recentlyPlayed
        } else {
            recentlyPlayed + seed
        }

        val heardKeys = played.takeLast(RECENT_SONG_MEMORY).mapTo(ArrayList()) { SongKey.of(it) }
        val fresh = ArrayList<ScoredCandidate>()
        val repeats = ArrayList<VideoItem>()
        for (cand in sorted) {
            val key = SongKey.of(cand.item)
            if (heardKeys.any { it.isSameSongAs(key) }) {
                repeats.add(cand.item)
            } else {
                heardKeys.add(key)
                fresh.add(cand)
            }
        }

        var runAuthor = ""
        var runLength = 0
        fun extendRun(author: String) {
            if (author.isNotEmpty() && author == runAuthor) {
                runLength++
            } else {
                runAuthor = author
                runLength = 1
            }
        }
        for (video in played) extendRun(normalizeAuthor(video.author))

        val result = ArrayList<VideoItem>(sorted.size)
        while (fresh.isNotEmpty()) {
            val head = fresh[0]
            var pick = 0
            if (runLength >= MAX_SAME_ARTIST_RUN && runAuthor.isNotEmpty() &&
                normalizeAuthor(head.item.author) == runAuthor
            ) {
                val other = fresh.indexOfFirst {
                    it.tier == head.tier && normalizeAuthor(it.item.author) != runAuthor
                }
                if (other > 0) pick = other
            }
            val chosen = fresh.removeAt(pick)
            result.add(chosen.item)
            extendRun(normalizeAuthor(chosen.item.author))
        }
        result.addAll(repeats)
        return result
    }

    /** Title reduced to the words that identify the song, ignoring upload/version decoration. */
    private class SongKey(
        val titleTokens: Set<String>,
        val authorTokens: Set<String>,
        val durationMs: Long
    ) {
        fun isSameSongAs(other: SongKey): Boolean {
            // Only single tracks are compared; long mixes with look-alike titles are different content.
            if (durationMs > MAX_SONG_DURATION_MS || other.durationMs > MAX_SONG_DURATION_MS) return false
            val uploaders = authorTokens + other.authorTokens
            val mine = titleTokens - uploaders
            val theirs = other.titleTokens - uploaders
            if (mine.isEmpty() || theirs.isEmpty()) return false
            // "Tập 12" vs "Tập 13", "... 2025" vs "... 2026" are different videos.
            if (mine.filterTo(HashSet()) { it.isNumber() } != theirs.filterTo(HashSet()) { it.isNumber() }) {
                return false
            }
            if (mine == theirs) {
                return mine.size >= 2 || mine.first().let { it.length >= 3 && !it.isNumber() }
            }
            val shared = mine.count { it in theirs }
            val smaller = minOf(mine.size, theirs.size)
            val larger = maxOf(mine.size, theirs.size)
            if (smaller < 2) return false
            if (shared == smaller && smaller * 2 >= larger) return true
            return shared.toDouble() / (mine.size + theirs.size - shared) >= 0.75
        }

        companion object {
            fun of(item: VideoItem): SongKey {
                val withoutBrackets = tokenize(BRACKETED_REGEX.replace(item.title, " "))
                val titleTokens = if (withoutBrackets.size >= 2) withoutBrackets else tokenize(item.title)
                return SongKey(titleTokens, tokenize(item.author), item.durationMs)
            }

            private fun String.isNumber(): Boolean = all { it.isDigit() }

            private fun tokenize(text: String): Set<String> {
                val plain = COMBINING_MARKS_REGEX
                    .replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
                    .replace('đ', 'd')
                return plain.split(NON_WORD_REGEX).filterTo(LinkedHashSet()) {
                    it.isNotEmpty() && it !in TITLE_NOISE_WORDS
                }
            }
        }
    }

    private fun scoreCandidate(
        cand: VideoItem,
        youtubeRank: Int,
        seedIsLive: Boolean,
        seedIsMusic: Boolean,
        seedIsKids: Boolean,
        seedDurationMs: Long,
        normalizedSeedAuthor: String,
        authorSkipCount: Int,
        relatedHitCount: Int
    ): ScoredCandidate {
        val candIsLive = isLiveItem(cand)
        val candText = "${cand.title} ${cand.author} ${cand.subtitle}"

        // Base relevance from YouTube's own WatchNext ordering. The rows of that grid are not
        // ordered by relevance (measured on the car: row 0 is just three unrelated videos), so the
        // row index earns nothing; YouTube's explicit autoplay choice does.
        var score = (40.0 - youtubeRank * 1.15).coerceAtLeast(4.0)
        if (cand.isAutoplayPick) {
            score += 20.0
        }

        // Listener signals: a channel skipped early sinks, and a candidate that several
        // fully-listened videos all point to rises (the first hit is just "it is related").
        score -= authorSkipCount.coerceAtMost(3) * 18.0
        score += (relatedHitCount - 1).coerceIn(0, 3) * 9.0

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
        // The autoplay pick often arrives without a duration; it is not a disguised live stream.
        val candDurationMs = when {
            cand.durationMs > 0L -> cand.durationMs
            cand.isAutoplayPick -> seedDurationMs.takeIf { it > 0L } ?: 270_000L
            else -> 0L
        }
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
