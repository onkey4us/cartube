package com.cartube.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.cartube.app.R
import com.cartube.app.data.FeedPage
import com.cartube.app.data.VideoItem
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
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private var currentTab: NavTab = NavTab.HOME
    private var selectedChipId: String = "home_recommended"
    private var currentGroup: MediaGroup? = null
    private var feedJob: Job? = null
    private var isLoadingMore: Boolean = false

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val homeChips = listOf(
        CategoryChip("home_recommended", "Đề xuất") { YouTubeRepository.loadHome() },
        CategoryChip("home_trending", "Thịnh hành") { YouTubeRepository.loadTrending() },
        CategoryChip("home_vpop", "Nhạc trẻ") { YouTubeRepository.search("Nhạc trẻ V-Pop tuyển chọn mới nhất") },
        CategoryChip("home_bolero", "Bolero") { YouTubeRepository.search("Nhạc vàng Bolero trữ tình chọn lọc") },
        CategoryChip("home_remix", "Remix / Nonstop") { YouTubeRepository.search("Nhạc trẻ Remix Nonstop đi xe hơi") },
        CategoryChip("home_chill", "Acoustic / Chill") { YouTubeRepository.search("Nhạc Acoustic Lofi chill nhẹ nhàng") },
        CategoryChip("home_podcast", "Podcast") { YouTubeRepository.search("Podcast tiếng Việt hay nhất") },
        CategoryChip("home_news", "Tin tức") { YouTubeRepository.loadNews() },
        CategoryChip("home_live", "Trực tiếp") { YouTubeRepository.loadLive() }
    )

    private val musicChips = listOf(
        CategoryChip("music_all", "Tất cả nhạc") { YouTubeRepository.loadMusic() },
        CategoryChip("music_vpop", "Nhạc trẻ / V-Pop") { YouTubeRepository.search("Nhạc trẻ V-Pop hay nhất hiện nay") },
        CategoryChip("music_bolero", "Bolero / Trữ tình") { YouTubeRepository.search("Liên khúc Bolero trữ tình hay nhất") },
        CategoryChip("music_remix", "Remix / Nonstop") { YouTubeRepository.search("Nhạc Remix Nonstop bass căng đi xe") },
        CategoryChip("music_chill", "Acoustic / Lofi") { YouTubeRepository.search("Acoustic Lofi thư giãn lái xe") },
        CategoryChip("music_usuk", "Âu Mỹ / US-UK") { YouTubeRepository.search("US UK Pop Hits playlist") },
        CategoryChip("music_inst", "Không lời") { YouTubeRepository.search("Nhạc không lời thư giãn nhẹ nhàng") }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupNavRail()
        setupSearchBar()
        setupMiniPlayer()
        applyResponsiveLayout()
        observePlaybackState()

        selectTab(NavTab.HOME)
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
    }

    override fun onStop() {
        super.onStop()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    private fun applyResponsiveLayout() {
        val mode = DisplayModeHelper.getLayoutMode(this)
        val widthDp = resources.configuration.screenWidthDp
        when (mode) {
            DisplayModeHelper.LayoutMode.PIP_COMPACT -> {
                binding.leftNavRail.visibility = View.GONE
                binding.topCompactNavScroll.visibility = View.VISIBLE
                binding.headerContainer.visibility = if (currentTab == NavTab.SEARCH) View.VISIBLE else View.GONE
                gridLayoutManager.spanCount = 1
                binding.ivMiniThumb.visibility = View.GONE
                binding.btnMiniPrev.visibility = View.GONE
            }
            DisplayModeHelper.LayoutMode.PIP_LARGE -> {
                binding.leftNavRail.visibility = View.VISIBLE
                binding.leftNavRail.layoutParams = binding.leftNavRail.layoutParams.apply {
                    width = dpToPx(124)
                }
                binding.topCompactNavScroll.visibility = View.GONE
                binding.headerContainer.visibility = View.VISIBLE
                gridLayoutManager.spanCount = if (widthDp >= 900) 3 else 2
                binding.ivMiniThumb.visibility = View.VISIBLE
                binding.btnMiniPrev.visibility = View.VISIBLE
            }
            DisplayModeHelper.LayoutMode.FULLSCREEN -> {
                binding.leftNavRail.visibility = View.VISIBLE
                binding.leftNavRail.layoutParams = binding.leftNavRail.layoutParams.apply {
                    width = dpToPx(176)
                }
                binding.topCompactNavScroll.visibility = View.GONE
                binding.headerContainer.visibility = View.VISIBLE
                gridLayoutManager.spanCount = 4
                binding.ivMiniThumb.visibility = View.VISIBLE
                binding.btnMiniPrev.visibility = View.VISIBLE
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
        videoAdapter = VideoAdapter { item, _ ->
            openPlayer(item)
        }
        gridLayoutManager = GridLayoutManager(this, 3)
        binding.rvVideos.layoutManager = gridLayoutManager
        binding.rvVideos.adapter = videoAdapter
        binding.rvVideos.itemAnimator = null // Zero animation overhead on car head unit

        binding.btnLoadMore.setOnClickListener {
            loadNextPage()
        }
    }

    private fun setupNavRail() {
        // Left rail buttons
        binding.navHome.setOnClickListener { selectTab(NavTab.HOME) }
        binding.navMusic.setOnClickListener { selectTab(NavTab.MUSIC) }
        binding.navSubs.setOnClickListener { selectTab(NavTab.SUBSCRIPTIONS) }
        binding.navHistory.setOnClickListener { selectTab(NavTab.HISTORY) }
        binding.navSearch.setOnClickListener { selectTab(NavTab.SEARCH) }
        binding.navAccount.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Compact top bar buttons (for 360x712 narrow PiP slot)
        binding.compactHome.setOnClickListener { selectTab(NavTab.HOME) }
        binding.compactMusic.setOnClickListener { selectTab(NavTab.MUSIC) }
        binding.compactSubs.setOnClickListener { selectTab(NavTab.SUBSCRIPTIONS) }
        binding.compactHistory.setOnClickListener { selectTab(NavTab.HISTORY) }
        binding.compactSearch.setOnClickListener { selectTab(NavTab.SEARCH) }
        binding.compactAccount.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
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
        binding.btnSearchSubmit.setOnClickListener {
            performSearch(binding.etSearch.text?.toString().orEmpty())
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.etSearch.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.text?.clear()
        }
    }

    private fun setupMiniPlayer() {
        binding.miniPlayerContainer.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
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
                        "${video.author} • Đang phát âm thanh nền"
                    } else {
                        video.author
                    }
                    binding.btnMiniPlayPause.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    Glide.with(binding.ivMiniThumb)
                        .load(video.thumbnailUrl)
                        .centerCrop()
                        .into(binding.ivMiniThumb)
                }
            }
        }
    }

    private fun updateAccountIndicator() {
        val account = YouTubeRepository.getSelectedAccount()
        binding.tvAccountLabel.text = if (account != null && !account.name.isNullOrBlank()) {
            account.name
        } else {
            getString(R.string.nav_account)
        }
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

        val heightPx = dpToPx(38)
        val padH = dpToPx(14)
        val marginEnd = dpToPx(8)
        val textColor = ContextCompat.getColor(this, R.color.car_text_primary)

        for (chip in chips) {
            val tv = TextView(this).apply {
                text = chip.label
                textSize = 14f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_nav_item)
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
            NavTab.HISTORY to binding.navHistory,
            NavTab.SEARCH to binding.navSearch
        )
        railMap.forEach { (key, view) -> view.isSelected = key == tab }

        val compactMap = mapOf(
            NavTab.HOME to binding.compactHome,
            NavTab.MUSIC to binding.compactMusic,
            NavTab.SUBSCRIPTIONS to binding.compactSubs,
            NavTab.HISTORY to binding.compactHistory,
            NavTab.SEARCH to binding.compactSearch
        )
        compactMap.forEach { (key, view) -> view.isSelected = key == tab }

        renderCategoryChipsForTab(tab)

        binding.searchBarContainer.visibility = if (tab == NavTab.SEARCH) View.VISIBLE else View.GONE
        if (DisplayModeHelper.getLayoutMode(this) == DisplayModeHelper.LayoutMode.PIP_COMPACT) {
            binding.headerContainer.visibility = if (tab == NavTab.SEARCH) View.VISIBLE else View.GONE
            binding.tvHeaderTitle.visibility = View.GONE
        } else {
            binding.headerContainer.visibility = View.VISIBLE
            binding.tvHeaderTitle.visibility = View.VISIBLE
        }
        binding.tvHeaderTitle.text = when (tab) {
            NavTab.HOME -> getString(R.string.nav_home)
            NavTab.MUSIC -> getString(R.string.nav_music)
            NavTab.SUBSCRIPTIONS -> getString(R.string.nav_subscriptions)
            NavTab.HISTORY -> getString(R.string.nav_history)
            NavTab.SEARCH -> getString(R.string.nav_search)
        }

        if (tab == NavTab.SEARCH) {
            binding.etSearch.requestFocus()
            if (videoAdapter.itemCount == 0) {
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
        binding.progressBar.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE

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

    private fun performSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.search_prompt, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        feedJob?.cancel()
        currentGroup = null
        binding.progressBar.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE

        feedJob = lifecycleScope.launch {
            try {
                val page = YouTubeRepository.search(query)
                bindFeedPage(page)
            } catch (e: Exception) {
                showFeedError(e.message ?: getString(R.string.error_load_feed))
            }
        }
    }

    private fun bindFeedPage(page: FeedPage) {
        binding.progressBar.visibility = View.GONE
        currentGroup = page.sourceGroup
        videoAdapter.submitList(page.items)
        binding.rvVideos.scrollToPosition(0)

        if (page.items.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = getString(R.string.empty_feed)
            binding.btnLoadMore.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.btnLoadMore.visibility =
                if (!page.sourceGroup?.nextPageKey.isNullOrBlank()) View.VISIBLE else View.GONE
        }
    }

    private fun loadNextPage() {
        val group = currentGroup ?: return
        if (isLoadingMore) return
        isLoadingMore = true
        binding.btnLoadMore.isEnabled = false
        binding.btnLoadMore.text = getString(R.string.loading)

        lifecycleScope.launch {
            try {
                val nextPage = YouTubeRepository.loadMore(group)
                currentGroup = nextPage.sourceGroup
                videoAdapter.appendItems(nextPage.items)
                binding.btnLoadMore.visibility =
                    if (!nextPage.sourceGroup?.nextPageKey.isNullOrBlank()) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, R.string.error_load_more, Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingMore = false
                binding.btnLoadMore.isEnabled = true
                binding.btnLoadMore.text = getString(R.string.load_more)
            }
        }
    }

    private fun showFeedError(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.errorContainer.visibility = View.VISIBLE
        binding.tvError.text = msg
    }

    private fun openPlayer(video: VideoItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO, video)
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
}
