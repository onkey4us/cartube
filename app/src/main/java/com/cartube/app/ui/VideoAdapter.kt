package com.cartube.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.cartube.app.R
import com.cartube.app.data.VideoItem
import com.cartube.app.databinding.ItemVideoCardBinding

class VideoAdapter(
    private val compact: Boolean = false,
    private val onClick: (VideoItem, Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val items = ArrayList<VideoItem>()

    fun submitList(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendItems(moreItems: List<VideoItem>) {
        val existingIds = items.mapTo(HashSet()) { it.videoId }
        val filtered = moreItems.filter { existingIds.add(it.videoId) }
        if (filtered.isEmpty()) return
        val start = items.size
        items.addAll(filtered)
        notifyItemRangeInserted(start, filtered.size)
    }

    fun getCurrentItems(): List<VideoItem> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(
        private val binding: ItemVideoCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem, position: Int) {
            binding.tvTitle.text = item.title
            binding.tvTitle.maxLines = if (compact) 2 else 2
            binding.tvSubtitle.text = when {
                item.author.isNotBlank() && item.subtitle.isNotBlank() && !item.subtitle.startsWith(item.author) ->
                    "${item.author} • ${item.subtitle}"
                item.subtitle.isNotBlank() -> item.subtitle
                else -> item.author
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
                onClick(item, position)
            }
        }
    }

    companion object {
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
    }
}
