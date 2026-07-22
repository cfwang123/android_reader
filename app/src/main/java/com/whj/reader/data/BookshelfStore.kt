package com.whj.reader.data

import android.content.Context
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.model.ShelfFolderKind
import com.whj.reader.model.ShelfItem
import com.whj.reader.model.ShelfSort
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale
import java.util.UUID

/**
 * 书架：
 * - 顶层：书 + 虚拟书架(SHELF) + 绑定文件夹(LINKED)
 * - 虚拟书架内：仅书（不可嵌套）
 * - 绑定文件夹：内容实时读 SD/SAF，支持子目录浏览（不入库）
 */
object BookshelfStore {
    private const val PREF = "reader_bookshelf"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_BOOKS = "books"
    private const val KEY_MIGRATED = "migrated_recent"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun ensureMigrated(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val recent = RecentStore.list(ctx)
        if (recent.isNotEmpty()) {
            val books = books(ctx).toMutableList()
            recent.forEach { r ->
                if (books.none { it.uri == r.uri }) {
                    books.add(
                        ShelfBook(
                            id = UUID.randomUUID().toString(),
                            uri = r.uri,
                            displayName = r.displayName,
                            folderId = null,
                            pathHint = r.pathHint,
                            lastParagraph = r.lastParagraph,
                            lastOpened = r.lastOpened,
                            sortOrder = books.size,
                        ),
                    )
                }
            }
            saveBooks(ctx, books)
        }
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    fun folders(ctx: Context): List<ShelfFolder> {
        val raw = prefs(ctx).getString(KEY_FOLDERS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val kind = when (o.optString("kind", "SHELF").uppercase()) {
                        "LINKED" -> ShelfFolderKind.LINKED
                        else -> ShelfFolderKind.SHELF
                    }
                    val treeUri = if (o.isNull("treeUri")) {
                        null
                    } else {
                        o.optString("treeUri", "")
                            .takeIf { it.isNotEmpty() && it != "null" }
                    }
                    add(
                        ShelfFolder(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            createdAt = o.optLong("createdAt", 0L),
                            kind = kind,
                            treeUri = treeUri,
                        ),
                    )
                }
            }.sortedWith(
                compareBy<ShelfFolder> { it.kind.ordinal }
                    .thenBy { it.name.lowercase(Locale.getDefault()) },
            )
        }.getOrDefault(emptyList())
    }

    fun books(ctx: Context): List<ShelfBook> {
        val raw = prefs(ctx).getString(KEY_BOOKS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ShelfBook(
                            id = o.getString("id"),
                            uri = o.getString("uri"),
                            displayName = o.getString("displayName"),
                            folderId = if (o.isNull("folderId")) null
                            else o.optString("folderId").takeIf { it.isNotEmpty() && it != "null" },
                            pathHint = o.optString("pathHint", ""),
                            lastParagraph = o.optInt("lastParagraph", 0),
                            lastOpened = o.optLong("lastOpened", 0L),
                            sortOrder = o.optInt("sortOrder", 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun listRoot(ctx: Context, sort: ShelfSort = ShelfSort.LAST_OPENED): List<ShelfItem> {
        ensureMigrated(ctx)
        val allBooks = books(ctx)
        // 固定入口：阅读历史（置顶）
        val historyFolder = ReadingHistoryStore.folder(ctx)
        val historyItem = ShelfItem.Folder(
            historyFolder,
            ReadingHistoryStore.count(ctx),
        )
        val folderItems = folders(ctx).map { f ->
            val count = if (f.isLinked) {
                // 读整树缓存中的根目录项数
                val tree = f.treeUri
                if (tree.isNullOrBlank()) {
                    -1
                } else {
                    LinkedTreeCacheStore.load(ctx, tree)?.rootChildCount() ?: -1
                }
            } else {
                allBooks.count { it.folderId == f.id }
            }
            ShelfItem.Folder(f, count)
        }
        val rootBooks = sortBooks(allBooks.filter { it.folderId == null }, sort)
            .map { ShelfItem.Book(it) }
        return listOf(historyItem) + folderItems + rootBooks
    }

    fun listInFolder(
        ctx: Context,
        folderId: String,
        sort: ShelfSort = ShelfSort.LAST_OPENED,
    ): List<ShelfItem> {
        if (ReadingHistoryStore.isHistoryFolderId(folderId)) {
            // 历史固定按最近阅读；名称排序可选
            val books = ReadingHistoryStore.listAsBooks(ctx)
            return when (sort) {
                ShelfSort.NAME -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    books.sortedWith(compareBy(collator) { it.displayName })
                        .map { ShelfItem.Book(it) }
                }
                else -> books.map { ShelfItem.Book(it) }
            }
        }
        // 仅虚拟书架；绑定文件夹由 UI 实时扫描
        return sortBooks(books(ctx).filter { it.folderId == folderId }, sort)
            .map { ShelfItem.Book(it) }
    }

    /** 仅虚拟书架（移动书时不列出绑定目录 / 历史） */
    fun shelfFolders(ctx: Context): List<ShelfFolder> =
        folders(ctx).filter { it.kind == ShelfFolderKind.SHELF }

    private fun sortBooks(list: List<ShelfBook>, sort: ShelfSort): List<ShelfBook> {
        return when (sort) {
            ShelfSort.LAST_OPENED -> list.sortedByDescending { it.lastOpened }
            ShelfSort.NAME -> {
                val collator = Collator.getInstance(Locale.getDefault())
                list.sortedWith(compareBy(collator) { it.displayName })
            }
            ShelfSort.CUSTOM -> {
                val collator = Collator.getInstance(Locale.getDefault())
                list.sortedWith(
                    compareBy<ShelfBook> { it.sortOrder }
                        .thenByDescending { it.lastOpened }
                        .thenBy(collator) { it.displayName },
                )
            }
        }
    }

    /**
     * 保存 [folderId] 范围内书的自定义顺序（null = 顶层）。
     * [orderedIds] 为该范围内书的 id 顺序。
     */
    fun setBookOrder(ctx: Context, folderId: String?, orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val orderMap = orderedIds.withIndex().associate { it.value to it.index }
        val list = books(ctx).map { b ->
            if (b.folderId == folderId && b.id in orderMap) {
                b.copy(sortOrder = orderMap[b.id]!!)
            } else {
                b
            }
        }
        saveBooks(ctx, list)
    }

    fun removeBooks(ctx: Context, bookIds: Set<String>) {
        if (bookIds.isEmpty()) return
        saveBooks(ctx, books(ctx).filterNot { it.id in bookIds })
    }

    fun moveBooks(ctx: Context, bookIds: Set<String>, targetFolderId: String?) {
        if (bookIds.isEmpty()) return
        if (targetFolderId != null) {
            val f = findFolder(ctx, targetFolderId) ?: return
            if (f.isLinked) return
        }
        // 移入目标列表尾部
        val maxOrder = books(ctx)
            .filter { it.folderId == targetFolderId }
            .maxOfOrNull { it.sortOrder } ?: -1
        var next = maxOrder + 1
        saveBooks(
            ctx,
            books(ctx).map { b ->
                if (b.id in bookIds) {
                    b.copy(folderId = targetFolderId, sortOrder = next++)
                } else {
                    b
                }
            },
        )
    }

    fun findFolder(ctx: Context, folderId: String): ShelfFolder? {
        if (ReadingHistoryStore.isHistoryFolderId(folderId)) {
            return ReadingHistoryStore.folder(ctx)
        }
        return folders(ctx).firstOrNull { it.id == folderId }
    }

    fun findBook(ctx: Context, bookId: String): ShelfBook? =
        books(ctx).firstOrNull { it.id == bookId }

    fun findBookByUri(ctx: Context, uri: String): ShelfBook? =
        books(ctx).firstOrNull { it.uri == uri }

    /** 最近打开的一本书（按 lastOpened） */
    fun mostRecentBook(ctx: Context): ShelfBook? =
        books(ctx).maxByOrNull { it.lastOpened }?.takeIf { it.lastOpened > 0L }

    fun createFolder(ctx: Context, name: String): ShelfFolder {
        val folder = ShelfFolder(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            kind = ShelfFolderKind.SHELF,
        )
        val list = folders(ctx).toMutableList()
        list.add(folder)
        saveFolders(ctx, list)
        return folder
    }

    /** 绑定外部文件夹（SAF tree），与虚拟书架并列显示 */
    fun createLinkedFolder(ctx: Context, name: String, treeUri: String): ShelfFolder {
        val folder = ShelfFolder(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Folder" },
            kind = ShelfFolderKind.LINKED,
            treeUri = treeUri,
        )
        val list = folders(ctx).toMutableList()
        // 同一 tree 不重复绑定
        val existing = list.firstOrNull {
            it.kind == ShelfFolderKind.LINKED && it.treeUri == treeUri
        }
        if (existing != null) return existing
        list.add(folder)
        saveFolders(ctx, list)
        return folder
    }

    /**
     * 绑定文件夹失效后重新授权：更新 [treeUri]，保留同一 [folderId]。
     * 若新 tree 已被另一绑定占用，会合并到当前项并移除重复项。
     */
    fun updateLinkedTreeUri(
        ctx: Context,
        folderId: String,
        treeUri: String,
        displayName: String? = null,
    ): ShelfFolder? {
        if (treeUri.isBlank()) return null
        val list = folders(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == folderId && it.isLinked }
        if (idx < 0) return null
        val old = list[idx]
        // 其它绑定已占用同一 tree：去掉重复，保留当前 id
        val dupes = list.filter {
            it.id != folderId && it.isLinked && it.treeUri == treeUri
        }
        for (d in dupes) {
            LinkedTreeCacheStore.remove(ctx, d.treeUri)
            list.removeAll { it.id == d.id }
        }
        val stillIdx = list.indexOfFirst { it.id == folderId }
        if (stillIdx < 0) return null
        val prev = list[stillIdx]
        if (!prev.treeUri.isNullOrBlank() && prev.treeUri != treeUri) {
            LinkedTreeCacheStore.remove(ctx, prev.treeUri)
        }
        val next = prev.copy(
            treeUri = treeUri,
            name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: prev.name,
        )
        list[stillIdx] = next
        saveFolders(ctx, list)
        return next
    }

    fun renameFolder(ctx: Context, folderId: String, name: String) {
        saveFolders(
            ctx,
            folders(ctx).map {
                if (it.id == folderId) it.copy(name = name.trim()) else it
            },
        )
    }

    /**
     * 删除书架 / 解除绑定。
     * - 虚拟书架：删除书架内全部书籍记录（不移到顶层）
     * - 绑定文件夹：仅解除绑定，不动阅读进度记录
     */
    fun deleteFolder(ctx: Context, folderId: String, moveBooksToRoot: Boolean = false) {
        val folder = findFolder(ctx, folderId)
        if (folder?.isLinked == true) {
            LinkedTreeCacheStore.remove(ctx, folder.treeUri)
            saveFolders(ctx, folders(ctx).filterNot { it.id == folderId })
            return
        }
        if (moveBooksToRoot) {
            saveBooks(
                ctx,
                books(ctx).map {
                    if (it.folderId == folderId) it.copy(folderId = null) else it
                },
            )
        } else {
            // 默认：连同书籍记录一起删除
            saveBooks(ctx, books(ctx).filterNot { it.folderId == folderId })
        }
        saveFolders(ctx, folders(ctx).filterNot { it.id == folderId })
    }

    fun addOrUpdateBook(
        ctx: Context,
        uri: String,
        displayName: String,
        folderId: String? = null,
        pathHint: String = "",
        lastParagraph: Int? = null,
    ): ShelfBook {
        val list = books(ctx).toMutableList()
        val existing = list.indexOfFirst { it.uri == uri }
        val book = if (existing >= 0) {
            val old = list[existing]
            old.copy(
                displayName = displayName.ifBlank { old.displayName },
                folderId = folderId ?: old.folderId,
                pathHint = pathHint.ifBlank { old.pathHint },
                lastParagraph = lastParagraph ?: old.lastParagraph,
                lastOpened = System.currentTimeMillis(),
            ).also { list[existing] = it }
        } else {
            val maxOrder = list.filter { it.folderId == folderId }.maxOfOrNull { it.sortOrder } ?: -1
            ShelfBook(
                id = UUID.randomUUID().toString(),
                uri = uri,
                displayName = displayName,
                folderId = folderId,
                pathHint = pathHint,
                lastParagraph = lastParagraph ?: 0,
                lastOpened = System.currentTimeMillis(),
                sortOrder = maxOrder + 1,
            ).also { list.add(0, it) }
        }
        saveBooks(ctx, list)
        return book
    }

    fun updateProgress(ctx: Context, uri: String, lastParagraph: Int) {
        val list = books(ctx).toMutableList()
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx < 0) return
        list[idx] = list[idx].copy(
            lastParagraph = lastParagraph,
            lastOpened = System.currentTimeMillis(),
        )
        saveBooks(ctx, list)
    }

    /**
     * 仅当书架上已有该书时更新显示名/进度/时间，**不新增**记录。
     * 绑定文件夹打开的书不应进入主书架。
     */
    fun updateIfExists(
        ctx: Context,
        uri: String,
        displayName: String? = null,
        lastParagraph: Int? = null,
    ): ShelfBook? {
        val list = books(ctx).toMutableList()
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx < 0) return null
        val old = list[idx]
        val next = old.copy(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: old.displayName,
            lastParagraph = lastParagraph ?: old.lastParagraph,
            lastOpened = System.currentTimeMillis(),
        )
        list[idx] = next
        saveBooks(ctx, list)
        return next
    }

    /** 更新某本书的 URI（外部打开后改为本地副本时保留进度） */
    fun updateBookUri(ctx: Context, bookId: String, newUri: String) {
        saveBooks(
            ctx,
            books(ctx).map {
                if (it.id == bookId) it.copy(uri = newUri, lastOpened = System.currentTimeMillis())
                else it
            },
        )
    }

    fun removeBook(ctx: Context, bookId: String) {
        saveBooks(ctx, books(ctx).filterNot { it.id == bookId })
    }

    fun removeBookByUri(ctx: Context, uri: String) {
        saveBooks(ctx, books(ctx).filterNot { it.uri == uri })
    }

    /** 阅读历史删除时：保留书架条目，但 lastOpened=0 使历史列表不再收录 */
    fun clearLastOpenedForUri(ctx: Context, uri: String) {
        if (uri.isBlank()) return
        saveBooks(
            ctx,
            books(ctx).map {
                if (it.uri == uri) it.copy(lastOpened = 0L, lastParagraph = 0) else it
            },
        )
    }

    fun moveBook(ctx: Context, bookId: String, targetFolderId: String?) {
        // 只能移入虚拟书架（null = 顶层）
        if (targetFolderId != null) {
            val f = findFolder(ctx, targetFolderId) ?: return
            if (f.isLinked) return
        }
        saveBooks(
            ctx,
            books(ctx).map {
                if (it.id == bookId) it.copy(folderId = targetFolderId) else it
            },
        )
    }

    fun addBooksBatch(
        ctx: Context,
        items: List<Triple<String, String, String>>,
        folderId: String?,
    ): Int {
        if (items.isEmpty()) return 0
        val list = books(ctx).toMutableList()
        var added = 0
        val now = System.currentTimeMillis()
        items.forEach { (uri, name, hint) ->
            val idx = list.indexOfFirst { it.uri == uri }
            if (idx >= 0) {
                list[idx] = list[idx].copy(
                    displayName = name.ifBlank { list[idx].displayName },
                    folderId = folderId,
                    pathHint = hint.ifBlank { list[idx].pathHint },
                    lastOpened = now,
                )
            } else {
                val maxOrder = list.filter { it.folderId == folderId }.maxOfOrNull { it.sortOrder } ?: -1
                list.add(
                    ShelfBook(
                        id = UUID.randomUUID().toString(),
                        uri = uri,
                        displayName = name,
                        folderId = folderId,
                        pathHint = hint,
                        lastOpened = now,
                        sortOrder = maxOrder + 1,
                    ),
                )
                added++
            }
        }
        saveBooks(ctx, list)
        return added
    }

    /** 导入备份时整表替换书架与绑定文件夹 */
    fun replaceAllData(ctx: Context, folders: List<ShelfFolder>, books: List<ShelfBook>) {
        saveFolders(ctx, folders)
        saveBooks(ctx, books)
        prefs(ctx).edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun saveFolders(ctx: Context, folders: List<ShelfFolder>) {
        val arr = JSONArray()
        folders.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("createdAt", f.createdAt)
                    .put("kind", f.kind.name)
                    .put("treeUri", f.treeUri ?: JSONObject.NULL),
            )
        }
        prefs(ctx).edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }

    private fun saveBooks(ctx: Context, books: List<ShelfBook>) {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("uri", b.uri)
                    .put("displayName", b.displayName)
                    .put("folderId", b.folderId)
                    .put("pathHint", b.pathHint)
                    .put("lastParagraph", b.lastParagraph)
                    .put("lastOpened", b.lastOpened)
                    .put("sortOrder", b.sortOrder),
            )
        }
        // commit：清除记录等路径立即对 UI 可见
        prefs(ctx).edit().putString(KEY_BOOKS, arr.toString()).commit()
    }
}
