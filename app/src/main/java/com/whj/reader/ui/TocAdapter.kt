package com.whj.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.R
import com.whj.reader.databinding.ItemTocBinding
import com.whj.reader.model.Bookmark
import com.whj.reader.model.Chapter

sealed class TocItem {
    data class ChapterItem(val chapter: Chapter) : TocItem()
    data class BookmarkItem(val bookmark: Bookmark) : TocItem()
}

class TocAdapter(
    private val onClick: (TocItem) -> Unit,
) : RecyclerView.Adapter<TocAdapter.VH>() {

    private var items: List<TocItem> = emptyList()
    private var currentParagraph: Int = 0

    fun submit(list: List<TocItem>, currentParagraph: Int = 0) {
        items = list
        this.currentParagraph = currentParagraph
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTocBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemTocBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }

        fun bind(item: TocItem) {
            when (item) {
                is TocItem.ChapterItem -> {
                    val c = item.chapter
                    binding.tvTocTitle.text = c.title
                    binding.tvTocIndex.text = itemView.context.getString(
                        R.string.para_index,
                        c.paragraphIndex + 1,
                    )
                    val active = c.paragraphIndex <= currentParagraph &&
                        (
                            bindingAdapterPosition == items.lastIndex ||
                                (items.getOrNull(bindingAdapterPosition + 1) as? TocItem.ChapterItem)
                                    ?.chapter?.paragraphIndex?.let { it > currentParagraph } == true
                            )
                    binding.tvTocTitle.setTextColor(
                        if (active) 0xFFF0A020.toInt() else 0xFF2C3E50.toInt(),
                    )
                }
                is TocItem.BookmarkItem -> {
                    val b = item.bookmark
                    binding.tvTocTitle.text = b.preview
                    binding.tvTocIndex.text = itemView.context.getString(
                        R.string.para_index,
                        b.paragraphIndex + 1,
                    )
                    binding.tvTocTitle.setTextColor(0xFF2C3E50.toInt())
                }
            }
        }
    }
}
