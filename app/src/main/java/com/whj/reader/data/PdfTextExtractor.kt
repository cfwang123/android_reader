package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.whj.reader.model.Paragraph
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 从 PDF 提取可朗读文字与带坐标的字符（选字 / TTS 高亮）。
 *
 * 坐标：左上原点，Y 向下（pdfbox-android getXDirAdj/getYDirAdj）。
 */
object PdfTextExtractor {

    private const val TAG = "PdfTextExtractor"

    /** 章节/小节标题：001. 第一章、二、宇宙之心、第3节 等 */
    private val TITLE_LINE = Regex(
        """^(\d{1,4}[\.、．]\s*.{0,40}|第[0-9一二三四五六七八九十百千零〇两]+[章节回部卷节篇].{0,30}|[一二三四五六七八九十百千]+[、．.].{0,30})$""",
    )

    data class PdfChar(
        val pageIndex: Int,
        val indexOnPage: Int,
        val char: Char,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val pageWidth: Float,
        val pageHeight: Float,
    ) {
        val midX: Float get() = (left + right) / 2f
        val midY: Float get() = (top + bottom) / 2f
        fun contains(pageX: Float, pageY: Float, pad: Float = 3f): Boolean {
            return pageX >= left - pad && pageX <= right + pad &&
                pageY >= top - pad && pageY <= bottom + pad
        }
    }

    data class ParaLink(
        val pageIndex: Int,
        val charStart: Int,
        val charEnd: Int,
    )

    data class Extracted(
        val paragraphs: List<Paragraph>,
        val pageChars: Map<Int, List<PdfChar>>,
        val paraLinks: List<ParaLink>,
        /** 未分段、未切边过滤的原始字符（用于重新切边） */
        val rawPageChars: Map<Int, List<PdfChar>> = pageChars,
    )

    @Volatile
    private var inited = false

    /** 阅读会话：PDF 已 load 在内存，抽字不再整本重读 */
    private val sessionLock = Any()
    private var sessionKey: String? = null
    private var sessionDoc: PDDocument? = null

