package com.cartube.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.cartube.app.R
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.VideoItem
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
    private lateinit var upNextAdapter: VideoAdapter

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var userForcedAudioOnly: Boolean = false
    private var isSeeking: Boolean = false
    private var isControlsVisible: Boolean = true
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
            binding.upNextPanel.visibility = View.GONE
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

    private fun activePlayerView(): PlayerView {
        // Use TextureView inside ActivityViewVirtualDisplay / PiP (fixes Android 9 SurfaceView black screen)
        // Use SurfaceView on main fullscreen display #0 for minimum CPU/GPU load
        return if (DisplayModeHelper.isEmbeddedOrPip(this)) {
            binding.playerViewTexture
        } else {
            binding.playerViewSurface
        }
    }

    private fun attachPlayerToActiveView() {
        val player = CarPlaybackService.instance?.getPlayer() ?: return
        val useTexture = DisplayModeHelper.isEmbeddedOrPip(this)
        if (useTexture) {
            binding.playerViewSurface.player = null
            binding.playerViewSurface.visibility = View.GONE
            binding.playerViewTexture.visibility = View.VISIBLE
            if (binding.playerViewTexture.player !== player) {
                binding.playerViewTexture.player = player
            }
        } else {
            binding.playerViewTexture.player = null
            binding.playerViewTexture.visibility = View.GONE
            binding.playerViewSurface.visibility = View.VISIBLE
            if (binding.playerViewSurface.player !== player) {
                binding.playerViewSurface.player = player
            }
        }
    }

    private fun applyResponsiveLayout() {
        val embedded = DisplayModeHelper.isEmbeddedOrPip(this)
        val mode = DisplayModeHelper.getLayoutMode(this)

        // Switch SurfaceView vs TextureView cleanly
        if (embedded) {
            binding.playerViewSurface.visibility = View.GONE
            binding.playerViewTexture.visibility = View.VISIBLE
        } else {
            binding.playerViewTexture.visibility = View.GONE
            binding.playerViewSurface.visibility = View.VISIBLE
        }
        attachPlayerToActiveView()

        // Show "Full" button only when running inside a PiP slot
        binding.btnFullscreen.visibility = if (embedded) View.VISIBLE else View.GONE

        when (mode) {
            DisplayModeHelper.LayoutMode.PIP_COMPACT -> {
                // 360x712 narrow PiP slot on Beijing X7 home screen
                binding.btnQuality.visibility = View.GONE
                binding.btnAudioOnly.visibility = View.GONE
                binding.btnRewind.visibility = View.GONE
                binding.btnForward.visibility = View.GONE
                binding.tvVideoChannel.visibility = View.GONE
                resizeView(binding.btnPrev, 48)
                resizeView(binding.btnPlayPause, 60)
                resizeView(binding.btnNext, 48)
                binding.upNextPanel.layoutParams = binding.upNextPanel.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            DisplayModeHelper.LayoutMode.PIP_LARGE -> {
                // 1061x712 / 1080x580 large PiP slot on Beijing X7 home screen
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
                binding.upNextPanel.layoutParams = binding.upNextPanel.layoutParams.apply {
                    width = dpToPx(310)
                }
            }
            DisplayModeHelper.LayoutMode.FULLSCREEN -> {
                // 1920x720 main screen
                binding.btnQuality.visibility = View.VISIBLE
                binding.btnAudioOnly.visibility = View.VISIBLE
                binding.btnRewind.visibility = View.VISIBLE
                binding.btnForward.visibility = View.VISIBLE
                binding.tvVideoChannel.visibility = View.VISIBLE
                resizeView(binding.btnPrev, 68)
                resizeView(binding.btnRewind, 68)
                resizeView(binding.btnPlayPause, 86)
                resizeView(binding.btnForward, 68)
                resizeView(binding.btnNext, 68)
                binding.upNextPanel.layoutParams = binding.upNextPanel.layoutParams.apply {
                    width = dpToPx(360)
                }
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
                scheduleAutoHide()
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

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnFullscreen.setOnClickListener {
            // Pop PlayerActivity out of the PiP slot onto Display #0 (1920x720)
            val fullIntent = Intent(this, PlayerActivity::class.java)
            DisplayModeHelper.launchOnMainDisplay(this, fullIntent)
        }

        binding.btnPlayPause.setOnClickListener {
            val player = CarPlaybackService.instance?.getPlayer() ?: return@setOnClickListener
            if (player.isPlaying) player.pause() else player.play()
            scheduleAutoHide()
        }

        binding.btnPrev.setOnClickListener {
            CarPlaybackService.instance?.playPrevious()
            scheduleAutoHide()
        }

        binding.btnNext.setOnClickListener {
            CarPlaybackService.instance?.playNext()
            scheduleAutoHide()
        }

        binding.btnRewind.setOnClickListener {
            CarPlaybackService.instance?.seekBy(-10_000L)
            scheduleAutoHide()
        }

        binding.btnForward.setOnClickListener {
            CarPlaybackService.instance?.seekBy(10_000L)
            scheduleAutoHide()
        }

        binding.btnQuality.setOnClickListener {
            val nextHeight = when (prefs.maxVideoHeight) {
                480 -> 720
                720 -> 1080
                else -> 480
            }
            CarPlaybackService.instance?.updateMaxVideoHeight(nextHeight)
            updateQualityChip()
            Toast.makeText(this, "Giới hạn chất lượng: ${nextHeight}p", Toast.LENGTH_SHORT).show()
            scheduleAutoHide()
        }

        binding.btnAudioOnly.setOnClickListener {
            userForcedAudioOnly = !userForcedAudioOnly
            CarPlaybackService.instance?.setVideoEnabled(!userForcedAudioOnly)
            updateAudioOnlyUi()
            scheduleAutoHide()
        }

        binding.btnUpNext.setOnClickListener {
            val show = binding.upNextPanel.visibility != View.VISIBLE
            binding.upNextPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                uiHandler.removeCallbacks(hideControlsRunnable)
            } else {
                scheduleAutoHide()
            }
        }

        binding.btnCloseUpNext.setOnClickListener {
            binding.upNextPanel.visibility = View.GONE
            scheduleAutoHide()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = VideoAdapter.formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                uiHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                seekBar?.let {
                    CarPlaybackService.instance?.getPlayer()?.seekTo(it.progress.toLong())
                }
                scheduleAutoHide()
            }
        })
    }

    private fun setupUpNextList() {
        upNextAdapter = VideoAdapter(compact = true) { item, _ ->
            binding.upNextPanel.visibility = View.GONE
            CarPlaybackService.instance?.playFromUpNext(item)
        }
        binding.rvUpNext.layoutManager = LinearLayoutManager(this)
        binding.rvUpNext.adapter = upNextAdapter
        binding.rvUpNext.itemAnimator = null
    }

    private fun setupTouchGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.upNextPanel.visibility == View.VISIBLE) {
                    binding.upNextPanel.visibility = View.GONE
                } else {
                    setControlsVisible(!isControlsVisible)
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
            if (PlaybackStateStore.state.value.statusNotice == null) {
                binding.tvStatusNotice.visibility = View.GONE
            }
        }, 1_000L)
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            PlaybackStateStore.state.collectLatest { state ->
                binding.tvVideoTitle.text = state.currentVideo?.title.orEmpty()
                binding.tvVideoChannel.text = state.currentVideo?.author.orEmpty()
                binding.progressLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                binding.btnPlayPause.setImageResource(
                    if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )

                upNextAdapter.submitList(state.upNextVideos)

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
        binding.btnQuality.text = if (actualHeight > 0) {
            "${actualHeight}p/${maxH}p"
        } else {
            "${maxH}p"
        }
    }

    private fun updateAudioOnlyUi() {
        val isAudio = PlaybackStateStore.state.value.isAudioOnly || userForcedAudioOnly
        binding.audioOnlyOverlay.visibility = if (isAudio) View.VISIBLE else View.GONE
        binding.btnAudioOnly.text = if (isAudio) "Bật hình" else "Chỉ tiếng"
    }

    private fun updateSeekProgress() {
        if (isSeeking) return
        val player = CarPlaybackService.instance?.getPlayer() ?: return
        val duration = player.duration.coerceAtLeast(0L)
        val position = player.currentPosition.coerceAtLeast(0L)
        if (duration > 0L) {
            binding.seekBar.max = duration.toInt()
            binding.seekBar.progress = position.toInt()
            binding.tvCurrentTime.text = VideoAdapter.formatDuration(position)
            binding.tvTotalTime.text = VideoAdapter.formatDuration(duration)
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        isControlsVisible = visible
        binding.controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        uiHandler.removeCallbacks(hideControlsRunnable)
        if (visible) {
            scheduleAutoHide()
        } else if (!DisplayModeHelper.isEmbeddedOrPip(this)) {
            hideSystemBars()
        }
    }

    private fun scheduleAutoHide() {
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
                    setControlsVisible(true)
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
    }
}
