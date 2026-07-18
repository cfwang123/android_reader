package com.whj.reader.data

import android.content.Context
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.model.ShelfItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 书架：顶层可放书 + 一层文件夹；文件夹内仅放书（不可再嵌套）。
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
                    add(
                        ShelfFolder(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            createdAt = o.optLong("createdAt", 0L),
                        ),
                    )
                }
            }.sortedBy { it.name.lowercase() }
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
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun listRoot(ctx: Context): List<ShelfItem> {
        ensureMigrated(ctx)
        val allBooks = books(ctx)
        val folderItems = folders(ctx).map { f ->
            ShelfItem.Folder(f, allBooks.count { it.folderId == f.id })
        }
        val rootBooks = allBooks
            .filter { it.folderId == null }
            .sortedByDescending { it.lastOpened }
            .map { ShelfItem.Book(it) }
        return folderItems + rootBooks
    }

    fun listInFolder(ctx: Context, folderId: String): List<ShelfItem> {
        return books(ctx)
            .filter { it.folderId == folderId }
            .sortedByDescending { it.lastOpened }
            .map { ShelfItem.Book(it) }
    }

    fun findFolder(ctx: Context, folderId: String): ShelfFolder? =
        folders(ctx).firstOrNull { it.id == folderId }

    fun findBook(ctx: Context, bookId: String): ShelfBook? =
        books(ctx).firstOrNull { it.id == bookId }

    fun findBookByUri(ctx: Context, uri: String): ShelfBook? =
        books(ctx).firstOrNull { it.uri == uri }

    /** 最近打开的一本书（按 lastOpened） */
    fun mostRecentBook(ctx: Context): ShelfBook? =
        books(ctx).maxByOrNull { it.lastOpened }?.takeIf { it.lastOpened > 0L }

    fun createFolder(ctx: Context, name: String): ShelfFolder {
        val folder = ShelfFolder(id = UUID.randomUUID().toString(), name = name.trim())
        val list = folders(ctx).toMutableList()
        list.add(folder)
        saveFolders(ctx, list)
        return folder
    }

    fun renameFolder(ctx: Context, folderId: String, name: String) {
        saveFolders(
            ctx,
            folders(ctx).map {
                if (it.id == folderId) it.copy(name = name.trim()) else it
            },
        )
    }

    fun deleteFolder(ctx: Context, folderId: String, moveBooksToRoot: Boolean = true) {
        if (moveBooksToRoot) {
            saveBooks(
                ctx,
                books(ctx).map {
                    if (it.folderId == folderId) it.copy(folderId = null) else it
                },
            )
        } else {
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
            ShelfBook(
                id = UUID.randomUUID().toString(),
                uri = uri,
                displayName = displayName,
                folderId = folderId,
                pathHint = pathHint,
                lastParagraph = lastParagraph ?: 0,
                lastOpened = System.currentTimeMillis(),
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

    fun removeBook(ctx: Context, bookId: String) {
        saveBooks(ctx, books(ctx).filterNot { it.id == bookId })
    }

    fun removeBookByUri(ctx: Context, uri: String) {
        saveBooks(ctx, books(ctx).filterNot { it.uri == uri })
    }

    fun moveBook(ctx: Context, bookId: String, targetFolderId: String?) {
        // 校验目标文件夹存在（null 为顶层）
        if (targetFolderId != null && folders(ctx).none { it.id == targetFolderId }) return
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
                list.add(
                    ShelfBook(
                        id = UUID.randomUUID().toString(),
                        uri = uri,
                        displayName = name,
                        folderId = folderId,
                        pathHint = hint,
                        lastOpened = now,
                    ),
                )
                added++
            }
        }
        saveBooks(ctx, list)
        return added
    }

    private fun saveFolders(ctx: Context, folders: List<ShelfFolder>) {
        val arr = JSONArray()
        folders.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("createdAt", f.createdAt),
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
                    .put("lastOpened", b.lastOpened),
            )
        }
        prefs(ctx).edit().putString(KEY_BOOKS, arr.toString()).apply()
    }
}
