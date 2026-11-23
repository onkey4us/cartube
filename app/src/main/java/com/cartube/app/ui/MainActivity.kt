package com.cartube.app.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoAdapter: VideoAdapter

    private var currentTab: NavTab = NavTab.HOME
    private var currentGroup: MediaGroup? = null
    private var feedJob: Job? = null
    private var isLoadingMore: Boolean = false

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupNavRail()
        setupSearchBar()
        setupMiniPlayer()
        observePlaybackState()

        selectTab(NavTab.HOME)
    }

    override fun onStart() {
        super.onStart()
        connectMediaSession()
        updateAccountIndicator()
    }

    override fun onStop() {
        super.onStop()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
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
            openPlayer(item, videoAdapter.getCurrentItems())
        }
        val spanCount = if (resources.configuration.screenWidthDp >= 1000) 4 else 3
        val gridLayoutManager = GridLayoutManager(this, spanCount)
        binding.rvVideos.layoutManager = gridLayoutManager
        binding.rvVideos.adapter = videoAdapter
        binding.rvVideos.itemAnimator = null // Zero animation overhead on car head unit

        binding.btnLoadMore.setOnClickListener {
            loadNextPage()
        }
    }

    private fun setupNavRail() {
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

    private fun selectTab(tab: NavTab) {
        currentTab = tab
        val map = mapOf(
            NavTab.HOME to binding.navHome,
            NavTab.MUSIC to binding.navMusic,
            NavTab.SUBSCRIPTIONS to binding.navSubs,
            NavTab.HISTORY to binding.navHistory,
            NavTab.SEARCH to binding.navSearch
        )
        map.forEach { (key, view) ->
            view.isSelected = key == tab
        }

        binding.searchBarContainer.visibility = if (tab == NavTab.SEARCH) View.VISIBLE else View.GONE
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
                    NavTab.HOME -> YouTubeRepository.loadHome()
                    NavTab.MUSIC -> YouTubeRepository.loadMusic()
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

    private fun openPlayer(video: VideoItem, queue: List<VideoItem>) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO, video)
            putExtra(PlayerActivity.EXTRA_QUEUE, ArrayList(queue.take(40)))
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
