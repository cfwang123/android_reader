package com.whj.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.R
import com.whj.reader.databinding.ItemTocBinding
import com.whj.reader.model.Bookmark
import com.whj.reader.model.Chapter
import com.whj.reader.ui.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class TocItem {
    data class ChapterItem(val chapter: Chapter) : TocItem()
    data class BookmarkItem(val bookmark: Bookmark) : TocItem()
    data class HighlightItem(
        val highlight: com.whj.reader.model.Highlight,
        val sequence: Int = 1,
        val progressPercent: Float = 0f,
    ) : TocItem()
}

class TocAdapter(
    private val onClick: (TocItem) -> Unit,
    private val onDeleteBookmark: ((Bookmark) -> Unit)? = null,
    private val onDeleteHighlight: ((com.whj.reader.model.Highlight) -> Unit)? = null,
    /** 总段数/总页数，用于旧书签估算进度 */
    private var totalParagraphs: Int = 0,
    /** true：书签按「页」显示（PDF） */
    private val bookmarkAsPage: Boolean = false,
) : RecyclerView.Adapter<TocAdapter.VH>() {

    private val highlightTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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

    /** 当前阅读位置对应的目录项下标（含 [currentParagraph] 的章节；视口顶标题） */
    fun indexOfActiveChapter(): Int {
        for (i in items.indices) {
            val c = (items[i] as? TocItem.ChapterItem)?.chapter ?: continue
            if (c.paragraphIndex < 0) continue
            if (c.paragraphIndex > currentParagraph) break
            val nextPara = items.drop(i + 1)
                .firstNotNullOfOrNull { (it as? TocItem.ChapterItem)?.chapter?.paragraphIndex }
            if (nextPara == null || nextPara < 0 || nextPara > currentParagraph) {
                return i
            }
        }
        var active = -1
        for (i in items.indices) {
            val c = (items[i] as? TocItem.ChapterItem)?.chapter ?: continue
            if (c.paragraphIndex < 0) continue
            if (c.paragraphIndex <= currentParagraph) {
                active = i
            } else {
                break
            }
        }
        return active
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
                when (val item = items[pos]) {
                    is TocItem.BookmarkItem -> onDeleteBookmark?.invoke(item.bookmark)
                    is TocItem.HighlightItem -> onDeleteHighlight?.invoke(item.highlight)
                    else -> Unit
                }
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
                    // 当前章：段索引落在本章区间内（视口顶段所属章，同屏多标题时取上方那一章）
                    val nextPara = items.drop(bindingAdapterPosition + 1)
                        .firstNotNullOfOrNull { (it as? TocItem.ChapterItem)?.chapter?.paragraphIndex }
                    val active = c.paragraphIndex >= 0 &&
                        c.paragraphIndex <= currentParagraph &&
                        (nextPara == null || nextPara < 0 || nextPara > currentParagraph)
                    binding.tvTocTitle.setTextColor(
                        if (active) {
                            AppTheme.toolbarAccent(itemView.context)
                        } else {
                            0xFF2C3E50.toInt()
                        },
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
                is TocItem.HighlightItem -> {
                    val h = item.highlight
                    val excerpt = h.selectedText.ifBlank {
                        itemView.context.getString(R.string.highlight_no_excerpt)
                    }
                    binding.tvTocTitle.text = itemView.context.getString(
                        R.string.highlight_note_list_title,
                        item.sequence,
                        excerpt,
                    )
                    val time = if (h.createdAt > 0L) {
                        highlightTimeFmt.format(Date(h.createdAt))
                    } else {
                        "—"
                    }
                    binding.tvTocIndex.text = itemView.context.getString(
                        R.string.highlight_note_list_meta,
                        item.progressPercent.coerceIn(0f, 100f),
                        time,
                    )
                    binding.tvTocTitle.setTextColor(0xFF2C3E50.toInt())
                    binding.btnTocDelete.visibility =
                        if (onDeleteHighlight != null) View.VISIBLE else View.GONE
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
