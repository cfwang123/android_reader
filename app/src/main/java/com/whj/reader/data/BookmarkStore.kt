package com.whj.reader.data

import android.content.Context
import com.whj.reader.model.Bookmark
import org.json.JSONArray
import org.json.JSONObject

object BookmarkStore {
    private const val PREF = "reader_bookmarks"
    private const val KEY = "items"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context, fileKey: String): List<Bookmark> {
        return all(ctx)
            .filter { it.fileKey == fileKey }
            .sortedBy { it.paragraphIndex }
    }

    fun all(ctx: Context): List<Bookmark> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Bookmark(
                            fileKey = o.getString("fileKey"),
                            paragraphIndex = o.getInt("paragraphIndex"),
                            preview = o.optString("preview", ""),
                            createdAt = o.optLong("createdAt", 0L),
                            progressPercent = o.optDouble("progressPercent", -1.0).toFloat(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, bookmark: Bookmark) {
        val items = all(ctx).toMutableList()
        items.removeAll {
            it.fileKey == bookmark.fileKey && it.paragraphIndex == bookmark.paragraphIndex
        }
        items.add(bookmark)
        save(ctx, items)
    }

    fun remove(ctx: Context, fileKey: String, paragraphIndex: Int) {
        save(
            ctx,
            all(ctx).filterNot {
                it.fileKey == fileKey && it.paragraphIndex == paragraphIndex
            },
        )
    }

    fun removeAllForFile(ctx: Context, fileKey: String) {
        if (fileKey.isBlank()) return
        save(ctx, all(ctx).filterNot { it.fileKey == fileKey })
    }

    fun has(ctx: Context, fileKey: String, paragraphIndex: Int): Boolean {
        return all(ctx).any {
            it.fileKey == fileKey && it.paragraphIndex == paragraphIndex
        }
    }

    fun replaceAll(ctx: Context, items: List<Bookmark>) {
        save(ctx, items)
    }

    private fun save(ctx: Context, items: List<Bookmark>) {
        val arr = JSONArray()
        items.forEach { b ->
            arr.put(
                JSONObject()
                    .put("fileKey", b.fileKey)
                    .put("paragraphIndex", b.paragraphIndex)
                    .put("preview", b.preview)
                    .put("createdAt", b.createdAt)
                    .put("progressPercent", b.progressPercent.toDouble()),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
