package com.cartube.app.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.cartube.app.R
import com.cartube.app.data.VideoItem
import com.cartube.app.databinding.ItemUpNextVideoBinding

class UpNextAdapter(
    private val onClick: (VideoItem, Int) -> Unit
) : RecyclerView.Adapter<UpNextAdapter.UpNextViewHolder>() {

    private val items = ArrayList<VideoItem>()
    private var thumbWidthDp: Int = 128
    private var rowHeightDp: Int = 84
    private var highlightFirst: Boolean = true
    private var titleTextSizeSp: Float = 15f
    private var subtitleTextSizeSp: Float = 13f

    fun submitList(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun configureStyle(
        thumbWidthDp: Int,
        rowHeightDp: Int,
        highlightFirst: Boolean,
        titleTextSizeSp: Float = 15f,
        subtitleTextSizeSp: Float = 13f
    ) {
        if (this.thumbWidthDp == thumbWidthDp &&
            this.rowHeightDp == rowHeightDp &&
            this.highlightFirst == highlightFirst &&
            this.titleTextSizeSp == titleTextSizeSp &&
            this.subtitleTextSizeSp == subtitleTextSizeSp
        ) {
            return
        }
        this.thumbWidthDp = thumbWidthDp
        this.rowHeightDp = rowHeightDp
        this.highlightFirst = highlightFirst
        this.titleTextSizeSp = titleTextSizeSp
        this.subtitleTextSizeSp = subtitleTextSizeSp
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UpNextViewHolder {
        val binding = ItemUpNextVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UpNextViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UpNextViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class UpNextViewHolder(
        private val binding: ItemUpNextVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem, position: Int) {
            val metrics = binding.root.resources.displayMetrics
            val wPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                thumbWidthDp.toFloat(),
                metrics
            ).toInt()
            val hPx = (wPx * 9) / 16
            val rowPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                rowHeightDp.toFloat(),
                metrics
            ).toInt()

            binding.upNextRowRoot.layoutParams = binding.upNextRowRoot.layoutParams.apply {
                height = rowPx
            }
            binding.upNextRowRoot.isSelected = highlightFirst && position == 0

            binding.thumbContainer.layoutParams = binding.thumbContainer.layoutParams.apply {
                width = wPx
                height = hPx
            }

            binding.tvTitle.text = item.title
            binding.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleTextSizeSp)
            binding.tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            binding.tvSubtitle.text = when {
                item.author.isNotBlank() -> item.author
                else -> item.subtitle
            }

            val badge = when {
                item.isLive -> "TRỰC TIẾP"
                item.badgeText.isNotBlank() -> item.badgeText
                item.durationMs > 0L -> VideoAdapter.formatDuration(item.durationMs)
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
}
