package com.whj.reader.widget

import android.content.Context
import android.net.Uri
import com.whj.reader.R
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookEncodingStore
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.ReadingHistoryStore
import com.whj.reader.data.ReadingProgressStore

/** 解析「继续阅读」小组件展示数据：按最近阅读时间排序。 */
object ContinueReadingResolver {

    const val DEFAULT_SLOT_COUNT = 15

    data class Snapshot(
        val uri: String,
        val title: String,
        val isPdf: Boolean,
        /** 单行右侧进度，如「42%」 */
        val progressPercent: String,
        val encoding: String?,
        val lastOpened: Long,
    )

    private data class Cand(
        val uri: String,
        val title: String,
        val at: Long,
        val progress: ReadingProgressStore.Progress?,
    )

    fun resolveList(ctx: Context, limit: Int = DEFAULT_SLOT_COUNT): List<Snapshot> {
        if (limit <= 0) return emptyList()
        val cands = ArrayList<Cand>(24)
        collectCandidates(ctx, cands)
        return cands
            .groupBy { it.uri }
            .map { (_, group) ->
                group.maxBy { maxOf(it.at, it.progress?.lastOpened ?: 0L) }
            }
            .sortedByDescending { maxOf(it.at, it.progress?.lastOpened ?: 0L) }
            .take(limit)
            .map { toSnapshot(ctx, it) }
    }

    private fun collectCandidates(ctx: Context, out: MutableList<Cand>) {
        val txtUri = AppSettings.lastBookUri(ctx)
        val txtTitle = AppSettings.lastBookTitle(ctx)
        val txtAt = AppSettings.lastBookAt(ctx)
        val pdfUri = AppSettings.lastPdfUri(ctx)
        val pdfTitle = AppSettings.lastPdfTitle(ctx)
        val pdfAt = AppSettings.lastPdfAt(ctx)

        if (!txtUri.isNullOrBlank()) {
            addCand(ctx, out, txtUri, txtTitle, txtAt)
        }
        if (!pdfUri.isNullOrBlank()) {
            addCand(ctx, out, pdfUri, pdfTitle, pdfAt)
        }
        ReadingHistoryStore.listAsBooks(ctx).forEach { book ->
            addCand(ctx, out, book.uri, book.displayName, book.lastOpened)
        }
        ReadingProgressStore.exportAll(ctx).forEach { (uri, p) ->
            if (out.none { it.uri == uri }) {
                addCand(ctx, out, uri, titleForUri(ctx, uri), p.lastOpened, p)
            }
        }
    }

    private fun toSnapshot(ctx: Context, cand: Cand): Snapshot {
        val uri = cand.uri
        val progress = cand.progress ?: ReadingProgressStore.get(ctx, uri)
        val title = cand.title.ifBlank { titleForUri(ctx, uri) }.ifBlank { "—" }
        val isPdf = when {
            progress?.kind == ReadingProgressStore.Kind.PDF -> true
            else -> BookFileType.isPdfUri(ctx, Uri.parse(uri), title) ||
                BookFileType.isPdf(uri)
        }
        val opened = maxOf(cand.at, progress?.lastOpened ?: 0L)
        return Snapshot(
            uri = uri,
            title = title,
            isPdf = isPdf,
            progressPercent = formatPercent(ctx, progress),
            encoding = BookEncodingStore.get(ctx, uri),
            lastOpened = opened,
        )
    }

    private fun addCand(
        ctx: Context,
        out: MutableList<Cand>,
        uri: String,
        title: String?,
        at: Long,
        progress: ReadingProgressStore.Progress? = null,
    ) {
        if (uri.isBlank()) return
        val p = progress ?: ReadingProgressStore.get(ctx, uri)
        val opened = maxOf(at, p?.lastOpened ?: 0L)
        if (opened <= 0L && title.isNullOrBlank()) return
        val name = title?.takeIf { it.isNotBlank() }
            ?: titleForUri(ctx, uri)
        out.add(Cand(uri, name, opened, p))
    }

    private fun titleForUri(ctx: Context, uri: String): String {
        BookshelfStore.findBookByUri(ctx, uri)?.displayName?.takeIf { it.isNotBlank() }
            ?.let { return it }
        if (uri.startsWith("asset://")) {
            return uri.removePrefix("asset://").substringAfterLast('/')
        }
        return runCatching {
            Uri.parse(uri).lastPathSegment
        }.getOrNull()?.let { BookFileType.stripBookExt(it) }.orEmpty()
    }

    private fun formatPercent(
        ctx: Context,
        progress: ReadingProgressStore.Progress?,
    ): String {
        if (progress == null || progress.total <= 0) {
            return ctx.getString(R.string.widget_continue_reading_no_percent)
        }
        return ctx.getString(R.string.widget_continue_reading_percent, progress.percent())
    }
}
