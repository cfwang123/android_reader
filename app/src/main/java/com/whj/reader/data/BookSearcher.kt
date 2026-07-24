package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.model.Chapter
import com.whj.reader.model.Paragraph

/**
 * 书内全文搜索（TXT 段落 / PDF 按页，含 OCR 缓存）。
 * 支持边搜边回调，便于 UI 实时展示。
 */
object BookSearcher {

    const val MAX_RESULTS = 200
    private const val CONTEXT_RADIUS = 28

    enum class SearchScope {
        /** 全书 */
        FULL,
        /** TXT/EPUB/MOBI：当前章 */
        CURRENT_CHAPTER,
        /** PDF：当前页 */
        CURRENT_PAGE,
        /** PDF：仅已 OCR 的页 */
        OCR_PAGES,
    }

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
        /** PDF：命中来自 OCR 缓存 */
        val fromOcr: Boolean = false,
    )

    fun searchTxt(paragraphs: List<Paragraph>, query: String): List<Hit> {
        val out = ArrayList<Hit>(32)
        searchTxtStreaming(paragraphs, query, isActive = { true }) { hit ->
            out.add(hit)
            out.size < MAX_RESULTS
        }
        return out
    }

    /**
     * 流式搜索 TXT。
     * [onHit] 返回 false 时停止；[isActive] 为 false 时中止（取消搜索）。
     */
    fun searchTxtStreaming(
        paragraphs: List<Paragraph>,
        query: String,
        scope: SearchScope = SearchScope.FULL,
        currentParagraph: Int = 0,
        chapters: List<Chapter> = emptyList(),
        isActive: () -> Boolean = { true },
        onHit: (Hit) -> Boolean,
    ): Int {
        val q = query.trim()
        if (q.isEmpty() || paragraphs.isEmpty()) return 0
        val range = if (scope == SearchScope.CURRENT_CHAPTER) {
            chapterParagraphRange(chapters, currentParagraph, paragraphs.lastIndex)
        } else {
            null
        }
        val searchable = if (range != null) {
            paragraphs.filter { it.index in range }
        } else {
            paragraphs
        }
        if (searchable.isEmpty()) return 0
        val totalChars = paragraphs.sumOf { it.text.length }.coerceAtLeast(1)
        var charBefore = 0
        var count = 0
        for (p in paragraphs) {
            if (!isActive()) return count
            if (range != null && p.index !in range) {
                charBefore += p.text.length
                continue
            }
            val text = p.text
            var start = 0
            while (start < text.length) {
                if (!isActive()) return count
                val idx = text.indexOf(q, start, ignoreCase = true)
                if (idx < 0) break
                val absPos = charBefore + idx
                val pct = ((absPos.toFloat() / totalChars) * 100f).toInt().coerceIn(0, 100)
                val hit = Hit(
                    index = p.index,
                    offset = idx,
                    length = q.length,
                    locationLabelValue = pct,
                    context = makeContext(text, idx, q.length),
                    isPdf = false,
                )
                count++
                if (!onHit(hit) || count >= MAX_RESULTS) return count
                start = idx + q.length.coerceAtLeast(1)
            }
            charBefore += text.length
        }
        return count
    }

    /**
     * 搜索 PDF：文字层 + OCR 缓存（按 [scope] 限定页）。
     * [onHit] 非空时边搜边回调，返回 false 停止。
     */
    fun searchPdf(
        context: Context,
        uri: Uri,
        fileKey: String,
        query: String,
        scope: SearchScope = SearchScope.FULL,
        currentPage: Int = 0,
        marginsForPage: ((pageIndex: Int) -> FloatArray)? = null,
        isActive: () -> Boolean = { true },
        onHit: ((Hit) -> Boolean)? = null,
    ): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (!isActive()) return emptyList()

        val pageCount = PdfTextExtractor.pageCount(context, uri).coerceAtLeast(0)
        val pagesToScan: List<Int> = when (scope) {
            SearchScope.CURRENT_PAGE -> {
                if (pageCount <= 0) emptyList()
                else listOf(currentPage.coerceIn(0, pageCount - 1))
            }
            SearchScope.OCR_PAGES -> {
                PdfOcrCacheStore.loadIndex(context, fileKey)
                    .filter { (_, meta) -> meta.charCount > 0 }
                    .keys
                    .sorted()
            }
            else -> if (pageCount <= 0) emptyList() else (0 until pageCount).toList()
        }
        if (pagesToScan.isEmpty()) return emptyList()

        val nativeByPage = when (scope) {
            SearchScope.OCR_PAGES -> emptyMap()
            SearchScope.CURRENT_PAGE -> {
                val raw = PdfTextExtractor.extractPagesRaw(context, uri, pagesToScan)
                val cropped = if (marginsForPage != null) {
                    PdfTextExtractor.filterByCrop(
                        PdfTextExtractor.Extracted(
                            emptyList(),
                            raw,
                            emptyList(),
                            raw,
                        ),
                        marginsForPage,
                    ).pageChars
                } else {
                    raw
                }
                buildNativePageTexts(cropped)
            }
            else -> {
                val raw = PdfTextExtractor.extractAll(context, uri)
                if (!isActive()) return emptyList()
                val extracted = if (marginsForPage != null) {
                    PdfTextExtractor.filterByCrop(raw, marginsForPage)
                } else {
                    raw
                }
                buildNativePageTexts(
                    if (extracted.pageChars.isNotEmpty()) extracted.pageChars
                    else extracted.rawPageChars,
                    fromParagraphs = extracted,
                )
            }
        }

        val out = ArrayList<Hit>(32)
        for (page in pagesToScan) {
            if (!isActive()) return out
            val (text, fromOcr) = pageSearchText(
                context = context,
                fileKey = fileKey,
                page = page,
                nativeText = nativeByPage[page],
                scope = scope,
            )
            if (text.isEmpty()) continue
            var start = 0
            while (start < text.length) {
                if (!isActive()) return out
                val idx = text.indexOf(q, start, ignoreCase = true)
                if (idx < 0) break
                val hit = Hit(
                    index = page,
                    offset = idx,
                    length = q.length,
                    locationLabelValue = page + 1,
                    context = makeContext(text, idx, q.length),
                    isPdf = true,
                    fromOcr = fromOcr,
                )
                out.add(hit)
                if (onHit != null && !onHit(hit)) return out
                if (out.size >= MAX_RESULTS) return out
                start = idx + q.length.coerceAtLeast(1)
            }
        }
        return out
    }

    /** 当前章段落下标范围（含首尾）；无章节时 null */
    fun chapterParagraphRange(
        chapters: List<Chapter>,
        currentParagraph: Int,
        lastParagraphIndex: Int,
    ): IntRange? {
        val starts = chapters.map { it.paragraphIndex }.filter { it >= 0 }.distinct().sorted()
        if (starts.isEmpty()) return null
        val chIdx = starts.indexOfLast { it <= currentParagraph }.let { if (it < 0) 0 else it }
        val start = starts[chIdx]
        val end = starts.getOrNull(chIdx + 1)?.minus(1) ?: lastParagraphIndex
        return start..end.coerceAtLeast(start)
    }

    private fun buildNativePageTexts(
        pageChars: Map<Int, List<PdfTextExtractor.PdfChar>>,
        fromParagraphs: PdfTextExtractor.Extracted? = null,
    ): Map<Int, String> {
        val pageTexts = LinkedHashMap<Int, String>()
        if (pageChars.isNotEmpty()) {
            for ((page, chars) in pageChars.toSortedMap()) {
                if (chars.isEmpty()) continue
                pageTexts[page] = buildString(chars.size) {
                    for (c in chars) append(c.char)
                }
            }
        } else if (fromParagraphs != null && fromParagraphs.paragraphs.isNotEmpty()) {
            fromParagraphs.paragraphs.forEachIndexed { i, para ->
                val page = fromParagraphs.paraLinks.getOrNull(i)?.pageIndex ?: i
                val prev = pageTexts[page].orEmpty()
                pageTexts[page] = if (prev.isEmpty()) para.text else "$prev\n${para.text}"
            }
        }
        return pageTexts
    }

    private fun pageSearchText(
        context: Context,
        fileKey: String,
        page: Int,
        nativeText: String?,
        scope: SearchScope,
    ): Pair<String, Boolean> {
        when (scope) {
            SearchScope.OCR_PAGES -> {
                val chars = PdfOcrCacheStore.loadPage(context, fileKey, page) ?: return "" to true
                return chars.joinToString("") { it.char.toString() } to true
            }
            else -> {
                val native = nativeText?.trim().orEmpty()
                if (native.isNotEmpty()) return native to false
                val chars = PdfOcrCacheStore.loadPage(context, fileKey, page) ?: return "" to false
                val ocr = chars.joinToString("") { it.char.toString() }
                return ocr to ocr.isNotEmpty()
            }
        }
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
