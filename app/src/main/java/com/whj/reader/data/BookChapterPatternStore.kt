package com.whj.reader.data

import android.content.Context

/**
 * 按书 URI 记忆自定义章节目录正则；空表示使用内置自动识别。
 */
object BookChapterPatternStore {
    private const val PREF = "reader_book_chapter_pattern"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun key(uri: String) = "chpat_${uri.hashCode()}"

    fun get(ctx: Context, uri: String): String? {
        if (uri.isBlank()) return null
        return prefs(ctx).getString(key(uri), null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getIgnoreCase(ctx: Context, uri: String): Boolean {
        if (uri.isBlank()) return false
        return prefs(ctx).getBoolean("${key(uri)}_ic", false)
    }

    fun set(ctx: Context, uri: String, pattern: String?, ignoreCase: Boolean = false) {
        if (uri.isBlank()) return
        val ed = prefs(ctx).edit()
        val p = pattern?.trim()
        if (p.isNullOrEmpty()) {
            ed.remove(key(uri))
            ed.remove("${key(uri)}_ic")
        } else {
            ed.putString(key(uri), p)
            ed.putBoolean("${key(uri)}_ic", ignoreCase)
        }
        ed.apply()
    }

    fun clear(ctx: Context, uri: String) {
        set(ctx, uri, null)
    }
}
