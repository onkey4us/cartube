package com.cartube.app.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
    private var pendingQueue: List<VideoItem> = emptyList()

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
        observePlaybackState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIntentExtras(intent)
        triggerPendingPlaybackIfReady()
    }

    override fun onStart() {
        super.onStart()
        hideSystemBars()
        connectToService()
        uiHandler.post(progressRunnable)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(progressRunnable)
        uiHandler.removeCallbacks(hideControlsRunnable)

        // Detach SurfaceView to free GPU/SurfaceFlinger immediately
        binding.playerView.player = null

        // Auto switch to audio-only when minimized to Maps/Vietmap to cut CPU usage
        val service = CarPlaybackService.instance
        if (service != null && prefs.backgroundAudioOnly && service.getPlayer().isPlaying) {
            service.setVideoEnabled(false)
        }

        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    @Suppress("DEPRECATION")
    private fun extractIntentExtras(intent: Intent?) {
        val video = intent?.getSerializableExtra(EXTRA_VIDEO) as? VideoItem
        val queue = (intent?.getSerializableExtra(EXTRA_QUEUE) as? ArrayList<*>)
            ?.filterIsInstance<VideoItem>()
            .orEmpty()
        if (video != null) {
            pendingVideo = video
            pendingQueue = queue
        }
    }

    private fun connectToService() {
        val token = SessionToken(this, ComponentName(this, CarPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener(
            {
                val service = CarPlaybackService.instance ?: return@addListener
                binding.playerView.player = service.getPlayer()
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
        val queue = pendingQueue
        pendingQueue = emptyList()
        service.playVideo(video, queue)
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

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
            val currentQueue = PlaybackStateStore.state.value.queue
            CarPlaybackService.instance?.playVideo(item, currentQueue)
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

                val upNext = if (state.currentIndex >= 0 && state.currentIndex + 1 < state.queue.size) {
                    state.queue.subList(state.currentIndex + 1, state.queue.size)
                } else {
                    state.relatedVideos
                }
                upNextAdapter.submitList(upNext)

                if (!state.statusNotice.isNullOrBlank()) {
                    binding.tvStatusNotice.text = state.statusNotice
                    binding.tvStatusNotice.visibility = View.VISIBLE
                } else if (!state.errorMessage.isNullOrBlank()) {
                    binding.tvStatusNotice.text = state.errorMessage
                    binding.tvStatusNotice.visibility = View.VISIBLE
                } else {
                    binding.tvStatusNotice.visibility = View.GONE
                }

                updateAudioOnlyUi()
            }
        }
    }

    private fun updateQualityChip() {
        binding.btnQuality.text = "${prefs.maxVideoHeight}p"
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
        } else {
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
        const val EXTRA_QUEUE = "extra_queue"
    }
}
