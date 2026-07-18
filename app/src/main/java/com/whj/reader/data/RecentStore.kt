package com.whj.reader.data

import android.content.Context
import com.whj.reader.model.RecentFile
import org.json.JSONArray
import org.json.JSONObject

object RecentStore {
    private const val PREF = "reader_recent"
    private const val KEY = "items"
    private const val MAX = 30

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<RecentFile> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        RecentFile(
                            uri = o.getString("uri"),
                            displayName = o.optString("displayName", "未命名"),
                            lastParagraph = o.optInt("lastParagraph", 0),
                            lastOpened = o.optLong("lastOpened", 0L),
                            pathHint = o.optString("pathHint", ""),
                        ),
                    )
                }
            }.sortedByDescending { it.lastOpened }
        }.getOrDefault(emptyList())
    }

    fun touch(
        ctx: Context,
        uri: String,
        displayName: String,
        lastParagraph: Int = 0,
        pathHint: String = "",
    ) {
        val items = list(ctx).toMutableList()
        items.removeAll { it.uri == uri }
        items.add(
            0,
            RecentFile(
                uri = uri,
                displayName = displayName,
                lastParagraph = lastParagraph,
                lastOpened = System.currentTimeMillis(),
                pathHint = pathHint,
            ),
        )
        while (items.size > MAX) items.removeAt(items.lastIndex)
        save(ctx, items)
    }

    fun updateProgress(ctx: Context, uri: String, lastParagraph: Int) {
        val items = list(ctx).toMutableList()
        val idx = items.indexOfFirst { it.uri == uri }
        if (idx < 0) return
        items[idx] = items[idx].copy(
            lastParagraph = lastParagraph,
            lastOpened = System.currentTimeMillis(),
        )
        save(ctx, items)
    }

    fun remove(ctx: Context, uri: String) {
        save(ctx, list(ctx).filterNot { it.uri == uri })
    }

    private fun save(ctx: Context, items: List<RecentFile>) {
        val arr = JSONArray()
        items.forEach { f ->
            arr.put(
                JSONObject()
                    .put("uri", f.uri)
                    .put("displayName", f.displayName)
                    .put("lastParagraph", f.lastParagraph)
                    .put("lastOpened", f.lastOpened)
                    .put("pathHint", f.pathHint),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
