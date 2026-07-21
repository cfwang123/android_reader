package com.whj.reader.data

import android.content.Context
import org.json.JSONObject

/**
 * 按 URI 记录阅读进度（不依赖是否在书架列表里）。
 * 绑定文件夹内的书也可显示进度，且不必写入主书架。
 */
object ReadingProgressStore {
    private const val PREF = "reader_reading_progress"

    enum class Kind { TXT, PDF }

    data class Progress(
        val lastOpened: Long,
        /** 0-based：TXT 为段落索引，PDF 为页码；漫画模式为图片索引 */
        val position: Int,
        /** 总量：TXT 段数，PDF 页数，漫画为图片数；未知为 0 */
        val total: Int,
        val kind: Kind,
        /**
         * 展示用扩展名（含点，如 `.mobi`），解决 content URI 无后缀时历史列表误标成 .txt。
         */
        val fileExt: String = "",
    ) {
        /** 0–100 进度百分比 */
        fun percent(): Int {
            if (total <= 0) return 0
            // 按「已读到当前位置」估算：第 1 页/段 → 约 0% 附近，末项 → 100%
            if (total == 1) return if (position > 0) 100 else 0
            return (((position + 1).toFloat() / total) * 100f).toInt().coerceIn(0, 100)
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun key(uri: String) = "p_${uri.hashCode()}"

    private fun parseProgress(o: JSONObject): Progress =
        Progress(
            lastOpened = o.optLong("lastOpened", 0L),
            position = o.optInt("position", 0).coerceAtLeast(0),
            total = o.optInt("total", 0).coerceAtLeast(0),
            kind = when (o.optString("kind", "TXT").uppercase()) {
                "PDF" -> Kind.PDF
                else -> Kind.TXT
            },
            fileExt = o.optString("fileExt", "").orEmpty(),
        )

    fun get(ctx: Context, uri: String): Progress? {
        if (uri.isBlank()) return null
        val raw = prefs(ctx).getString(key(uri), null) ?: return null
        return runCatching {
            parseProgress(JSONObject(raw))
        }.getOrNull()
    }

    fun remove(ctx: Context, uri: String) {
        if (uri.isBlank()) return
        prefs(ctx).edit().remove(key(uri)).apply()
    }

    fun saveTxt(
        ctx: Context,
        uri: String,
        paragraphIndex: Int,
        totalParagraphs: Int,
        fileExt: String? = null,
    ) {
        if (uri.isBlank()) return
        val prev = get(ctx, uri)
        val ext = fileExt?.takeIf { it.isNotBlank() }
            ?: prev?.fileExt?.takeIf { it.isNotBlank() }
            ?: BookFileType.extensionOf(uri).orEmpty()
        write(
            ctx,
            uri,
            Progress(
                lastOpened = System.currentTimeMillis(),
                position = paragraphIndex.coerceAtLeast(0),
                total = totalParagraphs.coerceAtLeast(0),
                kind = Kind.TXT,
                fileExt = ext,
            ),
        )
    }

    fun savePdf(ctx: Context, uri: String, pageIndex: Int, totalPages: Int) {
        if (uri.isBlank()) return
        val pages = totalPages.coerceAtLeast(0)
        if (pages > 0) {
            ShelfFileMetaStore.setPdfPageCount(ctx, uri, pages)
        }
        val prev = get(ctx, uri)
        write(
            ctx,
            uri,
            Progress(
                lastOpened = System.currentTimeMillis(),
                position = pageIndex.coerceAtLeast(0),
                total = pages,
                kind = Kind.PDF,
                fileExt = prev?.fileExt?.takeIf { it.isNotBlank() } ?: ".pdf",
            ),
        )
    }

    private fun write(ctx: Context, uri: String, p: Progress) {
        val o = JSONObject()
            .put("lastOpened", p.lastOpened)
            .put("position", p.position)
            .put("total", p.total)
            .put("kind", p.kind.name)
            .put("uri", uri)
            .put("fileExt", p.fileExt)
        prefs(ctx).edit().putString(key(uri), o.toString()).apply()
    }

    /**
     * 重新选文件后迁移进度：新 URI 尚无记录时写入旧进度。
     */
    fun migrate(ctx: Context, oldUri: String, newUri: String) {
        if (oldUri.isBlank() || newUri.isBlank() || oldUri == newUri) return
        val p = get(ctx, oldUri) ?: return
        if (get(ctx, newUri) != null) return
        write(
            ctx,
            newUri,
            p.copy(lastOpened = System.currentTimeMillis()),
        )
    }

    /** 导出全部阅读进度（含 URI） */
    fun exportAll(ctx: Context): List<Pair<String, Progress>> {
        val out = ArrayList<Pair<String, Progress>>()
        prefs(ctx).all.forEach { (_, value) ->
            if (value !is String) return@forEach
            runCatching {
                val o = JSONObject(value)
                val uri = o.optString("uri", "")
                if (uri.isBlank()) return@runCatching
                out.add(uri to parseProgress(o))
            }
        }
        return out
    }

    /** 导入时整表替换 */
    fun replaceAll(ctx: Context, items: List<Pair<String, Progress>>) {
        val ed = prefs(ctx).edit().clear()
        items.forEach { (uri, p) ->
            if (uri.isBlank()) return@forEach
            val o = JSONObject()
                .put("lastOpened", p.lastOpened)
                .put("position", p.position)
                .put("total", p.total)
                .put("kind", p.kind.name)
                .put("uri", uri)
                .put("fileExt", p.fileExt)
            ed.putString(key(uri), o.toString())
        }
        ed.apply()
    }
}
