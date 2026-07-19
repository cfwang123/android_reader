package com.whj.reader.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.R
import com.whj.reader.data.BookEncodingStore
import com.whj.reader.data.BookFileType
import com.whj.reader.data.ReadingProgressStore
import com.whj.reader.data.ShelfFileMetaStore
import com.whj.reader.databinding.ItemRecentBinding
import com.whj.reader.databinding.ItemShelfFolderBinding
import com.whj.reader.model.LinkedDirEntry
import com.whj.reader.model.LinkedFileEntry
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.model.ShelfItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ShelfAdapter(
    private val onFolderClick: (ShelfFolder) -> Unit,
    private val onBookClick: (ShelfBook) -> Unit,
    private val onLinkedDirClick: (LinkedDirEntry) -> Unit,
    private val onLinkedFileClick: (LinkedFileEntry) -> Unit,
    private val onFolderLongClick: (ShelfFolder) -> Unit,
    /** 长按书架书：弹出菜单（锚点 View） */
    private val onBookLongPress: (ShelfBook, View) -> Unit,
    /** 长按绑定目录内文件：弹出菜单（如编码） */
    private val onLinkedFileLongPress: (LinkedFileEntry, View) -> Unit,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ShelfItem> = emptyList()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val sizeCache = ConcurrentHashMap<String, Long>()

    private var dropHighlightFolderId: String? = null
    private var selectionMode: Boolean = false
    private var selectedIds: Set<String> = emptySet()
    private var showDragHandle: Boolean = false

    fun submit(list: List<ShelfItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun currentItems(): List<ShelfItem> = items

    fun setItemsSilently(list: List<ShelfItem>) {
        items = list
    }

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in items.indices || to !in items.indices) return
        val mutable = items.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        items = mutable
        notifyItemMoved(from, to)
    }

    fun setSelectionState(
        enabled: Boolean,
        selected: Set<String>,
        dragEnabled: Boolean,
    ) {
        val changed = selectionMode != enabled ||
            selectedIds != selected ||
            showDragHandle != dragEnabled
        selectionMode = enabled
        selectedIds = selected
        showDragHandle = dragEnabled
        if (changed) notifyDataSetChanged()
    }

    fun getItem(position: Int): ShelfItem? =
        items.getOrNull(position)

    fun indexOfUri(uri: String): Int {
        if (uri.isBlank()) return -1
        val target = normalizeUri(uri)
        return items.indexOfFirst { item ->
            when (item) {
                is ShelfItem.Book -> urisMatch(item.book.uri, uri, target)
                is ShelfItem.LinkedFile -> urisMatch(item.entry.uri, uri, target)
                else -> false
            }
        }
    }

    private fun normalizeUri(u: String): String =
        u.trim().trimEnd('/').lowercase(Locale.ROOT)

    private fun urisMatch(a: String, raw: String, normalizedRaw: String): Boolean {
        if (a == raw) return true
        if (normalizeUri(a) == normalizedRaw) return true
        val sa = a.substringAfterLast('/')
        val sb = raw.substringAfterLast('/')
        return sa.isNotEmpty() && sa == sb
    }

    fun setDropHighlight(folderId: String?) {
        if (dropHighlightFolderId == folderId) return
        val old = dropHighlightFolderId
        dropHighlightFolderId = folderId
        items.forEachIndexed { index, item ->
            if (item is ShelfItem.Folder) {
                if (item.folder.id == old || item.folder.id == folderId) {
                    notifyItemChanged(index)
                }
            }
        }
    }

    fun clearDropHighlight() {
        setDropHighlight(null)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ShelfItem.Folder, is ShelfItem.LinkedDir -> TYPE_FOLDER
        is ShelfItem.Book, is ShelfItem.LinkedFile -> TYPE_BOOK
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
            is ShelfItem.Folder -> (holder as FolderVH).bindShelf(item)
            is ShelfItem.LinkedDir -> (holder as FolderVH).bindLinkedDir(item.entry)
            is ShelfItem.Book -> (holder as BookVH).bindBook(item.book)
            is ShelfItem.LinkedFile -> (holder as BookVH).bindLinked(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class FolderVH(private val binding: ItemShelfFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                if (selectionMode) return@setOnClickListener
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                when (val item = items[pos]) {
                    is ShelfItem.Folder -> onFolderClick(item.folder)
                    is ShelfItem.LinkedDir -> onLinkedDirClick(item.entry)
                    else -> Unit
                }
            }
            binding.root.setOnLongClickListener {
                if (selectionMode) return@setOnLongClickListener false
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val item = items[pos] as? ShelfItem.Folder ?: return@setOnLongClickListener false
                onFolderLongClick(item.folder)
                true
            }
        }

        fun bindShelf(item: ShelfItem.Folder) {
            binding.tvName.text = item.folder.name
            binding.tvMeta.text = folderMetaText(item.folder, item.childCount)
            val highlighted = dropHighlightFolderId == item.folder.id &&
                !item.folder.isLinked &&
                !item.folder.isHistory
            if (highlighted) {
                binding.root.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.primary_soft),
                )
            } else {
                binding.root.setBackgroundResource(R.drawable.bg_round_card)
            }
        }

        fun bindLinkedDir(entry: LinkedDirEntry) {
            binding.tvName.text = entry.name
            binding.tvMeta.text = folderMetaText(
                isLinkedRoot = false,
                childCount = entry.childCount,
                isHistory = false,
            )
            binding.root.setBackgroundResource(R.drawable.bg_round_card)
        }

        private fun folderMetaText(folder: com.whj.reader.model.ShelfFolder, childCount: Int): String =
            folderMetaText(
                isLinkedRoot = folder.isLinked,
                childCount = childCount,
                isHistory = folder.isHistory,
            )

        private fun folderMetaText(
            isLinkedRoot: Boolean,
            childCount: Int,
            isHistory: Boolean,
        ): String {
            val ctx = binding.root.context
            if (isHistory) {
                return if (childCount > 0) {
                    ctx.getString(R.string.history_folder_meta, childCount)
                } else {
                    ctx.getString(R.string.history_folder_hint)
                }
            }
            // childCount < 0：未统计（绑定目录为加速不逐项 count）
            return when {
                childCount < 0 && isLinkedRoot -> ctx.getString(R.string.linked_folder_meta)
                childCount < 0 -> ctx.getString(R.string.folder_meta_no_count)
                isLinkedRoot -> ctx.getString(R.string.linked_folder_count, childCount)
                else -> ctx.getString(R.string.item_count, childCount)
            }
        }
    }

    inner class BookVH(private val binding: ItemRecentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                when (val item = items[pos]) {
                    is ShelfItem.Book -> onBookClick(item.book)
                    is ShelfItem.LinkedFile -> {
                        if (!selectionMode) onLinkedFileClick(item.entry)
                    }
                    else -> Unit
                }
            }
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                if (selectionMode) return@setOnLongClickListener false
                when (val item = items[pos]) {
                    is ShelfItem.Book -> {
                        onBookLongPress(item.book, binding.root)
                        true
                    }
                    is ShelfItem.LinkedFile -> {
                        onLinkedFileLongPress(item.entry, binding.root)
                        true
                    }
                    else -> false
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        fun bindBook(book: ShelfBook) {
            val isPdf = BookFileType.isPdf(book.pathHint) ||
                BookFileType.isPdf(book.displayName) ||
                BookFileType.isPdf(book.uri)
            binding.ivFileType.setImageResource(
                if (isPdf) R.drawable.ic_file_pdf else R.drawable.ic_file_txt,
            )
            binding.tvTitle.text = fullFileName(book.displayName, book.pathHint, isPdf)
            binding.tvPath.visibility = View.GONE
            val (meta, pct) = resolveProgress(
                uri = book.uri,
                displayName = book.displayName,
                pathHint = book.pathHint,
                shelfFallback = book,
                forcePdf = isPdf,
                knownSizeBytes = -1L,
            )
            binding.tvMeta.text = meta
            bindProgressBar(pct)
            binding.root.alpha = 1f

            val selected = selectionMode && selectedIds.contains(book.id)
            if (selectionMode) {
                binding.ivCheck.visibility = View.VISIBLE
                binding.ivCheck.setImageResource(
                    if (selected) R.drawable.ic_check_box else R.drawable.ic_check_box_outline,
                )
                binding.root.setBackgroundColor(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (selected) R.color.primary_soft else R.color.white,
                    ),
                )
            } else {
                binding.ivCheck.visibility = View.GONE
                binding.root.setBackgroundResource(R.drawable.bg_round_card)
            }

            val dragOn = showDragHandle && selectionMode
            binding.ivDragHandle.visibility = if (dragOn) View.VISIBLE else View.GONE
            if (dragOn) {
                binding.ivDragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag?.invoke(this)
                    }
                    false
                }
            } else {
                binding.ivDragHandle.setOnTouchListener(null)
            }
        }

        fun bindLinked(item: ShelfItem.LinkedFile) {
            val entry = item.entry
            binding.ivFileType.setImageResource(
                if (entry.isPdf) R.drawable.ic_file_pdf else R.drawable.ic_file_txt,
            )
            binding.tvTitle.text = entry.name.ifBlank {
                fullFileName(entry.displayName, entry.name, entry.isPdf)
            }
            // 搜索命中子目录文件时显示相对路径
            val rel = entry.relativePath.trim()
            if (rel.isNotEmpty() && rel != entry.name && rel.contains('/')) {
                binding.tvPath.visibility = View.VISIBLE
                binding.tvPath.text = rel
            } else {
                binding.tvPath.visibility = View.GONE
            }
            val (meta, pct) = resolveProgress(
                uri = entry.uri,
                displayName = entry.displayName,
                pathHint = entry.name,
                shelfFallback = item.progress,
                forcePdf = entry.isPdf,
                knownSizeBytes = entry.sizeBytes,
            )
            binding.tvMeta.text = meta
            bindProgressBar(pct)
            binding.root.alpha = 1f
            binding.ivCheck.visibility = View.GONE
            binding.ivDragHandle.visibility = View.GONE
            binding.ivDragHandle.setOnTouchListener(null)
            binding.root.setBackgroundResource(R.drawable.bg_round_card)
        }

        private fun fullFileName(displayName: String, pathHint: String, isPdf: Boolean): String {
            fun extractFileName(raw: String): String? {
                if (raw.isBlank()) return null
                val decoded = runCatching {
                    java.net.URLDecoder.decode(raw, Charsets.UTF_8.name())
                }.getOrDefault(raw)
                var s = decoded.substringBefore('?').substringBefore('#')
                s = s.substringAfterLast('/').substringAfterLast('\\')
                if (s.contains(':') && !s.startsWith("content:")) {
                    s = s.substringAfterLast(':')
                    s = s.substringAfterLast('/')
                }
                s = s.trim()
                if (s.isBlank() || s == "null") return null
                if (s.contains('%') && s.length > 40) return null
                return s
            }

            val fromHint = extractFileName(pathHint)
            if (fromHint != null && (fromHint.contains('.') || fromHint.length in 1..200)) {
                return if (fromHint.contains('.')) {
                    fromHint
                } else {
                    fromHint + if (isPdf) ".pdf" else ".txt"
                }
            }
            val fromDisplay = extractFileName(displayName) ?: displayName.ifBlank { "book" }
            if (fromDisplay.contains('.')) return fromDisplay
            return fromDisplay + if (isPdf) ".pdf" else ".txt"
        }

        private fun bindProgressBar(percent: Int) {
            val bar = binding.progressRead
            val p = percent.coerceIn(0, 100)
            bar.progress = p
            bar.visibility = if (p > 0) View.VISIBLE else View.INVISIBLE
        }

        private fun resolveProgress(
            uri: String,
            displayName: String,
            pathHint: String,
            shelfFallback: ShelfBook?,
            forcePdf: Boolean? = null,
            knownSizeBytes: Long = -1L,
        ): Pair<String, Int> {
            val ctx = itemView.context
            val store = ReadingProgressStore.get(ctx, uri)
            val isPdf = when {
                forcePdf != null -> forcePdf
                store != null -> store.kind == ReadingProgressStore.Kind.PDF
                else -> BookFileType.isPdf(pathHint) || BookFileType.isPdf(displayName)
            }

            val lastOpened = when {
                store != null && store.lastOpened > 0 -> store.lastOpened
                shelfFallback != null && shelfFallback.lastOpened > 0 -> shelfFallback.lastOpened
                else -> 0L
            }

            val cachedPages = if (isPdf) {
                when {
                    (store?.total ?: 0) > 0 -> store!!.total
                    else -> ShelfFileMetaStore.getPdfPageCount(ctx, uri)
                }
            } else {
                store?.total ?: 0
            }
            val position = store?.position
                ?: shelfFallback?.lastParagraph
                ?: 0

            val sizeBytes = resolveSizeBytes(uri, knownSizeBytes)
            val sizeText = if (sizeBytes >= 0) {
                ctx.getString(R.string.shelf_file_size, humanSize(sizeBytes))
            } else {
                ""
            }
            var pct = 0
            val progressText = if (isPdf) {
                val total = cachedPages
                if (total > 0) {
                    val page = (position + 1).coerceIn(1, total)
                    pct = ((page * 100f) / total).toInt().coerceIn(0, 100)
                    ctx.getString(R.string.shelf_progress_pdf_pct, page, total, pct)
                } else if (position > 0) {
                    ctx.getString(R.string.shelf_progress_pdf, position + 1, 0)
                } else {
                    ""
                }
            } else {
                val total = store?.total ?: 0
                if (total > 0) {
                    pct = ((position.coerceAtLeast(0) * 100f) / total).toInt().coerceIn(0, 100)
                    ctx.getString(R.string.shelf_progress_txt, pct)
                } else {
                    ""
                }
            }
            // 非 UTF-8（手动或打开时记忆）在书架副标题展示编码
            val encodingLabel = if (!isPdf) {
                encodingShelfLabel(ctx, uri)
            } else {
                null
            }
            val meta = buildString {
                if (sizeText.isNotEmpty()) append(sizeText)
                if (encodingLabel != null) {
                    if (isNotEmpty()) append(" · ")
                    append(encodingLabel)
                }
                if (lastOpened > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(ctx.getString(R.string.shelf_last_read, fmt.format(Date(lastOpened))))
                }
                if (progressText.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(progressText)
                }
            }
            return meta to pct
        }

        /** 仅非 UTF-8 显示，UTF-8 / 自动不占位 */
        private fun encodingShelfLabel(ctx: android.content.Context, uri: String): String? {
            val enc = BookEncodingStore.get(ctx, uri) ?: return null
            if (enc.equals("UTF-8", ignoreCase = true)) return null
            return ctx.getString(R.string.encoding_label, enc)
        }

        /**
         * 优先用列表已带 size / 本地缓存；**主线程不做 ContentResolver query**
         *（绑定目录切换时对每个文件 query 会卡约数百毫秒）。
         */
        private fun resolveSizeBytes(uri: String, knownSizeBytes: Long): Long {
            if (knownSizeBytes >= 0L) {
                sizeCache[uri] = knownSizeBytes
                return knownSizeBytes
            }
            sizeCache[uri]?.let { return it }
            val cached = ShelfFileMetaStore.getSizeBytes(itemView.context, uri)
            if (cached >= 0) {
                sizeCache[uri] = cached
                return cached
            }
            return -1L
        }

        private fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.getDefault(), "%.1f MB", mb)
        }
    }

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_BOOK = 1
    }
}
