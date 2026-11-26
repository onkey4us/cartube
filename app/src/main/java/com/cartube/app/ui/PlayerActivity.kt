package com.cartube.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cartube.app.R
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.VideoItem
import com.cartube.app.data.VoiceAndLinkHelper
import com.cartube.app.databinding.ActivityPlayerBinding
import com.cartube.app.playback.CarPlaybackService
import com.cartube.app.playback.PlaybackStateStore
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: CarPrefs
    private lateinit var upNextAdapter: UpNextAdapter

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var isSeeking: Boolean = false
    private var isControlsVisible: Boolean = true

    /**
     * false = Split Watch Mode (Mode B for Large/Wide, Mode C for Narrow).
     * true  = Frame-Full Mode (expands Video to fill 100% of current window).
     */
    private var isFrameFull: Boolean = false
    private var isZoomCrop: Boolean = false
    private var usesOverlayControls: Boolean = true
    private var pendingVideo: VideoItem? = null
    private var pendingResumePositionMs: Long = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateSeekProgress()
            uiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = CarPrefs(this)
        extractIntentExtras(intent)
        setupControls()
        setupUpNextList()
        setupTouchGestures()
        applyResponsiveLayout()
        observePlaybackState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIntentExtras(intent)
        applyResponsiveLayout()
        triggerPendingPlaybackIfReady()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            isFrameFull = true
            applyResponsiveLayout()
            setControlsVisible(false)
        } else {
            applyResponsiveLayout()
        }
    }

    override fun onStart() {
        super.onStart()
        applyResponsiveLayout()
        if (!DisplayModeHelper.isEmbeddedOrPip(this)) {
            hideSystemBars()
        }
        connectToService()
        uiHandler.post(progressRunnable)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(progressRunnable)
        uiHandler.removeCallbacks(hideControlsRunnable)

        binding.playerViewSurface.player = null
        binding.playerViewTexture.player = null

        val service = CarPlaybackService.instance
        if (service != null && prefs.backgroundAudioOnly && service.getPlayer().isPlaying) {
            service.setVideoEnabled(false)
        }

        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isFrameFull) {
            setFrameFullMode(false)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun attachPlayerToActiveView() {
        val player = CarPlaybackService.instance?.getPlayer()
        val useTexture = DisplayModeHelper.needsTextureView(this)
        val targetResizeMode = if (isFrameFull && isZoomCrop) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        binding.playerViewSurface.setResizeMode(targetResizeMode)
        binding.playerViewTexture.setResizeMode(targetResizeMode)

        if (useTexture) {
            binding.playerViewSurface.player = null
            binding.playerViewSurface.visibility = View.GONE
            binding.playerViewTexture.visibility = View.VISIBLE
            if (player != null && binding.playerViewTexture.player !== player) {
                binding.playerViewTexture.player = player
            }
        } else {
            binding.playerViewTexture.player = null
            binding.playerViewTexture.visibility = View.GONE
            binding.playerViewSurface.visibility = View.VISIBLE
            if (player != null && binding.playerViewSurface.player !== player) {
                binding.playerViewSurface.player = player
            }
        }
    }

    private fun setFrameFullMode(full: Boolean) {
        if (isFrameFull == full) return
        isFrameFull = full
        applyResponsiveLayout()
        if (usesOverlayControls) {
            setControlsVisible(true)
        }
    }

    private fun applyResponsiveLayout() {
        val embedded = DisplayModeHelper.isEmbeddedOrPip(this)
        val mode = DisplayModeHelper.getLayoutMode(this)
        val widthDp = resources.configuration.screenWidthDp.coerceAtLeast(320)
        val heightDp = resources.configuration.screenHeightDp.coerceAtLeast(320)

        attachPlayerToActiveView()

        if (isFrameFull) {
            // ==================== FRAME-FULL MODE (100% Window) ====================
            usesOverlayControls = true
            binding.playerRoot.orientation = LinearLayout.HORIZONTAL

            binding.narrowTopBar.visibility = View.GONE
            binding.largeBelowVideoPanel.visibility = View.GONE
            binding.midVideoSeekRow.visibility = View.GONE
            binding.midBelowVideoPanel.visibility = View.GONE
            binding.narrowBelowVideoPanel.visibility = View.GONE
            binding.narrowVideoProgress.visibility = View.GONE
            binding.upNextDivider.visibility = View.GONE
            binding.upNextPanel.visibility = View.GONE
            binding.btnMidVoice.visibility = View.GONE
            binding.tvUpNextHeader.setTextColor(ContextCompat.getColor(this, R.color.car_accent))

            binding.leftMainPane.setPadding(0, 0, 0, 0)
            binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0f
            )

            binding.videoStageFrame.setBackgroundColor(0xFF000000.toInt())
            binding.videoStageFrame.clipToOutline = false
            binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0f
            )

            // Frame-Full overlay shows top info/pills + Prev/Next since below-video panels are hidden
            binding.overlayFrameFullTopRow.visibility = View.VISIBLE
            binding.btnOverlayPrev.visibility = View.VISIBLE
            binding.btnOverlayNext.visibility = View.VISIBLE
            binding.btnOverlayWholeCar.visibility = if (embedded) View.VISIBLE else View.GONE
            binding.tvOverlayZoom.text = getString(
                if (isZoomCrop) R.string.action_crop_frame else R.string.action_fit_frame
            )

            binding.controlsOverlay.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
            if (isControlsVisible) {
                scheduleAutoHide()
            }
            return
        }

        // ==================== SPLIT WATCH MODES (B, MID2 & C) ====================
        when {
            mode == DisplayModeHelper.LayoutMode.PIP_COMPACT -> {
                // ==================== MODE C: NARROW (~360x712) ====================
                usesOverlayControls = false
                uiHandler.removeCallbacks(hideControlsRunnable)
                binding.controlsOverlay.visibility = View.GONE

                binding.playerRoot.orientation = LinearLayout.VERTICAL
                binding.leftMainPane.setPadding(0, 0, 0, 0)
                binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f
                )

                binding.narrowTopBar.visibility = View.VISIBLE
                binding.largeBelowVideoPanel.visibility = View.GONE
                binding.midVideoSeekRow.visibility = View.GONE
                binding.midBelowVideoPanel.visibility = View.GONE
                binding.narrowBelowVideoPanel.visibility = View.VISIBLE
                binding.narrowVideoProgress.visibility = View.VISIBLE

                val videoHeightPx = dpToPx(((widthDp - 24).coerceAtLeast(200) * 9) / 16)
                binding.videoStageFrame.setBackgroundResource(R.drawable.bg_rounded_thumb)
                binding.videoStageFrame.clipToOutline = true
                binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    videoHeightPx,
                    0f
                ).apply {
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(12)
                }

                binding.upNextDivider.visibility = View.VISIBLE
                binding.upNextDivider.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(1)
                ).apply {
                    topMargin = dpToPx(10)
                }

                binding.upNextPanel.visibility = View.VISIBLE
                binding.upNextPanel.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), 0)
                binding.upNextPanel.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                binding.tvUpNextHeader.text = getString(R.string.up_next_short)
                binding.tvUpNextHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                binding.tvUpNextHeader.setTextColor(ContextCompat.getColor(this, R.color.car_accent))
                binding.btnMidVoice.visibility = View.GONE
                upNextAdapter.configureStyle(
                    thumbWidthDp = 112,
                    rowHeightDp = 76,
                    highlightFirst = false,
                    titleTextSizeSp = 14f,
                    subtitleTextSizeSp = 12f
                )
            }

            mode == DisplayModeHelper.LayoutMode.PIP_LARGE && heightDp >= 620 -> {
                // ==================== MID2: EMBEDDED MIDDLE WINDOW (~1061x712 / 1082x712) ====================
                usesOverlayControls = false
                uiHandler.removeCallbacks(hideControlsRunnable)
                binding.controlsOverlay.visibility = View.GONE

                binding.playerRoot.orientation = LinearLayout.HORIZONTAL
                binding.narrowTopBar.visibility = View.GONE
                binding.narrowBelowVideoPanel.visibility = View.GONE
                binding.narrowVideoProgress.visibility = View.GONE
                binding.largeBelowVideoPanel.visibility = View.GONE
                binding.midVideoSeekRow.visibility = View.VISIBLE
                binding.midBelowVideoPanel.visibility = View.VISIBLE

                val videoWidthDp = 760.coerceAtMost((widthDp - 292).coerceAtLeast(480))
                val videoHeightDp = Math.round(videoWidthDp * 9f / 16f)

                binding.leftMainPane.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(12))
                binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                    dpToPx(videoWidthDp + 32),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0f
                )

                binding.videoStageFrame.setBackgroundResource(R.drawable.bg_rounded_video_16)
                binding.videoStageFrame.clipToOutline = true
                binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(videoHeightDp),
                    0f
                )

                binding.upNextDivider.visibility = View.GONE
                binding.upNextPanel.visibility = View.VISIBLE
                binding.upNextPanel.setPadding(0, dpToPx(16), dpToPx(12), 0)
                binding.upNextPanel.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )
                binding.tvUpNextHeader.text = getString(R.string.up_next_short)
                binding.tvUpNextHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                binding.tvUpNextHeader.setTextColor(ContextCompat.getColor(this, R.color.car_text_primary))
                binding.btnMidVoice.visibility = View.VISIBLE
                upNextAdapter.configureStyle(
                    thumbWidthDp = 120,
                    rowHeightDp = 78,
                    highlightFirst = false,
                    titleTextSizeSp = 13.5f,
                    subtitleTextSizeSp = 12f,
                    titleMaxLines = 4,
                    showSubtitle = false,
                    horizontalPaddingDp = 0,
                    thumbGapDp = 10,
                    gravityTop = true
                )
            }

            else -> {
                // ==================== MODE B: LARGE (1080x580) & WIDE (1920x720) ====================
                usesOverlayControls = true
                binding.playerRoot.orientation = LinearLayout.HORIZONTAL

                binding.narrowTopBar.visibility = View.GONE
                binding.narrowBelowVideoPanel.visibility = View.GONE
                binding.narrowVideoProgress.visibility = View.GONE
                binding.midVideoSeekRow.visibility = View.GONE
                binding.midBelowVideoPanel.visibility = View.GONE
                binding.largeBelowVideoPanel.visibility = View.VISIBLE

                // In Mode B, overlay has ONLY Back + Rewind 10s / Play-Pause / Forward 10s + SeekBar (ZERO duplicates)
                binding.overlayFrameFullTopRow.visibility = View.GONE
                binding.btnOverlayPrev.visibility = View.GONE
                binding.btnOverlayNext.visibility = View.GONE

                val leftWeight = if (mode == DisplayModeHelper.LayoutMode.FULLSCREEN) 65f else 63f
                val rightWeight = 100f - leftWeight

                val pad = dpToPx(16)
                binding.leftMainPane.setPadding(pad, pad, pad, pad)
                binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    leftWeight
                )

                val leftWidthDp = ((widthDp * leftWeight) / 100f).toInt() - 32
                val idealVideoHeightDp = (leftWidthDp * 9) / 16
                val maxVideoHeightDp = (heightDp - 266).coerceAtLeast(180)
                val finalVideoHeightPx = dpToPx(idealVideoHeightDp.coerceAtMost(maxVideoHeightDp))

                binding.videoStageFrame.setBackgroundResource(R.drawable.bg_rounded_video)
                binding.videoStageFrame.clipToOutline = true
                binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    finalVideoHeightPx,
                    0f
                )

                binding.btnLargeWholeCar.visibility = if (embedded) View.VISIBLE else View.GONE

                binding.upNextDivider.visibility = View.VISIBLE
                binding.upNextDivider.layoutParams = LinearLayout.LayoutParams(
                    dpToPx(1),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                binding.upNextPanel.visibility = View.VISIBLE
                binding.upNextPanel.setPadding(dpToPx(20), dpToPx(16), dpToPx(16), 0)
                binding.upNextPanel.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rightWeight
                )
                binding.tvUpNextHeader.text = getString(R.string.up_next_autoplay)
                binding.tvUpNextHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                binding.tvUpNextHeader.setTextColor(ContextCompat.getColor(this, R.color.car_accent))
                binding.btnMidVoice.visibility = View.GONE
                upNextAdapter.configureStyle(
                    thumbWidthDp = 128,
                    rowHeightDp = 84,
                    highlightFirst = true,
                    titleTextSizeSp = 15f,
                    subtitleTextSizeSp = 13f
                )

                binding.controlsOverlay.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
                if (isControlsVisible) {
                    scheduleAutoHide()
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    @Suppress("DEPRECATION")
    private fun extractIntentExtras(intent: Intent?) {
        val video = intent?.getSerializableExtra(EXTRA_VIDEO) as? VideoItem
        if (video != null) {
            pendingVideo = video
            pendingResumePositionMs = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, 0L).coerceAtLeast(0L)
        }
    }

    private fun connectToService() {
        val token = SessionToken(this, ComponentName(this, CarPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener(
            {
                val service = CarPlaybackService.instance ?: return@addListener
                attachPlayerToActiveView()
                service.setVideoEnabled(!service.isUserForcedAudioOnly())
                triggerPendingPlaybackIfReady()
                updateQualityChip()
                if (usesOverlayControls) {
                    scheduleAutoHide()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun triggerPendingPlaybackIfReady() {
        val video = pendingVideo ?: return
        val service = CarPlaybackService.instance ?: return
        val resumeMs = pendingResumePositionMs
        pendingVideo = null
        pendingResumePositionMs = 0L
        service.playVideo(video, startPositionMs = resumeMs)
    }

    private fun popOutToMainCarDisplay() {
        val fullIntent = Intent(this, PlayerActivity::class.java)
        DisplayModeHelper.launchOnMainDisplay(this, fullIntent)
    }

    private fun openMainTab(tab: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_INITIAL_TAB, tab)
        }
        startActivity(intent)
        finish()
    }

    private fun togglePlayPause() {
        val player = CarPlaybackService.instance?.getPlayer() ?: return
        if (player.isPlaying) player.pause() else player.play()
        if (usesOverlayControls) scheduleAutoHide()
    }

    private fun cycleQuality() {
        val nextHeight = when (prefs.maxVideoHeight) {
            0 -> 480
            480 -> 720
            720 -> 1080
            else -> 0
        }
        prefs.maxVideoHeight = nextHeight
        CarPlaybackService.instance?.updateMaxVideoHeight(nextHeight)
        updateQualityChip()
        val label = if (nextHeight <= 0) "Tự động" else "${nextHeight}p"
        Toast.makeText(this, "Chất lượng video: $label", Toast.LENGTH_SHORT).show()
        if (usesOverlayControls) scheduleAutoHide()
    }

    private fun toggleAudioOnly() {
        val service = CarPlaybackService.instance
        if (service != null) {
            service.setUserForcedAudioOnly(!service.isUserForcedAudioOnly())
        }
        updateAudioOnlyUi()
        if (usesOverlayControls) scheduleAutoHide()
    }

    @Suppress("DEPRECATION")
    private fun launchVoiceSearchInPlayer() {
        try {
            startActivityForResult(VoiceAndLinkHelper.buildSpeechRecognizerIntent(), REQ_PLAYER_VOICE)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Không tìm thấy dịch vụ nhận diện giọng nói Google trên xe",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PLAYER_VOICE && resultCode == Activity.RESULT_OK) {
            val raw = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (raw.isNotEmpty()) {
                val cleaned = VoiceAndLinkHelper.cleanVoiceQuery(raw)
                showQuickSeekHint("Đang mở: $cleaned…")
                // Search runs in the service scope so it survives activity close/recreate
                CarPlaybackService.instance?.playVoiceSearch(raw)
            }
        }
    }

    private fun setupControls() {
        // Overlay controls (Mode B & Frame-Full)
        binding.btnOverlayBack.setOnClickListener {
            if (isFrameFull) setFrameFullMode(false) else finish()
        }
        binding.btnOverlayRewind.setOnClickListener {
            CarPlaybackService.instance?.seekBy(-10_000L)
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnOverlayPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnOverlayForward.setOnClickListener {
            CarPlaybackService.instance?.seekBy(10_000L)
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnOverlayPrev.setOnClickListener {
            CarPlaybackService.instance?.playPrevious()
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnOverlayNext.setOnClickListener {
            CarPlaybackService.instance?.playNext()
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnOverlayAudioOnly.setOnClickListener { toggleAudioOnly() }
        binding.btnOverlayQuality.setOnClickListener { cycleQuality() }
        binding.btnOverlayZoom.setOnClickListener {
            isZoomCrop = !isZoomCrop
            attachPlayerToActiveView()
            binding.tvOverlayZoom.text = getString(
                if (isZoomCrop) R.string.action_crop_frame else R.string.action_fit_frame
            )
            showQuickSeekHint(if (isZoomCrop) "Chế độ: Tràn khung" else "Chế độ: Vừa khung")
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnOverlayWholeCar.setOnClickListener { popOutToMainCarDisplay() }
        binding.btnOverlayExitFull.setOnClickListener { setFrameFullMode(false) }

        // Mode B (Large Split) below-video controls
        binding.btnLargeAudioOnly.setOnClickListener { toggleAudioOnly() }
        binding.btnLargeQuality.setOnClickListener { cycleQuality() }
        binding.btnLargeFullscreen.setOnClickListener { setFrameFullMode(true) }
        binding.btnLargeWholeCar.setOnClickListener { popOutToMainCarDisplay() }
        binding.btnLargePrev.setOnClickListener { CarPlaybackService.instance?.playPrevious() }
        binding.btnLargeVoice.setOnClickListener { launchVoiceSearchInPlayer() }
        binding.btnLargeNext.setOnClickListener { CarPlaybackService.instance?.playNext() }

        // MID2 (Embedded Middle Window) below-video controls
        binding.btnMidAudioOnly.setOnClickListener { toggleAudioOnly() }
        binding.btnMidQuality.setOnClickListener { cycleQuality() }
        binding.btnMidFullscreen.setOnClickListener { setFrameFullMode(true) }
        binding.btnMidPrev.setOnClickListener { CarPlaybackService.instance?.playPrevious() }
        binding.btnMidPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnMidNext.setOnClickListener { CarPlaybackService.instance?.playNext() }
        binding.btnMidVoice.setOnClickListener { launchVoiceSearchInPlayer() }

        // Mode C (Narrow Split) top 5-icon bar & below-video controls
        binding.btnNarrowBack.setOnClickListener { finish() }
        binding.btnNarrowHome.setOnClickListener { openMainTab(MainActivity.TAB_HOME) }
        binding.btnNarrowSearch.setOnClickListener { openMainTab(MainActivity.TAB_SEARCH) }
        binding.btnNarrowVoice.setOnClickListener { launchVoiceSearchInPlayer() }
        binding.btnNarrowSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnNarrowPrev.setOnClickListener { CarPlaybackService.instance?.playPrevious() }
        binding.btnNarrowRewind.setOnClickListener { CarPlaybackService.instance?.seekBy(-10_000L) }
        binding.btnNarrowPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnNarrowForward.setOnClickListener { CarPlaybackService.instance?.seekBy(10_000L) }
        binding.btnNarrowNext.setOnClickListener { CarPlaybackService.instance?.playNext() }
        binding.btnNarrowAudioOnly.setOnClickListener { toggleAudioOnly() }
        binding.btnNarrowWholeCar.setOnClickListener { popOutToMainCarDisplay() }

        // SeekBars (Overlay for Mode B/Frame-Full, MID2 & Narrow for Mode C)
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val formatted = VideoAdapter.formatDuration(progress.toLong())
                    binding.tvOverlayCurrentTime.text = formatted
                    binding.tvMidCurrentTime.text = formatted
                    binding.tvNarrowCurrentTime.text = formatted
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                isSeeking = true
                uiHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                isSeeking = false
                bar?.let {
                    CarPlaybackService.instance?.getPlayer()?.seekTo(it.progress.toLong())
                }
                if (usesOverlayControls) scheduleAutoHide()
            }
        }
        binding.overlaySeekBar.setOnSeekBarChangeListener(seekListener)
        binding.midSeekBar.setOnSeekBarChangeListener(seekListener)
        binding.narrowSeekBar.setOnSeekBarChangeListener(seekListener)
    }

    private fun setupUpNextList() {
        upNextAdapter = UpNextAdapter { item, _ ->
            CarPlaybackService.instance?.playFromUpNext(item)
            binding.rvUpNext.scrollToPosition(0)
        }
        binding.rvUpNext.layoutManager = LinearLayoutManager(this)
        binding.rvUpNext.adapter = upNextAdapter
        binding.rvUpNext.itemAnimator = null

        // Endless list: ask for more songs when the listener gets close to the end
        binding.rvUpNext.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx <= 0 && dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val total = layoutManager.itemCount
                if (total > 0 && layoutManager.findLastVisibleItemPosition() >= total - 4) {
                    CarPlaybackService.instance?.loadMoreUpNext()
                }
            }
        })
    }

    private fun setupTouchGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (usesOverlayControls) {
                    setControlsVisible(!isControlsVisible)
                } else {
                    val player = CarPlaybackService.instance?.getPlayer()
                    if (player != null) {
                        val willPlay = !player.isPlaying
                        if (willPlay) player.play() else player.pause()
                        showQuickSeekHint(if (willPlay) "Tiếp tục phát" else "Tạm dừng")
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val isLeft = e.x < binding.touchSurface.width / 2f
                val delta = if (isLeft) -10_000L else 10_000L
                CarPlaybackService.instance?.seekBy(delta)
                showQuickSeekHint(if (isLeft) "-10 giây" else "+10 giây")
                return true
            }
        })

        binding.touchSurface.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun showQuickSeekHint(text: String) {
        binding.tvStatusNotice.text = text
        binding.tvStatusNotice.visibility = View.VISIBLE
        uiHandler.postDelayed({
            if (PlaybackStateStore.state.value.statusNotice == null &&
                PlaybackStateStore.state.value.errorMessage == null
            ) {
                binding.tvStatusNotice.visibility = View.GONE
            }
        }, 1_000L)
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            PlaybackStateStore.state.collectLatest { state ->
                val video = state.currentVideo
                val title = video?.title.orEmpty()
                val channel = video?.author.orEmpty()
                val metaText = when {
                    video != null && video.author.isNotBlank() && video.subtitle.isNotBlank() &&
                        !video.subtitle.startsWith(video.author) ->
                        "${video.author} · ${video.subtitle}"
                    video != null && video.subtitle.isNotBlank() -> video.subtitle
                    else -> channel
                }

                binding.tvOverlayTitle.text = title
                binding.tvOverlayChannel.text = channel
                binding.tvLargeTitle.text = title
                binding.tvLargeMeta.text = metaText
                binding.tvMidTitle.text = title
                binding.tvMidChannel.text = channel
                binding.tvNarrowTitle.text = title
                binding.tvNarrowChannel.text = channel

                binding.progressLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                val playIcon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                binding.btnOverlayPlayPause.setImageResource(playIcon)
                binding.btnMidPlayPause.setImageResource(playIcon)
                binding.btnNarrowPlayPause.setImageResource(playIcon)

                upNextAdapter.submitList(state.upNextVideos)

                val showLoadingUpNext = state.isLoadingUpNext && state.upNextVideos.isEmpty()
                binding.upNextLoadingRow.visibility = if (showLoadingUpNext) View.VISIBLE else View.GONE
                binding.tvUpNextEmpty.visibility =
                    if (!state.isLoadingUpNext && state.upNextVideos.isEmpty()) View.VISIBLE else View.GONE

                if (!state.statusNotice.isNullOrBlank()) {
                    binding.tvStatusNotice.text = state.statusNotice
                    binding.tvStatusNotice.visibility = View.VISIBLE
                } else if (!state.errorMessage.isNullOrBlank()) {
                    binding.tvStatusNotice.text = state.errorMessage
                    binding.tvStatusNotice.visibility = View.VISIBLE
                } else {
                    binding.tvStatusNotice.visibility = View.GONE
                }

                updateQualityChip()
                updateAudioOnlyUi()
            }
        }
    }

    private fun updateQualityChip() {
        val maxH = prefs.maxVideoHeight
        val label = if (maxH <= 0) getString(R.string.action_quality_auto) else "${maxH}p"
        binding.tvLargeQuality.text = label
        binding.tvMidQuality.text = label
        binding.tvOverlayQuality.text = label
    }

    private fun updateAudioOnlyUi() {
        val isAudio = PlaybackStateStore.state.value.isAudioOnly ||
            (CarPlaybackService.instance?.isUserForcedAudioOnly() == true)
        binding.audioOnlyOverlay.visibility = if (isAudio) View.VISIBLE else View.GONE
        val label = getString(if (isAudio) R.string.action_video_on else R.string.action_audio_only)

        binding.btnLargeAudioOnly.isSelected = isAudio
        binding.tvLargeAudioOnly.text = label

        binding.btnMidAudioOnly.isSelected = isAudio
        binding.tvMidAudioOnly.text = label

        binding.btnNarrowAudioOnly.isSelected = isAudio
        binding.tvNarrowAudioOnly.text = label

        binding.btnOverlayAudioOnly.isSelected = isAudio
        binding.tvOverlayAudioOnly.text = label
    }

    private fun updateSeekProgress() {
        if (isSeeking) return
        val player = CarPlaybackService.instance?.getPlayer() ?: return
        val duration = player.duration.coerceAtLeast(0L)
        val position = player.currentPosition.coerceAtLeast(0L)
        if (duration > 0L) {
            val maxInt = duration.toInt()
            val posInt = position.toInt()
            val curText = VideoAdapter.formatDuration(position)
            val totText = VideoAdapter.formatDuration(duration)

            binding.overlaySeekBar.max = maxInt
            binding.overlaySeekBar.progress = posInt
            binding.tvOverlayCurrentTime.text = curText
            binding.tvOverlayTotalTime.text = totText

            binding.midSeekBar.max = maxInt
            binding.midSeekBar.progress = posInt
            binding.tvMidCurrentTime.text = curText
            binding.tvMidTotalTime.text = totText

            binding.narrowSeekBar.max = maxInt
            binding.narrowSeekBar.progress = posInt
            binding.tvNarrowCurrentTime.text = curText
            binding.tvNarrowTotalTime.text = totText

            val permille = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
            binding.narrowVideoProgress.progress = permille
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        isControlsVisible = visible
        if (!usesOverlayControls) {
            binding.controlsOverlay.visibility = View.GONE
            return
        }
        binding.controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        uiHandler.removeCallbacks(hideControlsRunnable)
        if (visible) {
            scheduleAutoHide()
        } else if (!DisplayModeHelper.isEmbeddedOrPip(this)) {
            hideSystemBars()
        }
    }

    private fun scheduleAutoHide() {
        if (!usesOverlayControls) return
        uiHandler.removeCallbacks(hideControlsRunnable)
        uiHandler.postDelayed(hideControlsRunnable, AUTO_HIDE_DELAY_MS)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val service = CarPlaybackService.instance
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    service?.playNext()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    service?.playPrevious()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    val p = service?.getPlayer() ?: return super.dispatchKeyEvent(event)
                    if (p.isPlaying) p.pause() else p.play()
                    if (usesOverlayControls) setControlsVisible(true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    service?.getPlayer()?.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    service?.getPlayer()?.pause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    service?.seekBy(10_000L)
                    showQuickSeekHint("+10 giây")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    service?.seekBy(-10_000L)
                    showQuickSeekHint("-10 giây")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        const val EXTRA_RESUME_POSITION_MS = "extra_resume_position_ms"
        private const val REQ_PLAYER_VOICE = 2002
        private const val AUTO_HIDE_DELAY_MS = 3_000L
    }
}
