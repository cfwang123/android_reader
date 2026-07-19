package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.model.Paragraph

/**
 * 书内全文搜索（TXT 段落 / PDF 按页）。
 */
object BookSearcher {

    const val MAX_RESULTS = 200
    private const val CONTEXT_RADIUS = 28

    data class Hit(
        /** TXT：段落下标；PDF：页码 0-based */
        val index: Int,
        /** 段内/页内匹配起始（字符） */
        val offset: Int,
        /** 匹配长度 */
        val length: Int,
        /** 列表左侧位置文案用：TXT 进度 0–100；PDF 页码 1-based */
        val locationLabelValue: Int,
        /** 上下文摘要 */
        val context: String,
        val isPdf: Boolean,
    )

    fun searchTxt(paragraphs: List<Paragraph>, query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty() || paragraphs.isEmpty()) return emptyList()
        val totalChars = paragraphs.sumOf { it.text.length }.coerceAtLeast(1)
        var charBefore = 0
        val out = ArrayList<Hit>(32)
        for (p in paragraphs) {
            val text = p.text
            var start = 0
            while (start < text.length) {
                val idx = text.indexOf(q, start, ignoreCase = true)
                if (idx < 0) break
                val absPos = charBefore + idx
                val pct = ((absPos.toFloat() / totalChars) * 100f).toInt().coerceIn(0, 100)
                out.add(
                    Hit(
                        index = p.index,
                        offset = idx,
                        length = q.length,
                        locationLabelValue = pct,
                        context = makeContext(text, idx, q.length),
                        isPdf = false,
                    ),
                )
                if (out.size >= MAX_RESULTS) return out
                start = idx + q.length.coerceAtLeast(1)
            }
            charBefore += text.length
        }
        return out
    }

    /**
     * 搜索 PDF：按页抽取文字后匹配。
     * [marginsForPage] 可选切边过滤。
     */
    fun searchPdf(
        context: Context,
        uri: Uri,
        query: String,
        marginsForPage: ((pageIndex: Int) -> FloatArray)? = null,
    ): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val raw = PdfTextExtractor.extractAll(context, uri)
        val extracted = if (marginsForPage != null) {
            PdfTextExtractor.filterByCrop(raw, marginsForPage)
        } else {
            raw
        }
        val out = ArrayList<Hit>(32)
        val pageTexts = LinkedHashMap<Int, String>()
        if (extracted.pageChars.isNotEmpty()) {
            for ((page, chars) in extracted.pageChars.toSortedMap()) {
                if (chars.isEmpty()) continue
                pageTexts[page] = buildString(chars.size) {
                    for (c in chars) append(c.char)
                }
            }
        } else if (extracted.paragraphs.isNotEmpty()) {
            extracted.paragraphs.forEachIndexed { i, para ->
                val page = extracted.paraLinks.getOrNull(i)?.pageIndex ?: i
                val prev = pageTexts[page].orEmpty()
                pageTexts[page] = if (prev.isEmpty()) para.text else "$prev\n${para.text}"
            }
        }
        for ((page, text) in pageTexts) {
            var start = 0
            while (start < text.length) {
                val idx = text.indexOf(q, start, ignoreCase = true)
                if (idx < 0) break
                out.add(
                    Hit(
                        index = page,
                        offset = idx,
                        length = q.length,
                        locationLabelValue = page + 1,
                        context = makeContext(text, idx, q.length),
                        isPdf = true,
                    ),
                )
                if (out.size >= MAX_RESULTS) return out
                start = idx + q.length.coerceAtLeast(1)
            }
        }
        return out
    }

    fun makeContext(text: String, start: Int, length: Int): String {
        val a = (start - CONTEXT_RADIUS).coerceAtLeast(0)
        val b = (start + length + CONTEXT_RADIUS).coerceAtMost(text.length)
        var s = text.substring(a, b)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (a > 0) s = "…$s"
        if (b < text.length) s = "$s…"
        return s
    }
}
