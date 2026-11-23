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
import com.cartube.app.R
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.VideoItem
import com.cartube.app.data.VoiceAndLinkHelper
import com.cartube.app.data.YouTubeRepository
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
    private var userForcedAudioOnly: Boolean = false
    private var isSeeking: Boolean = false
    private var isControlsVisible: Boolean = true

    /**
     * false = Split Watch Mode (shows Video + Suggested Videos alongside/below).
     * true  = Frame-Full Mode (expands Video to fill 100% of current container — stays inside PiP if in PiP!).
     */
    private var isFrameFull: Boolean = false
    private var isZoomCrop: Boolean = false
    private var usesOverlayControls: Boolean = false
    private var pendingVideo: VideoItem? = null

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

        // Detach both video views to release GPU surfaces cleanly
        binding.playerViewSurface.player = null
        binding.playerViewTexture.player = null

        // Only switch to audio-only when actually hidden/stopped (touching neighbor PiP only triggers onPause)
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
        val useTexture = DisplayModeHelper.isEmbeddedOrPip(this)
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

        // Update toggle labels & zoom button state
        val toggleLabel = if (isFrameFull) "◫ Đề xuất" else "⛶ Full"
        binding.btnToggleSplitTop.text = toggleLabel
        binding.btnToggleSplitBottom.text = toggleLabel
        binding.btnZoomMode.visibility = if (isFrameFull) View.VISIBLE else View.GONE
        binding.btnZoomMode.text = if (isZoomCrop) "Tràn khung" else "Vừa khung"

        if (isFrameFull) {
            // ==================== FRAME-FULL MODE (Inside PiP OR on Main Car Screen) ====================
            usesOverlayControls = true
            binding.playerRoot.orientation = LinearLayout.HORIZONTAL
            binding.upNextDivider.visibility = View.GONE
            binding.upNextPanel.visibility = View.GONE
            binding.watchDockPanel.visibility = View.GONE

            binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0f
            )
            binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0f
            )

            // Show "⤢ Toàn xe" ONLY when inside a PiP slot so it never confuses with in-frame Full
            binding.btnPopOutCar.visibility = if (embedded) View.VISIBLE else View.GONE

            configureOverlayButtonScale(mode)
            binding.controlsOverlay.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
            if (isControlsVisible) {
                scheduleAutoHide()
            }
            return
        }

        // ==================== SPLIT WATCH MODE (Video + Suggested Videos) ====================
        when (mode) {
            DisplayModeHelper.LayoutMode.PIP_COMPACT -> {
                // 360x712 narrow portrait PiP slot: Top 16:9 Video + Compact Dock + Bottom Suggested List
                usesOverlayControls = false
                uiHandler.removeCallbacks(hideControlsRunnable)
                binding.controlsOverlay.visibility = View.GONE

                binding.playerRoot.orientation = LinearLayout.VERTICAL
                binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f
                )

                val videoHeightPx = dpToPx((widthDp * 9) / 16)
                binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    videoHeightPx,
                    0f
                )

                binding.watchDockPanel.visibility = View.VISIBLE
                binding.watchDockPanel.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f
                )
                binding.tvDockTitle.maxLines = 1
                binding.dockMetaRow.visibility = View.GONE
                binding.btnDockRewind.visibility = View.GONE
                binding.btnDockForward.visibility = View.GONE

                binding.upNextDivider.visibility = View.VISIBLE
                binding.upNextDivider.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(1)
                )

                binding.upNextPanel.visibility = View.VISIBLE
                binding.upNextPanel.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                binding.btnSidebarVoice.visibility = View.VISIBLE
                binding.btnSidebarPopOutCar.visibility = if (embedded) View.VISIBLE else View.GONE
                upNextAdapter.setThumbnailWidthDp(112)
            }

            DisplayModeHelper.LayoutMode.PIP_LARGE -> {
                // 1061x712 / 1080x580 Large PiP slot:
                // Left (~63%): Top 16:9 Video + Bottom Persistent Watch Dock (Title, Seek, Controls, Full, Toàn xe)
                // Right (~37%): Full-height Suggested Videos List
                usesOverlayControls = false
                uiHandler.removeCallbacks(hideControlsRunnable)
                binding.controlsOverlay.visibility = View.GONE

                binding.playerRoot.orientation = LinearLayout.HORIZONTAL
                binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    63f
                )

                val leftWidthDp = (widthDp * 63) / 100
                val idealVideoHeightDp = (leftWidthDp * 9) / 16
                val maxVideoHeightDp = (heightDp - 186).coerceAtLeast(180)
                val finalVideoHeightPx = dpToPx(idealVideoHeightDp.coerceAtMost(maxVideoHeightDp))

                binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    finalVideoHeightPx,
                    0f
                )

                binding.watchDockPanel.visibility = View.VISIBLE
                binding.watchDockPanel.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                binding.tvDockTitle.maxLines = 2
                binding.dockMetaRow.visibility = View.VISIBLE
                binding.btnDockPopOutCar.visibility = if (embedded) View.VISIBLE else View.GONE
                binding.btnDockRewind.visibility = View.VISIBLE
                binding.btnDockForward.visibility = View.VISIBLE

                binding.upNextDivider.visibility = View.VISIBLE
                binding.upNextDivider.layoutParams = LinearLayout.LayoutParams(
                    dpToPx(1),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                binding.upNextPanel.visibility = View.VISIBLE
                binding.upNextPanel.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    37f
                )
                binding.btnSidebarVoice.visibility = View.GONE
                binding.btnSidebarPopOutCar.visibility = View.GONE
                upNextAdapter.setThumbnailWidthDp(126)
            }

            DisplayModeHelper.LayoutMode.FULLSCREEN -> {
                // 1920x720 Main Car Screen (8:3):
                // Left (67% = 1280x720 exact 16:9!) + Right (33% = 640x720 Suggested Videos)
                usesOverlayControls = true
                binding.watchDockPanel.visibility = View.GONE

                binding.playerRoot.orientation = LinearLayout.HORIZONTAL
                binding.leftMainPane.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    67f
                )
                binding.videoStageFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0f
                )

                binding.upNextDivider.visibility = View.VISIBLE
                binding.upNextDivider.layoutParams = LinearLayout.LayoutParams(
                    dpToPx(1),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                binding.upNextPanel.visibility = View.VISIBLE
                binding.upNextPanel.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    33f
                )
                binding.btnPopOutCar.visibility = View.GONE
                binding.btnSidebarVoice.visibility = View.GONE
                binding.btnSidebarPopOutCar.visibility = View.GONE
                upNextAdapter.setThumbnailWidthDp(156)

                configureOverlayButtonScale(mode)
                binding.controlsOverlay.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
                if (isControlsVisible) {
                    scheduleAutoHide()
                }
            }
        }
    }

    private fun configureOverlayButtonScale(mode: DisplayModeHelper.LayoutMode) {
        when (mode) {
            DisplayModeHelper.LayoutMode.PIP_COMPACT -> {
                binding.btnQuality.visibility = View.GONE
                binding.btnAudioOnly.visibility = View.GONE
                binding.btnRewind.visibility = View.GONE
                binding.btnForward.visibility = View.GONE
                binding.tvVideoChannel.visibility = View.GONE
                resizeView(binding.btnPrev, 48)
                resizeView(binding.btnPlayPause, 60)
                resizeView(binding.btnNext, 48)
            }
            DisplayModeHelper.LayoutMode.PIP_LARGE -> {
                binding.btnQuality.visibility = View.VISIBLE
                binding.btnAudioOnly.visibility = View.VISIBLE
                binding.btnRewind.visibility = View.VISIBLE
                binding.btnForward.visibility = View.VISIBLE
                binding.tvVideoChannel.visibility = View.VISIBLE
                resizeView(binding.btnPrev, 56)
                resizeView(binding.btnRewind, 56)
                resizeView(binding.btnPlayPause, 70)
                resizeView(binding.btnForward, 56)
                resizeView(binding.btnNext, 56)
            }
            DisplayModeHelper.LayoutMode.FULLSCREEN -> {
                binding.btnQuality.visibility = View.VISIBLE
                binding.btnAudioOnly.visibility = View.VISIBLE
                binding.btnRewind.visibility = View.VISIBLE
                binding.btnForward.visibility = View.VISIBLE
                binding.tvVideoChannel.visibility = View.VISIBLE
                resizeView(binding.btnPrev, 66)
                resizeView(binding.btnRewind, 66)
                resizeView(binding.btnPlayPause, 84)
                resizeView(binding.btnForward, 66)
                resizeView(binding.btnNext, 66)
            }
        }
    }

    private fun resizeView(view: View, sizeDp: Int) {
        val px = dpToPx(sizeDp)
        view.layoutParams = view.layoutParams.apply {
            width = px
            height = px
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
        }
    }

    private fun connectToService() {
        val token = SessionToken(this, ComponentName(this, CarPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener(
            {
                val service = CarPlaybackService.instance ?: return@addListener
                attachPlayerToActiveView()
                service.setVideoEnabled(!userForcedAudioOnly)
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
        pendingVideo = null
        service.playVideo(video)
    }

    private fun popOutToMainCarDisplay() {
        val fullIntent = Intent(this, PlayerActivity::class.java)
        DisplayModeHelper.launchOnMainDisplay(this, fullIntent)
    }

    private fun togglePlayPause() {
        val player = CarPlaybackService.instance?.getPlayer() ?: return
        if (player.isPlaying) player.pause() else player.play()
        if (usesOverlayControls) scheduleAutoHide()
    }

    private fun cycleQuality() {
        val nextHeight = when (prefs.maxVideoHeight) {
            480 -> 720
            720 -> 1080
            else -> 480
        }
        CarPlaybackService.instance?.updateMaxVideoHeight(nextHeight)
        updateQualityChip()
        Toast.makeText(this, "Giới hạn chất lượng: ${nextHeight}p", Toast.LENGTH_SHORT).show()
        if (usesOverlayControls) scheduleAutoHide()
    }

    private fun toggleAudioOnly() {
        userForcedAudioOnly = !userForcedAudioOnly
        CarPlaybackService.instance?.setVideoEnabled(!userForcedAudioOnly)
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
                showQuickSeekHint("🎙 Đang mở: $cleaned...")
                lifecycleScope.launch {
                    runCatching {
                        YouTubeRepository.search(cleaned)
                    }.onSuccess { page ->
                        val first = page.items.firstOrNull()
                        if (first != null) {
                            CarPlaybackService.instance?.playVideo(first)
                        } else {
                            showQuickSeekHint("Không tìm thấy: $cleaned")
                        }
                    }.onFailure {
                        showQuickSeekHint("Không tìm được bài hát, thử lại nhé")
                    }
                }
            }
        }
    }

    private fun setupControls() {
        // Back to video browse list
        binding.btnBack.setOnClickListener { finish() }
        binding.btnDockBack.setOnClickListener { finish() }

        // Voice search right inside Player
        binding.btnVoice.setOnClickListener { launchVoiceSearchInPlayer() }
        binding.btnDockVoice.setOnClickListener { launchVoiceSearchInPlayer() }
        binding.btnSidebarVoice.setOnClickListener { launchVoiceSearchInPlayer() }

        // Separate "⤢ Toàn xe" button: pops CarTube out of PiP slot onto 1920x720 Display #0
        binding.btnPopOutCar.setOnClickListener { popOutToMainCarDisplay() }
        binding.btnDockPopOutCar.setOnClickListener { popOutToMainCarDisplay() }
        binding.btnSidebarPopOutCar.setOnClickListener { popOutToMainCarDisplay() }

        // In-Frame Full toggle (stays inside PiP if running in PiP!)
        val toggleFrameFullClick = View.OnClickListener {
            setFrameFullMode(!isFrameFull)
        }
        binding.btnToggleSplitTop.setOnClickListener(toggleFrameFullClick)
        binding.btnToggleSplitBottom.setOnClickListener(toggleFrameFullClick)
        binding.btnDockFullFrame.setOnClickListener { setFrameFullMode(true) }
        binding.btnSidebarFullFrame.setOnClickListener { setFrameFullMode(true) }

        // Fit vs Zoom-to-fill toggle when in Frame-Full mode
        binding.btnZoomMode.setOnClickListener {
            isZoomCrop = !isZoomCrop
            attachPlayerToActiveView()
            binding.btnZoomMode.text = if (isZoomCrop) "Tràn khung" else "Vừa khung"
            showQuickSeekHint(if (isZoomCrop) "Chế độ: Tràn đầy khung" else "Chế độ: Vừa tỉ lệ gốc")
            if (usesOverlayControls) scheduleAutoHide()
        }

        // Play / Pause
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnDockPlayPause.setOnClickListener { togglePlayPause() }

        // Previous / Next
        binding.btnPrev.setOnClickListener {
            CarPlaybackService.instance?.playPrevious()
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnDockPrev.setOnClickListener {
            CarPlaybackService.instance?.playPrevious()
        }

        binding.btnNext.setOnClickListener {
            CarPlaybackService.instance?.playNext()
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnDockNext.setOnClickListener {
            CarPlaybackService.instance?.playNext()
        }

        // ±10s Seek
        binding.btnRewind.setOnClickListener {
            CarPlaybackService.instance?.seekBy(-10_000L)
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnDockRewind.setOnClickListener {
            CarPlaybackService.instance?.seekBy(-10_000L)
        }

        binding.btnForward.setOnClickListener {
            CarPlaybackService.instance?.seekBy(10_000L)
            if (usesOverlayControls) scheduleAutoHide()
        }
        binding.btnDockForward.setOnClickListener {
            CarPlaybackService.instance?.seekBy(10_000L)
        }

        // Quality & Audio-Only
        binding.btnQuality.setOnClickListener { cycleQuality() }
        binding.btnDockQuality.setOnClickListener { cycleQuality() }

        binding.btnAudioOnly.setOnClickListener { toggleAudioOnly() }
        binding.btnDockAudioOnly.setOnClickListener { toggleAudioOnly() }

        // Overlay & Dock SeekBars
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val formatted = VideoAdapter.formatDuration(progress.toLong())
                    binding.tvCurrentTime.text = formatted
                    binding.tvDockCurrentTime.text = formatted
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
        binding.seekBar.setOnSeekBarChangeListener(seekListener)
        binding.dockSeekBar.setOnSeekBarChangeListener(seekListener)
    }

    private fun setupUpNextList() {
        upNextAdapter = UpNextAdapter { item, _ ->
            // Keep Split Watch Mode open while switching smoothly to the selected related video
            CarPlaybackService.instance?.playFromUpNext(item)
            binding.rvUpNext.scrollToPosition(0)
        }
        binding.rvUpNext.layoutManager = LinearLayoutManager(this)
        binding.rvUpNext.adapter = upNextAdapter
        binding.rvUpNext.itemAnimator = null
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
                        showQuickSeekHint(if (willPlay) "▶ Tiếp tục phát" else "⏸ Tạm dừng")
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
                val title = state.currentVideo?.title.orEmpty()
                val channel = state.currentVideo?.author.orEmpty()

                binding.tvVideoTitle.text = title
                binding.tvVideoChannel.text = channel
                binding.tvDockTitle.text = title
                binding.tvDockChannel.text = channel

                binding.progressLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                val playIcon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                binding.btnPlayPause.setImageResource(playIcon)
                binding.btnDockPlayPause.setImageResource(playIcon)

                upNextAdapter.submitList(state.upNextVideos)
                binding.tvUpNextHeader.text = if (state.upNextVideos.isNotEmpty()) {
                    "Tiếp theo (${state.upNextVideos.size})"
                } else {
                    "Tiếp theo"
                }

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

                updateQualityChip(state.actualVideoHeight)
                updateAudioOnlyUi()
            }
        }
    }

    private fun updateQualityChip(actualHeight: Int = PlaybackStateStore.state.value.actualVideoHeight) {
        val maxH = prefs.maxVideoHeight
        val label = if (actualHeight > 0) {
            "${actualHeight}p/${maxH}p"
        } else {
            "${maxH}p"
        }
        binding.btnQuality.text = label
        binding.btnDockQuality.text = label
    }

    private fun updateAudioOnlyUi() {
        val isAudio = PlaybackStateStore.state.value.isAudioOnly || userForcedAudioOnly
        binding.audioOnlyOverlay.visibility = if (isAudio) View.VISIBLE else View.GONE
        val label = if (isAudio) "Bật hình" else "Chỉ tiếng"
        binding.btnAudioOnly.text = label
        binding.btnDockAudioOnly.text = label
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

            binding.seekBar.max = maxInt
            binding.seekBar.progress = posInt
            binding.tvCurrentTime.text = curText
            binding.tvTotalTime.text = totText

            binding.dockSeekBar.max = maxInt
            binding.dockSeekBar.progress = posInt
            binding.tvDockCurrentTime.text = curText
            binding.tvDockTotalTime.text = totText
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
        uiHandler.postDelayed(hideControlsRunnable, 4_500L)
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

    /**
     * Direct steering-wheel & car physical button handling while PlayerActivity is focused.
     */
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
        private const val REQ_PLAYER_VOICE = 2002
    }
}
