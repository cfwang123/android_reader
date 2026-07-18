package com.whj.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.R
import com.whj.reader.databinding.ItemRecentBinding
import com.whj.reader.databinding.ItemShelfFolderBinding
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.model.ShelfItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShelfAdapter(
    private val onFolderClick: (ShelfFolder) -> Unit,
    private val onBookClick: (ShelfBook) -> Unit,
    private val onFolderLongClick: (ShelfFolder) -> Unit,
    private val onBookLongClick: (ShelfBook) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ShelfItem> = emptyList()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submit(list: List<ShelfItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ShelfItem.Folder -> TYPE_FOLDER
        is ShelfItem.Book -> TYPE_BOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderVH(ItemShelfFolderBinding.inflate(inflater, parent, false))
        } else {
            BookVH(ItemRecentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ShelfItem.Folder -> (holder as FolderVH).bind(item)
            is ShelfItem.Book -> (holder as BookVH).bind(item.book)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class FolderVH(private val binding: ItemShelfFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos] as? ShelfItem.Folder ?: return@setOnClickListener
                    onFolderClick(item.folder)
                }
            }
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos] as? ShelfItem.Folder ?: return@setOnLongClickListener false
                    onFolderLongClick(item.folder)
                }
                true
            }
        }

        fun bind(item: ShelfItem.Folder) {
            binding.tvName.text = item.folder.name
            binding.tvMeta.text = binding.root.context.getString(R.string.book_count, item.bookCount)
        }
    }

    inner class BookVH(private val binding: ItemRecentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos] as? ShelfItem.Book ?: return@setOnClickListener
                    onBookClick(item.book)
                }
            }
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos] as? ShelfItem.Book ?: return@setOnLongClickListener false
                    onBookLongClick(item.book)
                }
                true
            }
        }

        fun bind(book: ShelfBook) {
            binding.tvTitle.text = book.displayName
            binding.tvPath.text = book.pathHint.ifBlank { book.uri }
            binding.tvMeta.text = buildString {
                append(
                    itemView.context.getString(R.string.para_index, book.lastParagraph + 1),
                )
                if (book.lastOpened > 0) {
                    append(" · ")
                    append(fmt.format(Date(book.lastOpened)))
                }
            }
        }
    }

    companion object {
        private const val TYPE_FOLDER = 1
        private const val TYPE_BOOK = 2
    }
}
