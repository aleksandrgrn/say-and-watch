package com.voicesearch.ui

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.voicesearch.R
import com.voicesearch.databinding.ItemResultCardBinding
import com.voicesearch.model.SearchResult

class SearchAdapter(
    private val onItemClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchAdapter.ResultViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemResultCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val holder = ResultViewHolder(binding, onItemClick)
        // Длинное название: без фокуса многоточие, в фокусе бегущая строка.
        // Marquee крутится только у выбранного TextView, а сам он фокус не берёт.
        binding.root.setOnFocusChangeListener { _, hasFocus ->
            binding.title.ellipsize =
                if (hasFocus) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            binding.title.isSelected = hasFocus
        }
        return holder
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ResultViewHolder(
        private val binding: ItemResultCardBinding,
        private val onItemClick: (SearchResult) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: SearchResult) {
            binding.title.text = result.title

            // Year
            if (!result.year.isNullOrBlank()) {
                binding.year.visibility = View.VISIBLE
                binding.year.text = result.year
            } else {
                binding.year.visibility = View.GONE
            }

            // Type badge
            val type = result.metadata["type"]
            when (type) {
                "movie" -> {
                    binding.typeBadge.visibility = View.VISIBLE
                    binding.typeBadge.text = binding.root.context.getString(R.string.type_movie)
                    binding.typeBadge.setBackgroundResource(R.drawable.bg_type_badge)
                    binding.typeBadge.backgroundTintList = null
                }
                "tv" -> {
                    binding.typeBadge.visibility = View.VISIBLE
                    binding.typeBadge.text = binding.root.context.getString(R.string.type_tv)
                    binding.typeBadge.setBackgroundResource(R.drawable.bg_type_badge)
                    binding.typeBadge.backgroundTintList = ColorStateList.valueOf(
                        0xCC1A237E.toInt() // semi-transparent dark blue
                    )
                }
                else -> binding.typeBadge.visibility = View.GONE
            }

            // Rating badge
            val rating = result.metadata["rating"]
            if (!rating.isNullOrBlank()) {
                binding.ratingBadge.visibility = View.VISIBLE
                binding.ratingBadge.text = "★ $rating"
            } else {
                binding.ratingBadge.visibility = View.GONE
            }

            // Poster — Coil with placeholder + error + fallback
            binding.poster.load(result.posterUrl?.takeIf { it.isNotBlank() }) {
                crossfade(true)
                placeholder(R.drawable.bg_poster_placeholder)
                error(R.drawable.ic_no_poster)
                fallback(R.drawable.ic_no_poster)
            }

            // Accessibility contentDescription
            val typeLabel = when (type) {
                "movie" -> binding.root.context.getString(R.string.type_movie)
                "tv" -> binding.root.context.getString(R.string.type_tv)
                else -> ""
            }
            val ratingText = rating?.let { "★ $it" } ?: ""
            binding.root.contentDescription = buildString {
                append(result.title)
                if (typeLabel.isNotEmpty()) append(", $typeLabel")
                if (!result.year.isNullOrBlank()) append(", ${result.year}")
                if (ratingText.isNotEmpty()) append(", $ratingText")
            }

            // Click
            binding.root.setOnClickListener { onItemClick(result) }
        }
    }
}
