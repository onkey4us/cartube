package com.cartube.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.cartube.app.R
import com.cartube.app.data.VideoItem
import com.cartube.app.databinding.ItemLoadingFooterBinding
import com.cartube.app.databinding.ItemResumeCardBinding
import com.cartube.app.databinding.ItemVideoCardBinding

data class ResumeUiModel(
    val video: VideoItem,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean
)

class VideoAdapter(
    private val onResumeClick: (ResumeUiModel) -> Unit,
    private val onVideoClick: (VideoItem, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var resumeItem: ResumeUiModel? = null
    private val rawItems = ArrayList<VideoItem>()
    private val displayedItems = ArrayList<VideoItem>()
    private var showLoadingFooter: Boolean = false

    fun submitList(newItems: List<VideoItem>) {
        rawItems.clear()
        rawItems.addAll(newItems)
        rebuildDisplayedItems()
        notifyDataSetChanged()
    }

    fun appendItems(moreItems: List<VideoItem>) {
        val existingIds = rawItems.mapTo(HashSet()) { it.videoId }
        val filtered = moreItems.filter { existingIds.add(it.videoId) }
        if (filtered.isEmpty()) return
        rawItems.addAll(filtered)
        val resumeId = resumeItem?.video?.videoId
        val toAdd = if (resumeId != null) filtered.filter { it.videoId != resumeId } else filtered
        if (toAdd.isEmpty()) return
        val insertPos = (if (resumeItem != null) 1 else 0) + displayedItems.size
        displayedItems.addAll(toAdd)
        notifyItemRangeInserted(insertPos, toAdd.size)
    }

    fun setResumeItem(newResume: ResumeUiModel?) {
        val oldResume = resumeItem
        if (oldResume == newResume) return

        val videoIdChanged = oldResume?.video?.videoId != newResume?.video?.videoId
        resumeItem = newResume

        if (videoIdChanged) {
            rebuildDisplayedItems()
            notifyDataSetChanged()
        } else if (newResume != null) {
            notifyItemChanged(0)
        }
    }

    fun setShowLoadingFooter(show: Boolean) {
        if (showLoadingFooter == show) return
        val footerPos = (if (resumeItem != null) 1 else 0) + displayedItems.size
        showLoadingFooter = show
        if (show) {
            notifyItemInserted(footerPos)
        } else {
            notifyItemRemoved(footerPos)
        }
    }

    fun getVideoCount(): Int = displayedItems.size

    fun isFullSpanPosition(position: Int): Boolean {
        return getItemViewType(position) == VIEW_TYPE_LOADING
    }

    private fun rebuildDisplayedItems() {
        displayedItems.clear()
        val resumeId = resumeItem?.video?.videoId
        if (resumeId == null) {
            displayedItems.addAll(rawItems)
        } else {
            for (item in rawItems) {
                if (item.videoId != resumeId) {
                    displayedItems.add(item)
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val hasResume = resumeItem != null
        if (hasResume && position == 0) return VIEW_TYPE_RESUME
        val videoStart = if (hasResume) 1 else 0
        val videoEnd = videoStart + displayedItems.size
        if (position in videoStart until videoEnd) return VIEW_TYPE_VIDEO
        return VIEW_TYPE_LOADING
    }

    override fun getItemCount(): Int {
        return (if (resumeItem != null) 1 else 0) +
            displayedItems.size +
            (if (showLoadingFooter) 1 else 0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_RESUME -> ResumeViewHolder(
                ItemResumeCardBinding.inflate(inflater, parent, false)
            )
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                ItemVideoCardBinding.inflate(inflater, parent, false)
            )
            else -> LoadingFooterViewHolder(
                ItemLoadingFooterBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ResumeViewHolder -> resumeItem?.let { holder.bind(it) }
            is VideoViewHolder -> {
                val index = position - (if (resumeItem != null) 1 else 0)
                if (index in displayedItems.indices) {
                    holder.bind(displayedItems[index], index)
                }
            }
            is LoadingFooterViewHolder -> Unit
        }
    }

    inner class ResumeViewHolder(
        private val binding: ItemResumeCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(model: ResumeUiModel) {
            val video = model.video
            val effectiveDuration = if (model.durationMs > 0L) model.durationMs else video.durationMs
            val progress = if (effectiveDuration > 0L) {
                ((model.positionMs * 1000L) / effectiveDuration).toInt().coerceIn(0, 1000)
            } else {
                0
            }

            binding.progressResume.progress = progress
            binding.tvTitle.text = video.title
            binding.tvSubtitle.text = formatResumeSubtitle(video.author, model.positionMs, effectiveDuration)
            binding.btnResumePlay.setImageResource(
                if (model.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            Glide.with(binding.ivThumb)
                .load(video.thumbnailUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(R.drawable.bg_thumb_placeholder)
                .into(binding.ivThumb)

            val click = View.OnClickListener { onResumeClick(model) }
            binding.root.setOnClickListener(click)
            binding.btnResumePlay.setOnClickListener(click)
        }
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem, index: Int) {
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text = when {
                item.author.isNotBlank() && item.subtitle.isNotBlank() && !item.subtitle.startsWith(item.author) ->
                    "${item.author} · ${item.subtitle}"
                item.author.isNotBlank() -> item.author
                else -> item.subtitle
            }

            val badge = when {
                item.isLive -> "TRỰC TIẾP"
                item.badgeText.isNotBlank() -> item.badgeText
                item.durationMs > 0L -> formatDuration(item.durationMs)
                else -> ""
            }
            if (badge.isNotBlank()) {
                binding.tvDuration.visibility = View.VISIBLE
                binding.tvDuration.text = badge
                binding.tvDuration.setBackgroundResource(
                    if (item.isLive) R.drawable.bg_badge_live else R.drawable.bg_badge_duration
                )
            } else {
                binding.tvDuration.visibility = View.GONE
            }

            Glide.with(binding.ivThumb)
                .load(item.thumbnailUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(R.drawable.bg_thumb_placeholder)
                .into(binding.ivThumb)

            binding.root.setOnClickListener {
                onVideoClick(item, index)
            }
        }
    }

    private class LoadingFooterViewHolder(
        binding: ItemLoadingFooterBinding
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val VIEW_TYPE_RESUME = 0
        private const val VIEW_TYPE_VIDEO = 1
        private const val VIEW_TYPE_LOADING = 2

        fun formatDuration(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val hours = totalSec / 3600L
            val mins = (totalSec % 3600L) / 60L
            val secs = totalSec % 60L
            return if (hours > 0L) {
                String.format("%d:%02d:%02d", hours, mins, secs)
            } else {
                String.format("%d:%02d", mins, secs)
            }
        }

        fun formatResumeSubtitle(author: String, positionMs: Long, durationMs: Long): String {
            val timeInfo = when {
                durationMs > 0L && positionMs < durationMs -> {
                    val remainingMs = durationMs - positionMs
                    val remainingMins = (remainingMs / 60_000L).coerceAtLeast(1L)
                    "còn $remainingMins phút"
                }
                positionMs > 0L -> "đã nghe ${formatDuration(positionMs)}"
                else -> ""
            }
            return when {
                author.isNotBlank() && timeInfo.isNotBlank() -> "$author · $timeInfo"
                author.isNotBlank() -> author
                else -> timeInfo
            }
        }
    }
}
