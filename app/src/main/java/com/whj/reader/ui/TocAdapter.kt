package com.whj.reader.ui

import android.view.LayoutInflater
import android.view.View
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
    private val onDeleteBookmark: ((Bookmark) -> Unit)? = null,
    /** 总段数/总页数，用于旧书签估算进度 */
    private var totalParagraphs: Int = 0,
    /** true：书签按「页」显示（PDF） */
    private val bookmarkAsPage: Boolean = false,
) : RecyclerView.Adapter<TocAdapter.VH>() {

    private var items: List<TocItem> = emptyList()
    private var currentParagraph: Int = 0

    fun submit(
        list: List<TocItem>,
        currentParagraph: Int = 0,
        totalParagraphs: Int = this.totalParagraphs,
    ) {
        items = list
        this.currentParagraph = currentParagraph
        this.totalParagraphs = totalParagraphs
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
            binding.btnTocDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = items[pos] as? TocItem.BookmarkItem ?: return@setOnClickListener
                onDeleteBookmark?.invoke(item.bookmark)
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
                    binding.btnTocDelete.visibility = View.GONE
                }
                is TocItem.BookmarkItem -> {
                    val b = item.bookmark
                    binding.tvTocTitle.text = b.preview.ifBlank {
                        itemView.context.getString(R.string.bookmark)
                    }
                    val pct = resolvePercent(b)
                    binding.tvTocIndex.text = if (bookmarkAsPage) {
                        itemView.context.getString(
                            R.string.bookmark_pos_page_pct,
                            b.paragraphIndex + 1,
                            pct,
                        )
                    } else {
                        itemView.context.getString(
                            R.string.bookmark_pos_para_pct,
                            b.paragraphIndex + 1,
                            pct,
                        )
                    }
                    binding.tvTocTitle.setTextColor(0xFF2C3E50.toInt())
                    binding.btnTocDelete.visibility =
                        if (onDeleteBookmark != null) View.VISIBLE else View.GONE
                }
            }
        }

        private fun resolvePercent(b: Bookmark): Float {
            if (b.progressPercent >= 0f) {
                return b.progressPercent.coerceIn(0f, 100f)
            }
            // 旧数据：按段索引粗估
            if (totalParagraphs <= 1) return 0f
            return ((b.paragraphIndex.toFloat() / (totalParagraphs - 1).toFloat()) * 100f)
                .coerceIn(0f, 100f)
        }
    }
}
