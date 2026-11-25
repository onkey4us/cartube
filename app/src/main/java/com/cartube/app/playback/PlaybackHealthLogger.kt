package com.cartube.app.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Logs what a stutter really was: a network stall (rebuffer) or a decoder that cannot keep up
 * (dropped frames), plus which codec/decoder is in use.
 *
 * Read on the car with: adb logcat -s CarTubeHealth
 */
@UnstableApi
class PlaybackHealthLogger : AnalyticsListener {

    private var videoId: String? = null
    private var hasBeenReady = false
    private var seekInFlight = false
    private var rebufferStartMs = 0L
    private var rebufferCount = 0
    private var rebufferTotalMs = 0L
    private var droppedFrames = 0
    private var lastBitrateEstimate = 0L
    private var videoFormat: String? = null
    private var videoDecoder: String? = null

    /** Call when a video starts loading; prints the summary of the one that just ended. */
    fun onNewVideo(newVideoId: String) {
        // A retry of the same video (other source, same position) keeps its counters.
        if (newVideoId == videoId) return
        logSummary()
        videoId = newVideoId
        hasBeenReady = false
        seekInFlight = false
        rebufferStartMs = 0L
        rebufferCount = 0
        rebufferTotalMs = 0L
        droppedFrames = 0
        videoFormat = null
        videoDecoder = null
    }

    fun logSummary() {
        val id = videoId ?: return
        if (!hasBeenReady) return
        // Include a stall that is still going on (Next pressed while the spinner was showing).
        val stalledMs = rebufferTotalMs +
            if (rebufferStartMs != 0L) SystemClock.elapsedRealtime() - rebufferStartMs else 0L
        Log.i(
            TAG,
            "SUMMARY video=$id rebuffers=$rebufferCount stalledMs=$stalledMs " +
                "droppedFrames=$droppedFrames format=$videoFormat decoder=$videoDecoder " +
                "bandwidthKbps=${lastBitrateEstimate / 1000}"
        )
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        when (state) {
            Player.STATE_IDLE -> {
                // A stall that ended in an error keeps counting until playback is back.
                seekInFlight = false
            }
            Player.STATE_BUFFERING -> {
                // Buffering right after a seek or at the start of a video is expected, not a stall.
                if (hasBeenReady && !seekInFlight && rebufferStartMs == 0L) {
                    rebufferStartMs = SystemClock.elapsedRealtime()
                    rebufferCount++
                    Log.w(
                        TAG,
                        "REBUFFER #$rebufferCount video=$videoId at=${eventTime.currentPlaybackPositionMs}ms " +
                            "bufferedMs=${eventTime.totalBufferedDurationMs} " +
                            "bandwidthKbps=${lastBitrateEstimate / 1000} format=$videoFormat"
                    )
                }
            }
            Player.STATE_READY -> {
                if (rebufferStartMs != 0L) {
                    val stalledMs = SystemClock.elapsedRealtime() - rebufferStartMs
                    rebufferTotalMs += stalledMs
                    rebufferStartMs = 0L
                    Log.w(TAG, "REBUFFER ended video=$videoId stalledMs=$stalledMs")
                }
                hasBeenReady = true
                seekInFlight = false
            }
            Player.STATE_ENDED -> {
                rebufferStartMs = 0L
                seekInFlight = false
            }
        }
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        ) {
            seekInFlight = true
        }
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long
    ) {
        this.droppedFrames += droppedFrames
        Log.w(
            TAG,
            "DROPPED $droppedFrames frames in ${elapsedMs}ms video=$videoId " +
                "format=$videoFormat decoder=$videoDecoder"
        )
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        val fps = if (format.frameRate > 0f) "@${format.frameRate.toInt()}" else ""
        val described = "${format.codecs ?: format.sampleMimeType} ${format.width}x${format.height}$fps " +
            "${format.bitrate / 1000}kbps"
        if (described != videoFormat) {
            videoFormat = described
            Log.i(TAG, "FORMAT video=$videoId $described")
        }
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        videoDecoder = decoderName
        // OMX.google.* / c2.android.* are software decoders: expect stutter on this head unit.
        Log.i(TAG, "DECODER video=$videoId $decoderName")
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long
    ) {
        lastBitrateEstimate = bitrateEstimate
    }

    private companion object {
        const val TAG = "CarTubeHealth"
    }
}
