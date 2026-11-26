package com.cartube.app.ui

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.cartube.app.R
import com.cartube.app.data.VideoItem
import com.cartube.app.databinding.ItemUpNextVideoBinding
import kotlin.math.roundToInt

class UpNextAdapter(
    private val onClick: (VideoItem, Int) -> Unit
) : RecyclerView.Adapter<UpNextAdapter.UpNextViewHolder>() {

    private val items = ArrayList<VideoItem>()
    private var thumbWidthDp: Int = 128
    private var rowHeightDp: Int = 84
    private var highlightFirst: Boolean = true
    private var titleTextSizeSp: Float = 15f
    private var subtitleTextSizeSp: Float = 13f
    private var titleMaxLines: Int = 2
    private var showSubtitle: Boolean = true
    private var horizontalPaddingDp: Int = 8
    private var thumbGapDp: Int = 12
    private var gravityTop: Boolean = false

    fun submitList(newItems: List<VideoItem>) {
        // The playback state is re-emitted every second; only redraw when the list really changed,
        // otherwise the rows rebind under the listener's finger while scrolling.
        if (items == newItems) return
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun configureStyle(
        thumbWidthDp: Int,
        rowHeightDp: Int,
        highlightFirst: Boolean,
        titleTextSizeSp: Float = 15f,
        subtitleTextSizeSp: Float = 13f,
        titleMaxLines: Int = 2,
        showSubtitle: Boolean = true,
        horizontalPaddingDp: Int = 8,
        thumbGapDp: Int = 12,
        gravityTop: Boolean = false
    ) {
        if (this.thumbWidthDp == thumbWidthDp &&
            this.rowHeightDp == rowHeightDp &&
            this.highlightFirst == highlightFirst &&
            this.titleTextSizeSp == titleTextSizeSp &&
            this.subtitleTextSizeSp == subtitleTextSizeSp &&
            this.titleMaxLines == titleMaxLines &&
            this.showSubtitle == showSubtitle &&
            this.horizontalPaddingDp == horizontalPaddingDp &&
            this.thumbGapDp == thumbGapDp &&
            this.gravityTop == gravityTop
        ) {
            return
        }
        this.thumbWidthDp = thumbWidthDp
        this.rowHeightDp = rowHeightDp
        this.highlightFirst = highlightFirst
        this.titleTextSizeSp = titleTextSizeSp
        this.subtitleTextSizeSp = subtitleTextSizeSp
        this.titleMaxLines = titleMaxLines
        this.showSubtitle = showSubtitle
        this.horizontalPaddingDp = horizontalPaddingDp
        this.thumbGapDp = thumbGapDp
        this.gravityTop = gravityTop
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
            ).roundToInt()
            val hPx = (wPx * 9f / 16f).roundToInt()
            val rowPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                rowHeightDp.toFloat(),
                metrics
            ).roundToInt()
            val hPadPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                horizontalPaddingDp.toFloat(),
                metrics
            ).roundToInt()
            val gapPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                thumbGapDp.toFloat(),
                metrics
            ).roundToInt()

            binding.upNextRowRoot.layoutParams = binding.upNextRowRoot.layoutParams.apply {
                height = rowPx
            }
            binding.upNextRowRoot.setPadding(
                hPadPx,
                binding.upNextRowRoot.paddingTop,
                hPadPx,
                binding.upNextRowRoot.paddingBottom
            )
            binding.upNextRowRoot.gravity = if (gravityTop) Gravity.TOP else Gravity.CENTER_VERTICAL
            binding.upNextRowRoot.isSelected = highlightFirst && position == 0

            binding.thumbContainer.layoutParams = binding.thumbContainer.layoutParams.apply {
                width = wPx
                height = hPx
            }

            binding.upNextTextContainer.layoutParams =
                (binding.upNextTextContainer.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = gapPx
                }

            binding.tvTitle.maxLines = titleMaxLines
            binding.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleTextSizeSp)
            binding.tvTitle.text = item.title

            binding.tvSubtitle.visibility = if (showSubtitle) View.VISIBLE else View.GONE
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
