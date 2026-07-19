package com.whj.reader.data

import android.content.Context

/**
 * 书架文件元数据缓存：大小、PDF 总页数。
 * 避免列表反复 query ContentResolver；PDF 打开后写入页数供列表显示「当前页/总页数」。
 */
object ShelfFileMetaStore {
    private const val PREF = "reader_shelf_file_meta"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun sizeKey(uri: String) = "sz_${uri.hashCode()}"
    private fun pagesKey(uri: String) = "pg_${uri.hashCode()}"

    /** 缓存的文件字节数；未知返回 -1 */
    fun getSizeBytes(ctx: Context, uri: String): Long {
        if (uri.isBlank()) return -1L
        val v = prefs(ctx).getLong(sizeKey(uri), -1L)
        return if (v >= 0L) v else -1L
    }

    fun setSizeBytes(ctx: Context, uri: String, bytes: Long) {
        if (uri.isBlank() || bytes < 0L) return
        prefs(ctx).edit().putLong(sizeKey(uri), bytes).apply()
    }

    /** 仅当与缓存不同才写入，避免列表每次枚举狂刷 SharedPreferences */
    fun setSizeBytesIfChanged(ctx: Context, uri: String, bytes: Long) {
        if (uri.isBlank() || bytes < 0L) return
        val old = getSizeBytes(ctx, uri)
        if (old == bytes) return
        setSizeBytes(ctx, uri, bytes)
    }

    /** 缓存的 PDF 总页数；未知或未打开过返回 0 */
    fun getPdfPageCount(ctx: Context, uri: String): Int {
        if (uri.isBlank()) return 0
        return prefs(ctx).getInt(pagesKey(uri), 0).coerceAtLeast(0)
    }

    fun setPdfPageCount(ctx: Context, uri: String, pageCount: Int) {
        if (uri.isBlank() || pageCount <= 0) return
        prefs(ctx).edit().putInt(pagesKey(uri), pageCount).apply()
    }
}