    fun ensureInit(context: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            PDFBoxResourceLoader.init(context.applicationContext)
            inited = true
        }
    }

    /**
     * 打开/保持当前阅读 PDF 的 PDFBox 文档（读入内存）。
     * 与 [android.graphics.pdf.PdfRenderer] 独立；阅读页 onDestroy 时 [closeSession]。
     */
    fun openSession(context: Context, uri: Uri): Boolean {
        ensureInit(context)
        val key = uri.toString()
        synchronized(sessionLock) {
            if (sessionKey == key && sessionDoc != null) return true
            closeSessionLocked()
            return try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return false
                sessionDoc = PDDocument.load(ByteArrayInputStream(bytes))
                sessionKey = key
                Log.i(TAG, "session open pages=${sessionDoc?.numberOfPages} bytes=${bytes.size}")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "openSession failed", t)
                closeSessionLocked()
                false
            }
        }
    }

    fun closeSession() {
        synchronized(sessionLock) {
            closeSessionLocked()
        }
    }

    private fun closeSessionLocked() {
        runCatching { sessionDoc?.close() }
        sessionDoc = null
        sessionKey = null
    }

    /** 当前会话是否已为该 uri 打开 */
    fun hasSession(uri: Uri): Boolean {
        synchronized(sessionLock) {
            return sessionKey == uri.toString() && sessionDoc != null
        }
    }

    fun extractAll(context: Context, uri: Uri): Extracted {
        ensureInit(context)
        return withDocument(context, uri) { doc ->
            val n = doc.numberOfPages
            if (n <= 0) return@withDocument Extracted(emptyList(), emptyMap(), emptyList())
            val pages = (0 until n).toList()
            val rawPageChars = extractPageChars(doc, pages)
            buildFromRaw(rawPageChars) { floatArrayOf(0f, 0f, 0f, 0f) }
        } ?: Extracted(emptyList(), emptyMap(), emptyList())
    }

    /**
     * 仅提取指定页的原始字符（0-based）。用于 TTS/选字懒加载。
     * 若已 [openSession]，复用内存中的 PDDocument，避免反复 load。
     */
    fun extractPagesRaw(
        context: Context,
        uri: Uri,
        pageIndices: Collection<Int>,
    ): Map<Int, List<PdfChar>> {
        val wanted = pageIndices.filter { it >= 0 }.distinct().sorted()
        if (wanted.isEmpty()) return emptyMap()
        ensureInit(context)
        return withDocument(context, uri) { doc ->
            val n = doc.numberOfPages
            val pages = wanted.filter { it < n }
            if (pages.isEmpty()) emptyMap() else extractPageChars(doc, pages)
        } ?: emptyMap()
    }

    /**
     * 在持有 session 锁下使用文档。优先会话内文档；否则临时 load（并尽量升级为 session）。
     */
    private fun <T> withDocument(context: Context, uri: Uri, block: (PDDocument) -> T): T? {
        val key = uri.toString()
        synchronized(sessionLock) {
            val existing = sessionDoc
            if (sessionKey == key && existing != null) {
                return try {
                    block(existing)
                } catch (t: Throwable) {
                    Log.w(TAG, "withDocument session failed", t)
                    null
                }
            }
            // 无会话：读入并建立 session，供后续抽页复用
            return try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return null
                closeSessionLocked()
                val doc = PDDocument.load(ByteArrayInputStream(bytes))
                sessionDoc = doc
                sessionKey = key
                Log.i(TAG, "session open(lazy) pages=${doc.numberOfPages} bytes=${bytes.size}")
                block(doc)
            } catch (t: Throwable) {
                Log.w(TAG, "withDocument load failed", t)
                closeSessionLocked()
                null
            }
        }
    }

    private fun extractPageChars(
        doc: PDDocument,
        pageIndices: List<Int>,
    ): Map<Int, List<PdfChar>> {
        val rawPageChars = HashMap<Int, List<PdfChar>>(pageIndices.size)
        for (pageIndex in pageIndices) {
            val page1 = pageIndex + 1
            val collector = CharCollector(pageIndex)
            collector.startPage = page1
            collector.endPage = page1
            collector.sortByPosition = true
            runCatching { collector.getText(doc) }
                .onFailure { Log.w(TAG, "extract page $pageIndex failed", it) }
            // 即使无字也占位，避免反复尝试空页
            rawPageChars[pageIndex] = collector.chars.toList()
        }
        return rawPageChars
    }

    fun extractParagraphs(context: Context, uri: Uri): List<Paragraph> =
        extractAll(context, uri).paragraphs

    fun extractCharsByPage(context: Context, uri: Uri): Map<Int, List<PdfChar>> =
        extractAll(context, uri).pageChars

    /**
     * 按切边过滤并重建段落。
     * [source.rawPageChars] 优先；若无则用 pageChars。
     */
    fun filterByCrop(
        source: Extracted,
        marginsForPage: (pageIndex: Int) -> FloatArray,
    ): Extracted {
        val raw = if (source.rawPageChars.isNotEmpty()) source.rawPageChars else source.pageChars
        if (raw.isEmpty()) return source
        return buildFromRaw(raw, marginsForPage)
    }

    /** 从已缓存的原始页字符构建（可只含部分页） */
    fun buildFromCachedPages(
        rawPageChars: Map<Int, List<PdfChar>>,
        marginsForPage: (pageIndex: Int) -> FloatArray,
    ): Extracted {
        if (rawPageChars.isEmpty()) {
            return Extracted(emptyList(), emptyMap(), emptyList(), emptyMap())
        }
        return buildFromRaw(rawPageChars, marginsForPage)
    }

    /**
     * 从原始页字符：切边过滤 → 智能分段 → 段落列表。
     * 任一步失败时回退为「整页一段」，避免「未提取到可朗读文字」。
     */
    private fun buildFromRaw(
        rawPageChars: Map<Int, List<PdfChar>>,
        marginsForPage: (pageIndex: Int) -> FloatArray,
    ): Extracted {
        val pageChars = HashMap<Int, List<PdfChar>>()
        val paragraphs = ArrayList<Paragraph>()
        val links = ArrayList<ParaLink>()
        var paraIdx = 0

        for ((pageIndex, raw) in rawPageChars.toSortedMap()) {
            if (raw.isEmpty()) continue
            val m = marginsForPage(pageIndex)
            val cl = m.getOrElse(0) { 0f }.coerceIn(0f, 0.35f)
            val ct = m.getOrElse(1) { 0f }.coerceIn(0f, 0.35f)
            val cr = m.getOrElse(2) { 0f }.coerceIn(0f, 0.35f)
            val cb = m.getOrElse(3) { 0f }.coerceIn(0f, 0.35f)

            var visible = filterVisible(raw, cl, ct, cr, cb)
            // 切边过猛导致全灭时，回退为本页全部文字（仍可朗读）
            if (visible.isEmpty()) {
                visible = raw.mapIndexed { i, c -> c.copy(indexOnPage = i) }
            }

            val (normalized, blocks) = runCatching { smartSegment(visible) }
                .getOrElse {
                    Log.w(TAG, "smartSegment failed page=$pageIndex", it)
                    emptyList<PdfChar>() to emptyList()
                }

            val useChars: List<PdfChar>
            val useBlocks: List<Pair<Int, Int>>
            if (normalized.isNotEmpty() && blocks.isNotEmpty()) {
                useChars = normalized
                useBlocks = blocks
            } else {
                // 回退：整页一段
                useChars = visible.mapIndexed { i, c -> c.copy(indexOnPage = i) }
                useBlocks = if (useChars.isEmpty()) emptyList() else listOf(0 to useChars.size)
            }

            if (useChars.isEmpty()) continue
            pageChars[pageIndex] = useChars
            for ((start, end) in useBlocks) {
                if (start >= end) continue
                var s = start
                var e = end
                while (s < e && useChars[s].char.isWhitespace()) s++
                while (e > s && useChars[e - 1].char.isWhitespace()) e--
                if (s >= e) continue
                val text = buildString {
                    for (i in s until e) append(useChars[i].char)
                }
                if (text.isBlank()) continue
                paragraphs.add(Paragraph(paraIdx, text, isChapter = false))
                links.add(ParaLink(pageIndex, s, e))
                paraIdx++
            }
        }

        // 仍无段落但有字符：每页硬拼一段
        if (paragraphs.isEmpty() && pageChars.isNotEmpty()) {
            for ((pageIndex, chars) in pageChars.toSortedMap()) {
                val text = chars.joinToString("") { it.char.toString() }.trim()
                if (text.isEmpty()) continue
                paragraphs.add(Paragraph(paraIdx, text, isChapter = false))
                links.add(ParaLink(pageIndex, 0, chars.size))
                paraIdx++
            }
        }

        return Extracted(paragraphs, pageChars, links, rawPageChars)
    }

    private fun filterVisible(
        raw: List<PdfChar>,
        cl: Float,
        ct: Float,
        cr: Float,
        cb: Float,
    ): List<PdfChar> {
        if (cl <= 0.001f && ct <= 0.001f && cr <= 0.001f && cb <= 0.001f) {
            return raw.mapIndexed { i, c -> c.copy(indexOnPage = i) }
        }
        val out = ArrayList<PdfChar>(raw.size)
        var idx = 0
        for (c in raw) {
            val pw = c.pageWidth.coerceAtLeast(1f)
            val ph = c.pageHeight.coerceAtLeast(1f)
            val left = pw * cl
            val top = ph * ct
            val right = pw * (1f - cr)
            val bottom = ph * (1f - cb)
            if (c.midX < left || c.midX > right || c.midY < top || c.midY > bottom) continue
            out.add(c.copy(indexOnPage = idx++))
        }
        return out
    }

    /**
     * 智能分段：
     * - 同字号、正常行距的换行 → 同一段（行尾不断句）
     * - 字号明显不同 → 新段（标题不并正文）
     * - 空行级大间距 → 新段
     */
    private fun smartSegment(raw: List<PdfChar>): Pair<List<PdfChar>, List<Pair<Int, Int>>> {
        if (raw.isEmpty()) return emptyList<PdfChar>() to emptyList()
        val content = raw.filter { !it.char.isWhitespace() || it.char == ' ' }
        if (content.isEmpty()) {
            // 全是奇怪空白：仍尽量保留
            val re = raw.mapIndexed { i, c -> c.copy(indexOnPage = i) }
            return re to listOf(0 to re.size)
        }

        val heights = content.map { (it.bottom - it.top).coerceAtLeast(1f) }
        val avgH = heights.average().toFloat().coerceAtLeast(1f)
        val lineTol = avgH * 0.5f
        val lines = ArrayList<List<PdfChar>>()
        var curLine = ArrayList<PdfChar>()
        var curMidY = content[0].midY
        for (c in content.sortedWith(compareBy({ it.midY }, { it.left }))) {
            if (curLine.isEmpty()) {
                curLine.add(c)
                curMidY = c.midY
                continue
            }
            if (abs(c.midY - curMidY) <= lineTol) {
                curLine.add(c)
                curMidY = curLine.map { it.midY }.average().toFloat()
            } else {
                lines.add(curLine.sortedBy { it.left })
                curLine = arrayListOf(c)
                curMidY = c.midY
            }
        }
        if (curLine.isNotEmpty()) lines.add(curLine.sortedBy { it.left })
        if (lines.isEmpty()) {
            val re = content.mapIndexed { i, c -> c.copy(indexOnPage = i) }
            return re to listOf(0 to re.size)
        }

        data class LineSeg(
            val chars: List<PdfChar>,
            val midY: Float,
            val fontH: Float,
            val text: String,
            val pageW: Float,
        ) {
            val inkCount: Int get() = text.count { !it.isWhitespace() }
            val spanW: Float
                get() {
                    val ink = chars.filter { !it.char.isWhitespace() }
                    if (ink.isEmpty()) return 0f
                    return ink.maxOf { it.right } - ink.minOf { it.left }
                }
        }
        val segs = lines.map { line ->
            val ink = line.filter { !it.char.isWhitespace() }
            val fh = if (ink.isEmpty()) {
                avgH
            } else {
                ink.map { (it.bottom - it.top).coerceAtLeast(1f) }.average().toFloat()
            }
            val text = ink.joinToString("") { it.char.toString() }
            val pw = line.firstOrNull()?.pageWidth?.coerceAtLeast(1f) ?: 1f
            LineSeg(line, line.map { it.midY }.average().toFloat(), fh, text, pw)
        }

        // 正文主导字号 = 中位数（标题往往偏大/偏小且行少）
        val fontList = segs.map { it.fontH }.sorted()
        val bodyFont = fontList[fontList.size / 2].coerceAtLeast(1f)

        val steps = ArrayList<Float>()
        for (i in 1 until segs.size) {
            val d = segs[i].midY - segs[i - 1].midY
            if (d > avgH * 0.3f) steps.add(d)
        }
        steps.sort()
        val normalStep = if (steps.isEmpty()) avgH * 1.2f else steps[steps.size / 2]
        val paraBreakStep = normalStep * 1.55f
        // 字号差 ≥12% 即分段（标题常略大于正文）
        val fontBreakRatio = 1.12f

        fun isTitleLike(seg: LineSeg): Boolean {
            val t = seg.text.trim()
            if (t.isEmpty()) return false
            // 相对正文的字号差
            val fr = max(seg.fontH, bodyFont) / min(seg.fontH, bodyFont)
            if (fr >= fontBreakRatio) return true
            // 章节标题样式
            if (TITLE_LINE.matches(t)) return true
            // 短行且明显未占满行宽（标题常见）
            val fill = seg.spanW / seg.pageW
            if (seg.inkCount in 1..24 && fill < 0.72f && !t.last().let { isSentenceEndChar(it) || it == '，' || it == '、' }) {
                // 下一行更像正文时，本行当标题
                return true
            }
            return false
        }

        val paraLineGroups = ArrayList<List<LineSeg>>()
        var group = ArrayList<LineSeg>()
        for (i in segs.indices) {
            if (group.isEmpty()) {
                group.add(segs[i])
                continue
            }
            val prev = group.last()
            val cur = segs[i]
            val step = cur.midY - prev.midY
            val fontRatio = max(prev.fontH, cur.fontH) /
                min(prev.fontH, cur.fontH).coerceAtLeast(0.1f)
            val prevTitle = isTitleLike(prev)
            val curTitle = isTitleLike(cur)
            // 标题行单独成段：不与上一段/下一段合并
            val breakHere = step > paraBreakStep ||
                fontRatio >= fontBreakRatio ||
                prevTitle ||
                (curTitle && group.isNotEmpty())
            if (breakHere) {
                paraLineGroups.add(group)
                group = arrayListOf(cur)
            } else {
                group.add(cur)
            }
        }
        if (group.isNotEmpty()) paraLineGroups.add(group)

        val outChars = ArrayList<PdfChar>()
        val blocks = ArrayList<Pair<Int, Int>>()
        val blockFontH = ArrayList<Float>()
        for (g in paraLineGroups) {
            val start = outChars.size
            val gFont = g.map { it.fontH }.average().toFloat()
            for (li in g.indices) {
                val line = g[li].chars
                if (line.isEmpty()) continue
                if (outChars.isNotEmpty() && li > 0) {
                    while (outChars.isNotEmpty() && outChars.last().char.isWhitespace()) {
                        outChars.removeAt(outChars.lastIndex)
                    }
                    val nextInk = line.firstOrNull { !it.char.isWhitespace() }
                    if (outChars.isNotEmpty() && nextInk != null) {
                        val prevCh = outChars.last().char
                        if (shouldInsertSpaceBetween(prevCh, nextInk.char)) {
                            val p = outChars.last()
                            outChars.add(
                                p.copy(
                                    indexOnPage = outChars.size,
                                    char = ' ',
                                    left = p.right,
                                    right = p.right + (p.bottom - p.top) * 0.25f,
                                ),
                            )
                        }
                    }
                }
                for ((ci, c) in line.withIndex()) {
                    if (c.char == '\n' || c.char == '\r') continue
                    if ((c.char == '-' || c.char == '‐' || c.char == '‑') &&
                        ci == line.lastIndex && li < g.lastIndex
                    ) {
                        continue
                    }
                    outChars.add(c.copy(indexOnPage = outChars.size))
                }
            }
            val end = outChars.size
            if (end > start) {
                blocks.add(start to end)
                blockFontH.add(gFont)
            }
        }

        if (outChars.isEmpty()) {
            val re = content.mapIndexed { i, c -> c.copy(indexOnPage = i) }
            return re to listOf(0 to re.size)
        }
        if (blocks.isEmpty()) {
            return outChars.mapIndexed { i, c -> c.copy(indexOnPage = i) } to
                listOf(0 to outChars.size)
        }

        // 软合并：仅同字号、非标题、未句末的正文跨段（同字号换行）
        val blockIsTitle = blocks.mapIndexed { bi, (s, e) ->
            val t = buildString {
                for (i in s until e) {
                    if (!outChars[i].char.isWhitespace()) append(outChars[i].char)
                }
            }
            val fh = blockFontH.getOrElse(bi) { bodyFont }
            val fr = max(fh, bodyFont) / min(fh, bodyFont)
            fr >= fontBreakRatio || TITLE_LINE.matches(t) || (t.length in 1..24 && !t.any { isSentenceEndChar(it) })
        }
        val merged = mergeSoftBlocks(outChars, blocks, blockFontH, blockIsTitle)
        val normalized = outChars.mapIndexed { i, c -> c.copy(indexOnPage = i) }
        return normalized to merged
    }

    private fun mergeSoftBlocks(
        chars: List<PdfChar>,
        blocks: List<Pair<Int, Int>>,
        blockFontH: List<Float>,
        blockIsTitle: List<Boolean>,
    ): List<Pair<Int, Int>> {
        if (blocks.size <= 1) return blocks
        val out = ArrayList<Pair<Int, Int>>()
        var curS = blocks[0].first
        var curE = blocks[0].second
        var curFont = blockFontH.getOrElse(0) { 1f }
        var curTitle = blockIsTitle.getOrElse(0) { false }
        for (i in 1 until blocks.size) {
            val (s, e) = blocks[i]
            val nextFont = blockFontH.getOrElse(i) { curFont }
            val nextTitle = blockIsTitle.getOrElse(i) { false }
            val fontRatio = max(curFont, nextFont) / min(curFont, nextFont).coerceAtLeast(0.1f)
            val sameFont = fontRatio < 1.12f
            val prevLast = (curE - 1).downTo(curS).firstOrNull { !chars[it].char.isWhitespace() }
            val endsSentence = prevLast != null && isSentenceEndChar(chars[prevLast].char)
            // 标题段绝不与上下合并；字号不同也不合并
            if (!endsSentence && sameFont && !curTitle && !nextTitle) {
                curE = e
                curFont = max(curFont, nextFont)
            } else {
                out.add(curS to curE)
                curS = s
                curE = e
                curFont = nextFont
                curTitle = nextTitle
            }
        }
        out.add(curS to curE)
        return out
    }

    private fun isSentenceEndChar(ch: Char): Boolean =
        ch == '。' || ch == '！' || ch == '？' || ch == '．' ||
            ch == '!' || ch == '?' || ch == '…' || ch == '」' || ch == '』' ||
            ch == '"' || ch == '\u201D'

    private fun shouldInsertSpaceBetween(prev: Char, next: Char): Boolean {
        if (prev.isWhitespace() || next.isWhitespace()) return false
        if (isCjk(prev) && isCjk(next)) return false
        if (isCjk(prev) && !isCjk(next)) return false
        if (!isCjk(prev) && isCjk(next)) return false
        if (next in "，。！？、；：”’）】》,%)]}.,!?;:'\"") return false
        if (prev in "（【《“‘([{\"'") return false
        if (prev.isLetterOrDigit() && next.isLetterOrDigit()) return true
        if (prev in ",.;:!?%" && next.isLetterOrDigit()) return true
        return false
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF ||
            c in 0x3400..0x4DBF ||
            c in 0x3000..0x303F ||
            c in 0xFF00..0xFFEF ||
            c in 0x3040..0x30FF ||
            c in 0xAC00..0xD7AF
    }

    private class CharCollector(
        private val pageIndex: Int,
    ) : PDFTextStripper() {
        val chars = ArrayList<PdfChar>(512)
        private var idx = 0

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                val s = tp.unicode ?: continue
                if (s.isEmpty()) continue
                val pageW = tp.pageWidth.coerceAtLeast(1f)
                val pageH = tp.pageHeight.coerceAtLeast(1f)
                val x0 = tp.xDirAdj
                val baselineFromTop = tp.yDirAdj
                val wAll = tp.widthDirAdj.coerceAtLeast(1f)
                val h = max(tp.heightDir, tp.fontSizeInPt * 0.45f).coerceAtLeast(1f)
                val top = (baselineFromTop - h).coerceIn(0f, pageH)
                val bottom = baselineFromTop.coerceIn(0f, pageH)
                val charW = wAll / s.length.coerceAtLeast(1)
                for (i in s.indices) {
                    val ch = s[i]
                    if (ch == '\n' || ch == '\r' || ch == '\t') continue
                    val cLeft = x0 + charW * i
                    val cRight = cLeft + charW
                    val pad = charW * 0.04f
                    chars.add(
                        PdfChar(
                            pageIndex = pageIndex,
                            indexOnPage = idx++,
                            char = ch,
                            left = (cLeft + pad).coerceIn(0f, pageW),
                            top = top,
                            right = (cRight - pad).coerceIn(0f, pageW),
                            bottom = if (bottom > top) bottom else (top + h).coerceAtMost(pageH),
                            pageWidth = pageW,
                            pageHeight = pageH,
                        ),
                    )
                }
            }
        }
    }
}
