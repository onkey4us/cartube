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
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cartube.app.R
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.FeedPage
import com.cartube.app.data.VideoItem
import com.cartube.app.data.VoiceAndLinkHelper
import com.cartube.app.data.VoiceOrLinkTarget
import com.cartube.app.data.YouTubeRepository
import com.cartube.app.databinding.ActivityMainBinding
import com.cartube.app.playback.CarPlaybackService
import com.cartube.app.playback.PlaybackStateStore
import com.google.common.util.concurrent.ListenableFuture
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : AppCompatActivity() {

    private enum class NavTab { HOME, MUSIC, SUBSCRIPTIONS, HISTORY, SEARCH }

    private data class CategoryChip(
        val id: String,
        val label: String,
        val loader: suspend () -> FeedPage
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: CarPrefs
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private var currentTab: NavTab = NavTab.HOME
    private var lastNonSearchTab: NavTab = NavTab.HOME
    private var selectedChipId: String = "home_all"
    private var currentGroup: MediaGroup? = null
    private var feedJob: Job? = null
    private var isLoadingMore: Boolean = false
    private var isMiniSeeking: Boolean = false

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val miniProgressRunnable = object : Runnable {
        override fun run() {
            updateMiniPlayerProgress()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val homeChips = listOf(
        CategoryChip("home_all", "Tất cả") { YouTubeRepository.loadHome() },
        CategoryChip("home_remix", "Remix") { YouTubeRepository.search("Nhạc Remix Nonstop đi xe hơi hay nhất") },
        CategoryChip("home_deephouse", "Deep House") { YouTubeRepository.search("Deep House Viet Mix House Lak 2026") },
        CategoryChip("home_bolero", "Bolero") { YouTubeRepository.search("Nhạc vàng Bolero trữ tình chọn lọc") },
        CategoryChip("home_vpop", "Nhạc Trẻ") { YouTubeRepository.search("Nhạc trẻ V-Pop tuyển chọn mới nhất") },
        CategoryChip("home_podcast", "Podcast") { YouTubeRepository.search("Podcast tiếng Việt hay nhất") },
        CategoryChip("home_lofi", "Lofi chill") { YouTubeRepository.search("Nhạc Lofi chill Acoustic lái xe") },
        CategoryChip("home_trending", "Thịnh hành") { YouTubeRepository.loadTrending() },
        CategoryChip("home_news", "Tin tức") { YouTubeRepository.loadNews() },
        CategoryChip("home_live", "Trực tiếp") { YouTubeRepository.loadLive() }
    )

    private val musicChips = listOf(
        CategoryChip("music_all", "Tất cả nhạc") { YouTubeRepository.loadMusic() },
        CategoryChip("music_remix", "Remix") { YouTubeRepository.search("Nhạc Remix Nonstop bass căng đi xe") },
        CategoryChip("music_deephouse", "Deep House") { YouTubeRepository.search("Deep House Chill Viet Mix 2026") },
        CategoryChip("music_bolero", "Bolero") { YouTubeRepository.search("Liên khúc Bolero trữ tình hay nhất") },
        CategoryChip("music_vpop", "Nhạc Trẻ") { YouTubeRepository.search("Nhạc trẻ V-Pop hay nhất hiện nay") },
        CategoryChip("music_lofi", "Lofi chill") { YouTubeRepository.search("Acoustic Lofi thư giãn lái xe") },
        CategoryChip("music_usuk", "Âu Mỹ") { YouTubeRepository.search("US UK Pop Hits playlist") },
        CategoryChip("music_inst", "Không lời") { YouTubeRepository.search("Nhạc không lời thư giãn nhẹ nhàng") }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = CarPrefs(this)
        setupRecyclerView()
        setupNavRail()
        setupSearchBar()
        setupMiniPlayer()
        applyResponsiveLayout()
        observePlaybackState()

        if (!handleIncomingVoiceOrLinkIntent(intent, isInitialLaunch = true)) {
            if (!handleRequestedInitialTab(intent)) {
                selectTab(NavTab.HOME)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleIncomingVoiceOrLinkIntent(intent, isInitialLaunch = false)) {
            handleRequestedInitialTab(intent)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout()
    }

    override fun onStart() {
        super.onStart()
        connectMediaSession()
        updateAccountIndicator()
        applyResponsiveLayout()
        refreshResumeCard()
        uiHandler.post(miniProgressRunnable)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(miniProgressRunnable)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    private fun handleRequestedInitialTab(intent: Intent?): Boolean {
        val tabExtra = intent?.getStringExtra(EXTRA_INITIAL_TAB) ?: return false
        intent.removeExtra(EXTRA_INITIAL_TAB)
        return when (tabExtra) {
            TAB_HOME -> {
                selectTab(NavTab.HOME)
                true
            }
            TAB_SEARCH -> {
                selectTab(NavTab.SEARCH)
                true
            }
            else -> false
        }
    }

    private fun applyResponsiveLayout() {
        val mode = DisplayModeHelper.getLayoutMode(this)
        val widthDp = resources.configuration.screenWidthDp
        when (mode) {
            DisplayModeHelper.LayoutMode.PIP_COMPACT -> {
                binding.leftNavRail.visibility = View.GONE
                binding.compactTopBar.visibility = View.VISIBLE
                binding.topSearchRow.visibility = if (currentTab == NavTab.SEARCH) View.VISIBLE else View.GONE
                binding.btnVoiceSearch.visibility = View.GONE
                binding.topSearchRow.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                binding.categoryChipScroll.setPadding(dpToPx(12), 0, dpToPx(12), 0)
                gridLayoutManager.spanCount = 1

                // Compact Mini Player
                binding.miniPlayerRow.layoutParams = binding.miniPlayerRow.layoutParams.apply {
                    height = dpToPx(76)
                }
                binding.miniPlayerRow.setPadding(dpToPx(12), 0, dpToPx(12), 0)
                binding.miniThumbFrame.visibility = View.GONE
                binding.miniSeekContainer.visibility = View.GONE
                binding.btnMiniPrev.visibility = View.GONE
                resizeView(binding.btnMiniPlayPause, 56)
                resizeView(binding.btnMiniNext, 56)
            }

            DisplayModeHelper.LayoutMode.PIP_LARGE -> {
                binding.leftNavRail.visibility = View.VISIBLE
                binding.compactTopBar.visibility = View.GONE
                binding.topSearchRow.visibility = View.VISIBLE
                binding.btnVoiceSearch.visibility = View.VISIBLE
                binding.topSearchRow.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(8))
                binding.categoryChipScroll.setPadding(dpToPx(16), 0, dpToPx(16), 0)
                gridLayoutManager.spanCount = if (widthDp >= 900) 3 else 2

                binding.miniPlayerRow.layoutParams = binding.miniPlayerRow.layoutParams.apply {
                    height = dpToPx(88)
                }
                binding.miniPlayerRow.setPadding(dpToPx(16), 0, dpToPx(16), 0)
                binding.miniThumbFrame.visibility = View.VISIBLE
                binding.miniSeekContainer.visibility = if (widthDp >= 820) View.VISIBLE else View.GONE
                binding.btnMiniPrev.visibility = View.VISIBLE
                resizeView(binding.btnMiniPrev, 60)
                resizeView(binding.btnMiniPlayPause, 68)
                resizeView(binding.btnMiniNext, 60)
            }

            DisplayModeHelper.LayoutMode.FULLSCREEN -> {
                binding.leftNavRail.visibility = View.VISIBLE
                binding.compactTopBar.visibility = View.GONE
                binding.topSearchRow.visibility = View.VISIBLE
                binding.btnVoiceSearch.visibility = View.VISIBLE
                binding.topSearchRow.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(8))
                binding.categoryChipScroll.setPadding(dpToPx(20), 0, dpToPx(20), 0)
                gridLayoutManager.spanCount = 4

                binding.miniPlayerRow.layoutParams = binding.miniPlayerRow.layoutParams.apply {
                    height = dpToPx(96)
                }
                binding.miniPlayerRow.setPadding(dpToPx(20), 0, dpToPx(20), 0)
                binding.miniThumbFrame.visibility = View.VISIBLE
                binding.miniSeekContainer.visibility = View.VISIBLE
                binding.btnMiniPrev.visibility = View.VISIBLE
                resizeView(binding.btnMiniPrev, 64)
                resizeView(binding.btnMiniPlayPause, 72)
                resizeView(binding.btnMiniNext, 64)
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

    private fun connectMediaSession() {
        val token = SessionToken(this, ComponentName(this, CarPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener(
            {
                mediaController = runCatching { controllerFuture?.get() }.getOrNull()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onResumeClick = { resumeModel ->
                handleResumeCardClick(resumeModel)
            },
            onVideoClick = { item, _ ->
                openPlayer(item)
            }
        )
        gridLayoutManager = GridLayoutManager(this, 4).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (videoAdapter.isFullSpanPosition(position)) spanCount else 1
                }
            }
        }
        binding.rvVideos.layoutManager = gridLayoutManager
        binding.rvVideos.adapter = videoAdapter
        binding.rvVideos.itemAnimator = null

        // Automatic infinite scroll pagination near the bottom
        binding.rvVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                val group = currentGroup ?: return
                if (group.nextPageKey.isNullOrBlank()) return
                val totalCount = gridLayoutManager.itemCount
                val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                if (totalCount > 0 && lastVisible >= totalCount - 6) {
                    loadNextPage()
                }
            }
        })
    }

    private fun handleResumeCardClick(resumeModel: ResumeUiModel) {
        val service = CarPlaybackService.instance
        val activeId = PlaybackStateStore.state.value.currentVideo?.videoId
        if (service != null && activeId == resumeModel.video.videoId) {
            val player = service.getPlayer()
            if (!player.isPlaying) {
                player.play()
            }
            startActivity(Intent(this, PlayerActivity::class.java))
        } else {
            openPlayer(resumeModel.video, resumePositionMs = resumeModel.positionMs)
        }
    }

    private fun setupNavRail() {
        binding.navHome.setOnClickListener { selectTab(NavTab.HOME) }
        binding.navMusic.setOnClickListener { selectTab(NavTab.MUSIC) }
        binding.navSubs.setOnClickListener { selectTab(NavTab.SUBSCRIPTIONS) }
        binding.navHistory.setOnClickListener { selectTab(NavTab.HISTORY) }
        binding.navAccount.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Compact 5-icon top bar (Narrow ~360dp mode)
        binding.compactHome.setOnClickListener { selectTab(NavTab.HOME) }
        binding.compactMusic.setOnClickListener { selectTab(NavTab.MUSIC) }
        binding.compactSearch.setOnClickListener { selectTab(NavTab.SEARCH) }
        binding.compactVoice.setOnClickListener { launchVoiceRecognizer() }
        binding.compactSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnRetry.setOnClickListener {
            if (currentTab == NavTab.SEARCH) {
                performSearch(binding.etSearch.text?.toString().orEmpty())
            } else {
                loadCurrentTab()
            }
        }
    }

    private fun setupSearchBar() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.etSearch.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrEmpty()
                binding.btnSearchClear.visibility = if (hasText) View.VISIBLE else View.GONE
            }
        })
        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.text?.clear()
            hideKeyboard()
            if (currentTab == NavTab.SEARCH) {
                selectTab(lastNonSearchTab)
            }
        }
        binding.btnVoiceSearch.setOnClickListener {
            launchVoiceRecognizer()
        }
    }

    private fun setupMiniPlayer() {
        val openCurrentPlayer = View.OnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        binding.miniThumbFrame.setOnClickListener(openCurrentPlayer)
        binding.miniInfoContainer.setOnClickListener(openCurrentPlayer)

        binding.btnMiniPrev.setOnClickListener {
            CarPlaybackService.instance?.playPrevious()
        }
        binding.btnMiniPlayPause.setOnClickListener {
            val player = CarPlaybackService.instance?.getPlayer()
            if (player != null) {
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        binding.btnMiniNext.setOnClickListener {
            CarPlaybackService.instance?.playNext()
        }

        binding.miniSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvMiniCurrentTime.text = VideoAdapter.formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                isMiniSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                isMiniSeeking = false
                bar?.let {
                    CarPlaybackService.instance?.getPlayer()?.seekTo(it.progress.toLong())
                }
            }
        })
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            PlaybackStateStore.state.collectLatest { state ->
                val video = state.currentVideo
                if (video == null) {
                    binding.miniPlayerContainer.visibility = View.GONE
                } else {
                    binding.miniPlayerContainer.visibility = View.VISIBLE
                    binding.tvMiniTitle.text = video.title
                    binding.tvMiniAuthor.text = if (state.isAudioOnly) {
                        "${video.author} · Chỉ tiếng"
                    } else {
                        video.author
                    }
                    binding.btnMiniPlayPause.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    Glide.with(binding.ivMiniThumb)
                        .load(video.thumbnailUrl)
                        .centerCrop()
                        .placeholder(R.drawable.bg_thumb_placeholder)
                        .into(binding.ivMiniThumb)
                }
                refreshResumeCard()
            }
        }
    }

    private fun updateMiniPlayerProgress() {
        if (isMiniSeeking) return
        val player = CarPlaybackService.instance?.getPlayer() ?: return
        val duration = player.duration.coerceAtLeast(0L)
        val position = player.currentPosition.coerceAtLeast(0L)
        if (duration > 0L) {
            binding.miniSeekBar.max = duration.toInt()
            binding.miniSeekBar.progress = position.toInt()
            binding.tvMiniCurrentTime.text = VideoAdapter.formatDuration(position)
            binding.tvMiniTotalTime.text = VideoAdapter.formatDuration(duration)
        }
    }

    private fun refreshResumeCard() {
        if (currentTab != NavTab.HOME) {
            videoAdapter.setResumeItem(null)
            return
        }
        val state = PlaybackStateStore.state.value
        val activeVideo = state.currentVideo
        if (activeVideo != null) {
            val player = CarPlaybackService.instance?.getPlayer()
            val posMs = player?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs
            val durMs = player?.duration?.takeIf { it > 0L }
                ?: state.durationMs.takeIf { it > 0L }
                ?: activeVideo.durationMs
            videoAdapter.setResumeItem(
                ResumeUiModel(
                    video = activeVideo,
                    positionMs = posMs,
                    durationMs = durMs,
                    isPlaying = state.isPlaying
                )
            )
            return
        }

        val saved = prefs.getLastPlayed()
        if (saved != null) {
            videoAdapter.setResumeItem(
                ResumeUiModel(
                    video = saved.video,
                    positionMs = saved.positionMs,
                    durationMs = saved.durationMs,
                    isPlaying = false
                )
            )
        } else {
            videoAdapter.setResumeItem(null)
        }
    }

    private fun updateAccountIndicator() {
        val account = YouTubeRepository.getSelectedAccount()
        val isSignedIn = account != null
        binding.viewAccountSignedDot.visibility = if (isSignedIn) View.VISIBLE else View.GONE
        binding.navAccount.contentDescription = account?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.nav_account)
    }

    private fun renderCategoryChipsForTab(tab: NavTab) {
        val chips = when (tab) {
            NavTab.HOME -> homeChips
            NavTab.MUSIC -> musicChips
            else -> emptyList()
        }
        if (chips.isEmpty()) {
            binding.categoryChipScroll.visibility = View.GONE
            binding.categoryChipGroup.removeAllViews()
            return
        }

        if (chips.none { it.id == selectedChipId }) {
            selectedChipId = chips.first().id
        }

        binding.categoryChipScroll.visibility = View.VISIBLE
        binding.categoryChipGroup.removeAllViews()

        val heightPx = dpToPx(48)
        val padH = dpToPx(20)
        val marginEnd = dpToPx(10)
        val textColors = ContextCompat.getColorStateList(this, R.color.chip_text_color)

        for (chip in chips) {
            val tv = TextView(this).apply {
                text = chip.label
                textSize = 16f
                setTextColor(textColors)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_pill_chip)
                setPadding(padH, 0, padH, 0)
                isSelected = chip.id == selectedChipId
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    heightPx
                ).apply {
                    this.marginEnd = marginEnd
                }
                setOnClickListener {
                    if (selectedChipId != chip.id) {
                        selectedChipId = chip.id
                        updateChipSelectionVisuals(chips)
                        loadCurrentTab()
                    }
                }
            }
            binding.categoryChipGroup.addView(tv)
        }
        binding.categoryChipScroll.scrollTo(0, 0)
    }

    private fun updateChipSelectionVisuals(chips: List<CategoryChip>) {
        val count = binding.categoryChipGroup.childCount
        for (i in 0 until count) {
            val view = binding.categoryChipGroup.getChildAt(i)
            val chip = chips.getOrNull(i) ?: continue
            view.isSelected = chip.id == selectedChipId
        }
    }

    private fun selectTab(tab: NavTab) {
        val tabChanged = currentTab != tab
        currentTab = tab
        if (tab != NavTab.SEARCH) {
            lastNonSearchTab = tab
        }
        if (tabChanged) {
            selectedChipId = when (tab) {
                NavTab.HOME -> homeChips.first().id
                NavTab.MUSIC -> musicChips.first().id
                else -> ""
            }
        }

        val railMap = mapOf(
            NavTab.HOME to binding.navHome,
            NavTab.MUSIC to binding.navMusic,
            NavTab.SUBSCRIPTIONS to binding.navSubs,
            NavTab.HISTORY to binding.navHistory
        )
        railMap.forEach { (key, view) -> view.isSelected = key == tab }

        val compactMap = mapOf(
            NavTab.HOME to binding.compactHome,
            NavTab.MUSIC to binding.compactMusic,
            NavTab.SEARCH to binding.compactSearch
        )
        compactMap.forEach { (key, view) -> view.isSelected = key == tab }

        renderCategoryChipsForTab(tab)
        refreshResumeCard()

        if (DisplayModeHelper.getLayoutMode(this) == DisplayModeHelper.LayoutMode.PIP_COMPACT) {
            binding.topSearchRow.visibility = if (tab == NavTab.SEARCH) View.VISIBLE else View.GONE
        }

        if (tab == NavTab.SEARCH) {
            binding.etSearch.requestFocus()
            if (videoAdapter.getVideoCount() == 0) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = getString(R.string.search_prompt)
            }
        } else {
            hideKeyboard()
            loadCurrentTab()
        }
    }

    private fun loadCurrentTab() {
        feedJob?.cancel()
        currentGroup = null
        isLoadingMore = false
        videoAdapter.setShowLoadingFooter(false)
        binding.progressBar.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE

        feedJob = lifecycleScope.launch {
            try {
                if ((currentTab == NavTab.SUBSCRIPTIONS || currentTab == NavTab.HISTORY) &&
                    !YouTubeRepository.isSignedIn()
                ) {
                    binding.progressBar.visibility = View.GONE
                    videoAdapter.submitList(emptyList())
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = getString(R.string.sign_in_required_hint)
                    return@launch
                }

                val page: FeedPage = when (currentTab) {
                    NavTab.HOME -> {
                        val chip = homeChips.firstOrNull { it.id == selectedChipId } ?: homeChips.first()
                        chip.loader()
                    }
                    NavTab.MUSIC -> {
                        val chip = musicChips.firstOrNull { it.id == selectedChipId } ?: musicChips.first()
                        chip.loader()
                    }
                    NavTab.SUBSCRIPTIONS -> YouTubeRepository.loadSubscriptions()
                    NavTab.HISTORY -> YouTubeRepository.loadHistory()
                    NavTab.SEARCH -> return@launch
                }

                bindFeedPage(page)
            } catch (e: Exception) {
                showFeedError(e.message ?: getString(R.string.error_load_feed))
            }
        }
    }

    private fun handleIncomingVoiceOrLinkIntent(incoming: Intent?, isInitialLaunch: Boolean): Boolean {
        val target = VoiceAndLinkHelper.parseIntent(incoming) ?: return false
        incoming?.action = null
        incoming?.data = null

        when (target) {
            is VoiceOrLinkTarget.Video -> {
                if (isInitialLaunch) {
                    selectTab(NavTab.HOME)
                }
                openPlayer(VoiceAndLinkHelper.buildPlaceholderVideoItem(target.videoId))
            }
            is VoiceOrLinkTarget.SearchAndPlay -> {
                selectTab(NavTab.SEARCH)
                binding.etSearch.setText(target.query)
                Toast.makeText(this, "Đang mở: ${target.query}", Toast.LENGTH_SHORT).show()
                performSearch(target.query, autoPlayFirst = true)
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun launchVoiceRecognizer() {
        try {
            startActivityForResult(VoiceAndLinkHelper.buildSpeechRecognizerIntent(), REQ_VOICE_SEARCH)
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
        if (requestCode == REQ_VOICE_SEARCH && resultCode == Activity.RESULT_OK) {
            val raw = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (raw.isNotEmpty()) {
                val cleaned = VoiceAndLinkHelper.cleanVoiceQuery(raw)
                selectTab(NavTab.SEARCH)
                binding.etSearch.setText(cleaned)
                Toast.makeText(this, "Đang mở: $cleaned", Toast.LENGTH_SHORT).show()
                performSearch(cleaned, autoPlayFirst = true)
            }
        }
    }

    private fun performSearch(rawQuery: String, autoPlayFirst: Boolean = false) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.search_prompt, Toast.LENGTH_SHORT).show()
            return
        }
        if (currentTab != NavTab.SEARCH) {
            currentTab = NavTab.SEARCH
            binding.navHome.isSelected = false
            binding.navMusic.isSelected = false
            binding.navSubs.isSelected = false
            binding.navHistory.isSelected = false
            binding.compactHome.isSelected = false
            binding.compactMusic.isSelected = false
            binding.compactSearch.isSelected = true
            renderCategoryChipsForTab(NavTab.SEARCH)
            refreshResumeCard()
        }
        hideKeyboard()
        feedJob?.cancel()
        currentGroup = null
        isLoadingMore = false
        videoAdapter.setShowLoadingFooter(false)
        binding.progressBar.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE

        feedJob = lifecycleScope.launch {
            try {
                val page = YouTubeRepository.search(query)
                bindFeedPage(page)
                if (autoPlayFirst) {
                    page.items.firstOrNull()?.let { firstVideo ->
                        openPlayer(firstVideo)
                    }
                }
            } catch (e: Exception) {
                showFeedError(e.message ?: getString(R.string.error_load_feed))
            }
        }
    }

    private fun bindFeedPage(page: FeedPage) {
        binding.progressBar.visibility = View.GONE
        currentGroup = page.sourceGroup
        videoAdapter.submitList(page.items)
        refreshResumeCard()
        binding.rvVideos.scrollToPosition(0)

        if (page.items.isEmpty() && videoAdapter.itemCount == 0) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = getString(R.string.empty_feed)
        } else {
            binding.tvEmptyState.visibility = View.GONE
        }
    }

    private fun loadNextPage() {
        val group = currentGroup ?: return
        if (isLoadingMore) return
        isLoadingMore = true
        videoAdapter.setShowLoadingFooter(true)

        lifecycleScope.launch {
            try {
                val nextPage = YouTubeRepository.loadMore(group)
                currentGroup = nextPage.sourceGroup
                videoAdapter.appendItems(nextPage.items)
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, R.string.error_load_more, Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingMore = false
                videoAdapter.setShowLoadingFooter(false)
            }
        }
    }

    private fun showFeedError(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.errorContainer.visibility = View.VISIBLE
        binding.tvError.text = msg
    }

    private fun openPlayer(video: VideoItem, resumePositionMs: Long = 0L) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO, video)
            if (resumePositionMs > 0L) {
                putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, resumePositionMs)
            }
        }
        startActivity(intent)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    CarPlaybackService.instance?.playNext()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    CarPlaybackService.instance?.playPrevious()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val p = CarPlaybackService.instance?.getPlayer()
                    if (p != null) {
                        if (p.isPlaying) p.pause() else p.play()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val REQ_VOICE_SEARCH = 2001
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        const val TAB_HOME = "home"
        const val TAB_SEARCH = "search"
    }
}
