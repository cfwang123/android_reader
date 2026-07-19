package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.R
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.model.ShelfFolderKind
import com.whj.reader.model.ShelfItem
import java.net.URLDecoder

/**
 * 书架根目录「阅读历史」：
 * 聚合 [ReadingProgressStore] / 书架 / 最近打开，按时间取最近 [MAX] 条。
 * 虚拟文件夹，不写入书架 folders 配置。
 */
object ReadingHistoryStore {
    const val FOLDER_ID = "__reading_history__"
    const val MAX = 100

    fun isHistoryFolderId(id: String?): Boolean =
        id == FOLDER_ID

    fun folder(ctx: Context): ShelfFolder =
        ShelfFolder(
            id = FOLDER_ID,
            name = ctx.getString(R.string.reading_history),
            createdAt = 0L,
            kind = ShelfFolderKind.HISTORY,
        )

    fun count(ctx: Context): Int =
        listAsBooks(ctx, MAX).size

    fun listItems(ctx: Context, limit: Int = MAX): List<ShelfItem> =
        listAsBooks(ctx, limit).map { ShelfItem.Book(it) }

    /**
     * 最近阅读记录 → [ShelfBook]（id 为 hist_ 前缀，勿当作真实书架 id 删除）。
     */
    fun listAsBooks(ctx: Context, limit: Int = MAX): List<ShelfBook> {
        data class Acc(
            var lastOpened: Long,
            var position: Int,
            var displayName: String?,
            var pathHint: String?,
        )

        val map = LinkedHashMap<String, Acc>()

        fun merge(
            uri: String,
            lastOpened: Long,
            position: Int = 0,
            displayName: String? = null,
            pathHint: String? = null,
        ) {
            if (uri.isBlank() || lastOpened <= 0L) return
            if (uri.startsWith("asset://")) return
            val cur = map[uri]
            if (cur == null) {
                map[uri] = Acc(
                    lastOpened = lastOpened,
                    position = position.coerceAtLeast(0),
                    displayName = displayName?.takeIf { it.isNotBlank() },
                    pathHint = pathHint?.takeIf { it.isNotBlank() },
                )
            } else {
                if (lastOpened >= cur.lastOpened) {
                    cur.lastOpened = lastOpened
                    if (position > 0) cur.position = position
                }
                if (!displayName.isNullOrBlank()) cur.displayName = displayName
                if (!pathHint.isNullOrBlank()) cur.pathHint = pathHint
            }
        }

        ReadingProgressStore.exportAll(ctx).forEach { (uri, p) ->
            merge(uri, p.lastOpened, p.position)
        }
        BookshelfStore.books(ctx).forEach { b ->
            merge(
                uri = b.uri,
                lastOpened = b.lastOpened,
                position = b.lastParagraph,
                displayName = b.displayName,
                pathHint = b.pathHint,
            )
        }
        RecentStore.list(ctx).forEach { r ->
            merge(
                uri = r.uri,
                lastOpened = r.lastOpened,
                position = r.lastParagraph,
                displayName = r.displayName,
                pathHint = r.pathHint,
            )
        }
        AppSettings.lastBookUri(ctx)?.let { uri ->
            val title = AppSettings.lastBookTitle(ctx).takeIf { it.isNotBlank() }
            val existing = map[uri]
            if (existing != null) {
                if (title != null) existing.displayName = title
            } else if (title != null) {
                merge(
                    uri = uri,
                    lastOpened = System.currentTimeMillis(),
                    displayName = title,
                )
            }
        }

        return map.entries
            .sortedByDescending { it.value.lastOpened }
            .take(limit.coerceAtLeast(0))
            .mapIndexed { index, (uri, acc) ->
                val name = acc.displayName?.takeIf { it.isNotBlank() }
                    ?: displayNameFromUri(uri)
                val hint = acc.pathHint?.takeIf { it.isNotBlank() } ?: name
                ShelfBook(
                    id = "hist_${uri.hashCode()}_${index}",
                    uri = uri,
                    displayName = BookFileType.stripBookExt(name),
                    folderId = FOLDER_ID,
                    pathHint = hint,
                    lastParagraph = acc.position,
                    lastOpened = acc.lastOpened,
                    sortOrder = index,
                )
            }
    }

    private fun displayNameFromUri(uri: String): String {
        val raw = runCatching {
            URLDecoder.decode(uri, Charsets.UTF_8.name())
        }.getOrDefault(uri)
        var s = raw.substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('/').substringAfterLast('\\')
        if (s.contains(':') && !s.startsWith("content:")) {
            s = s.substringAfterLast(':')
            s = s.substringAfterLast('/')
        }
        s = s.trim()
        return if (s.isBlank() || s == "null") {
            Uri.parse(uri).lastPathSegment ?: "book"
        } else {
            s
        }
    }
}
