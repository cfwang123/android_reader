package com.whj.reader.data

import android.graphics.Color
import com.whj.reader.model.ImageDisplaySize
import com.whj.reader.model.TextAlign
import com.whj.reader.model.TextSpanStyle
import java.util.Locale

/**
 * EPUB/MOBI 用 HTML 子集解析 → 段落 + 富文本 span + 图片。
 *
 * 支持：
 * - 块：p/div/h1-h6/br/li/tr/blockquote/section/article/td/th → 分段
 * - 对齐：text-align / align（left/center/right/start/end）
 * - 列表：ul/ol/li（• / 1. 2. …）
 * - 表格：简单 tr/td/th → 按列对齐的多行（等宽），th 加粗
 * - pre：保留空白与换行，[ParsedBlock.preformatted]
 * - 行内：b/strong、i/em、u、font color、span style 的 color/background[-color]
 * - a href：链接样式（下划线 + 默认蓝），href 写入 [TextSpanStyle.linkHref]
 * - id/name：锚点，写入 [ParsedBlock.anchors] 供跳转
 * - img：整行图 / 行内图
 * - 颜色/背景：仅当前标签一层（栈顶），不合成多层
 */
object HtmlRichParser {

    /** 对象替换符，占位行内图 */
    const val OBJ_CHAR = '\uFFFC'
    /** 默认链接色 */
    val DEFAULT_LINK_COLOR: Int = 0xFF1565C0.toInt()

    data class InlineImageRef(
        val start: Int,
        val end: Int,
        val src: String,
        val displaySize: ImageDisplaySize? = null,
    )

    data class AnchorRef(
        val offset: Int,
        val id: String,
    )

    data class ParsedBlock(
        val text: String = "",
        val spans: List<TextSpanStyle> = emptyList(),
        val isChapter: Boolean = false,
        /** 整行/块级 img src；非空时 text 一般为空 */
        val imageSrc: String? = null,
        val imageDisplaySize: ImageDisplaySize? = null,
        /** 行内图（相对 [text] 偏移） */
        val inlineImages: List<InlineImageRef> = emptyList(),
        /** 段内锚点（相对 [text] 偏移） */
        val anchors: List<AnchorRef> = emptyList(),
        val align: TextAlign = TextAlign.START,
        val preformatted: Boolean = false,
    )

    private data class StyleFrame(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val color: Int? = null,
        val backgroundColor: Int? = null,
        val linkHref: String? = null,
    )

    private data class ListFrame(
        val ordered: Boolean,
        var nextIndex: Int = 1,
    )

    private class Builder {
        val blocks = ArrayList<ParsedBlock>(256)
        private val text = StringBuilder()
        private val openSpans = ArrayList<OpenSpan>()
        private val finishedSpans = ArrayList<TextSpanStyle>()
        private val pendingInline = ArrayList<InlineImageRef>()
        private val pendingAnchors = ArrayList<AnchorRef>()
        private val styleStack = ArrayList<StyleFrame>()
        private var chapterDepth = 0
        /** 当前块对齐（开块标签时更新，flush 时写入） */
        var blockAlign: TextAlign = TextAlign.START
        /** &lt;pre&gt; 嵌套深度 */
        var preDepth: Int = 0
        val inPre: Boolean get() = preDepth > 0
        private val listStack = ArrayList<ListFrame>()
        /** 表格嵌套深度；>0 时文本写入单元格缓冲 */
        var tableDepth: Int = 0
        private val tableRows = ArrayList<ArrayList<TableCell>>()
        private var currentRow: ArrayList<TableCell>? = null
        private val cellBuf = StringBuilder()
        private var inTableCell: Boolean = false
        private var cellIsHeader: Boolean = false
        /** 即将写入的列表前缀（li 打开时设置） */
        private var pendingListPrefix: String? = null

        private data class TableCell(
            val text: String,
            val header: Boolean,
        )

        private data class OpenSpan(
            val start: Int,
            val bold: Boolean,
            val italic: Boolean,
            val underline: Boolean,
            val color: Int?,
            val backgroundColor: Int?,
            val linkHref: String?,
        )

        private fun hasVisibleText(): Boolean {
            for (i in 0 until text.length) {
                val c = text[i]
                if (!c.isWhitespace() && c != OBJ_CHAR) return true
            }
            return false
        }

        fun currentStyle(): StyleFrame =
            styleStack.lastOrNull() ?: StyleFrame()

        fun pushList(ordered: Boolean, startAt: Int = 1) {
            listStack.add(ListFrame(ordered, startAt.coerceAtLeast(1)))
        }

        fun popList() {
            if (listStack.isNotEmpty()) listStack.removeAt(listStack.lastIndex)
        }

        fun beginListItem() {
            val frame = listStack.lastOrNull()
            pendingListPrefix = if (frame == null) {
                "• "
            } else if (frame.ordered) {
                val n = frame.nextIndex++
                "$n. "
            } else {
                "• "
            }
        }

        private fun consumeListPrefix() {
            val p = pendingListPrefix ?: return
            pendingListPrefix = null
            if (text.isEmpty()) text.append(p)
        }

        fun pushStyle(frame: StyleFrame) {
            styleStack.add(frame)
            val st = frame
            if (st.bold || st.italic || st.underline || st.color != null ||
                st.backgroundColor != null || !st.linkHref.isNullOrBlank()
            ) {
                openSpans.add(
                    OpenSpan(
                        start = text.length,
                        bold = st.bold,
                        italic = st.italic,
                        underline = st.underline,
                        color = st.color,
                        backgroundColor = st.backgroundColor,
                        linkHref = st.linkHref,
                    ),
                )
            }
        }

        fun popStyle() {
            if (styleStack.isEmpty()) return
            val st = styleStack.removeAt(styleStack.lastIndex)
            if (st.bold || st.italic || st.underline || st.color != null ||
                st.backgroundColor != null || !st.linkHref.isNullOrBlank()
            ) {
                // 关闭最近一次同风格 open
                for (i in openSpans.lastIndex downTo 0) {
                    val o = openSpans[i]
                    if (o.bold == st.bold &&
                        o.italic == st.italic &&
                        o.underline == st.underline &&
                        o.color == st.color &&
                        o.backgroundColor == st.backgroundColor &&
                        o.linkHref == st.linkHref
                    ) {
                        val end = text.length
                        if (end > o.start) {
                            finishedSpans.add(
                                TextSpanStyle(
                                    start = o.start,
                                    end = end,
                                    bold = o.bold,
                                    italic = o.italic,
                                    underline = o.underline,
                                    color = o.color,
                                    backgroundColor = o.backgroundColor,
                                    linkHref = o.linkHref,
                                ),
                            )
                        }
                        openSpans.removeAt(i)
                        break
                    }
                }
            }
        }

        fun markAnchor(id: String) {
            val clean = id.trim()
            if (clean.isEmpty()) return
            pendingAnchors.add(AnchorRef(offset = text.length, id = clean))
        }

        fun appendText(raw: String) {
            if (raw.isEmpty()) return
            if (inTableCell) {
                cellBuf.append(raw)
                return
            }
            consumeListPrefix()
            text.append(raw)
        }

        fun appendDecoded(raw: String) {
            appendText(decodeEntities(raw))
        }

        fun beginTable() {
            flush()
            tableDepth++
            if (tableDepth == 1) {
                tableRows.clear()
                currentRow = null
                inTableCell = false
                cellBuf.clear()
            }
        }

        fun beginRow() {
            endCell()
            endRow()
            currentRow = ArrayList(4)
        }

        fun beginCell(header: Boolean) {
            endCell()
            inTableCell = true
            cellIsHeader = header
            cellBuf.clear()
        }

        fun endCell() {
            if (!inTableCell) return
            val row = currentRow ?: ArrayList<TableCell>(4).also {
                currentRow = it
            }
            val t = cellBuf.toString().replace('\u00A0', ' ')
                .replace(Regex("\\s+"), " ").trim()
            row.add(TableCell(t, cellIsHeader))
            cellBuf.clear()
            inTableCell = false
            cellIsHeader = false
        }

        fun endRow() {
            endCell()
            val row = currentRow ?: return
            currentRow = null
            if (row.isNotEmpty() && row.any { it.text.isNotEmpty() }) {
                tableRows.add(row)
            }
        }

        fun endTable() {
            endCell()
            endRow()
            if (tableDepth == 1 && tableRows.isNotEmpty()) {
                emitTableBlocks()
                tableRows.clear()
            }
            if (tableDepth > 0) tableDepth--
            blockAlign = TextAlign.START
        }

        /** 按列宽对齐输出表格（preformatted 等宽），整行左对齐 */
        private fun emitTableBlocks() {
            if (tableRows.isEmpty()) return
            val colCount = tableRows.maxOf { it.size }
            if (colCount <= 0) return
            val colW = IntArray(colCount)
            for (row in tableRows) {
                for (c in 0 until colCount) {
                    val cell = row.getOrNull(c)?.text.orEmpty()
                    colW[c] = maxOf(colW[c], displayWidth(cell))
                }
            }
            // 限制过宽列，避免一行撑爆
            val maxCol = 24
            for (i in colW.indices) colW[i] = colW[i].coerceIn(1, maxCol)

            for (row in tableRows) {
                val sb = StringBuilder()
                val headerSpans = ArrayList<TextSpanStyle>()
                for (c in 0 until colCount) {
                    if (c > 0) sb.append(" │ ")
                    val cell = row.getOrNull(c)
                    val raw = cell?.text.orEmpty()
                    val start = sb.length
                    sb.append(padDisplay(raw, colW[c]))
                    val end = sb.length
                    if (cell?.header == true && end > start) {
                        headerSpans.add(
                            TextSpanStyle(
                                start = start,
                                end = end,
                                bold = true,
                            ),
                        )
                    }
                }
                val line = sb.toString().trimEnd()
                if (line.isEmpty()) continue
                blocks.add(
                    ParsedBlock(
                        text = line,
                        spans = headerSpans,
                        align = TextAlign.START,
                        preformatted = true, // 等宽，列对齐
                    ),
                )
            }
        }

        fun softBreak() {
            if (inPre) {
                text.append('\n')
                return
            }
            if (text.isNotEmpty() && text.last() != '\n' && text.last() != ' ') {
                text.append('\n')
            }
        }

        fun flush(forceChapter: Boolean = false) {
            // 列表前缀未消费（空 li）时丢弃
            pendingListPrefix = null
            // 关闭未闭合 span 到当前位置
            val end = text.length
            for (o in openSpans) {
                if (end > o.start) {
                    finishedSpans.add(
                        TextSpanStyle(
                            start = o.start,
                            end = end,
                            bold = o.bold,
                            italic = o.italic,
                            underline = o.underline,
                            color = o.color,
                            backgroundColor = o.backgroundColor,
                            linkHref = o.linkHref,
                        ),
                    )
                }
            }
            // 重新 open 到新段起点 0
            for (i in openSpans.indices) {
                val o = openSpans[i]
                openSpans[i] = o.copy(start = 0)
            }
            val raw = text.toString().replace('\u00A0', ' ')
            // pre：只去首尾空行感空白，保留内部空格/换行
            val body = if (inPre || blockPreFlag()) {
                raw.trimEnd().trimStart('\n', '\r')
            } else {
                raw.trim()
            }
            val pre = inPre || blockPreFlag()
            val align = blockAlign
            if (body.isNotEmpty()) {
                val spans = mergeSpans(finishedSpans.filter { it.end > it.start && it.start < body.length }
                    .map {
                        TextSpanStyle(
                            start = it.start.coerceAtLeast(0),
                            end = it.end.coerceAtMost(body.length),
                            bold = it.bold,
                            italic = it.italic,
                            underline = it.underline,
                            color = it.color,
                            backgroundColor = it.backgroundColor,
                            linkHref = it.linkHref,
                        )
                    }
                    .filter { it.start < it.end })
                val trimmedSpans = alignSpansAfterTrim(raw, body, spans)
                val trimmedInline = alignInlineAfterTrim(raw, body, pendingInline)
                val trimmedAnchors = alignAnchorsAfterTrim(raw, body, pendingAnchors)
                blocks.add(
                    ParsedBlock(
                        text = body,
                        spans = trimmedSpans,
                        isChapter = forceChapter || chapterDepth > 0,
                        inlineImages = trimmedInline,
                        anchors = trimmedAnchors,
                        align = align,
                        preformatted = pre,
                    ),
                )
            } else if (pendingAnchors.isNotEmpty()) {
                // 空段但有锚点：记到空文本段，加载器仍可映射
                blocks.add(
                    ParsedBlock(
                        text = "\u200B", // 零宽字符占位，避免被丢弃
                        anchors = pendingAnchors.map { it.copy(offset = 0) },
                        align = align,
                    ),
                )
            }
            text.clear()
            finishedSpans.clear()
            pendingInline.clear()
            pendingAnchors.clear()
            // 块对齐默认回到 start（下一开标签再设）；pre 由 depth 管
            if (!inPre) blockAlign = TextAlign.START
        }

        /** 刚离开 pre 时 flush 仍算 preformatted */
        private var leavingPre = false
        private fun blockPreFlag(): Boolean = leavingPre

        fun enterPre() {
            flush()
            preDepth++
            blockAlign = TextAlign.START
        }

        fun leavePre() {
            leavingPre = true
            flush()
            leavingPre = false
            if (preDepth > 0) preDepth--
            blockAlign = TextAlign.START
        }

        /**
         * @param forceBlock true 时强制整行图（先 flush 当前正文）
         */
        fun addImage(
            src: String,
            forceBlock: Boolean = false,
            displaySize: ImageDisplaySize? = null,
        ) {
            val s = src.trim()
            if (s.isEmpty()) return
            if (forceBlock || !hasVisibleText()) {
                flush()
                blocks.add(ParsedBlock(imageSrc = s, imageDisplaySize = displaySize))
            } else {
                // 行内：插入占位符
                val start = text.length
                text.append(OBJ_CHAR)
                pendingInline.add(InlineImageRef(start, start + 1, s, displaySize))
            }
        }

        fun enterChapter() {
            flush()
            chapterDepth++
        }

        fun leaveChapter() {
            flush(forceChapter = true)
            if (chapterDepth > 0) chapterDepth--
        }
    }

    fun parse(html: String): List<ParsedBlock> {
        if (html.isBlank()) return emptyList()
        // 去掉 script/style
        val cleaned = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")

        val b = Builder()
        var i = 0
        val n = cleaned.length
        while (i < n) {
            val c = cleaned[i]
            if (c == '<') {
                val gt = cleaned.indexOf('>', i + 1)
                if (gt < 0) {
                    b.appendDecoded(cleaned.substring(i))
                    break
                }
                val rawTag = cleaned.substring(i + 1, gt).trim()
                i = gt + 1
                if (rawTag.isEmpty()) continue
                // 声明/处理指令
                if (rawTag.startsWith("!") || rawTag.startsWith("?")) continue

                val closing = rawTag.startsWith("/")
                val body = if (closing) rawTag.substring(1).trim() else rawTag
                val nameEnd = body.indexOfFirst { it.isWhitespace() || it == '/' }
                val tagName = (if (nameEnd < 0) body else body.substring(0, nameEnd))
                    .lowercase(Locale.ROOT)
                val selfClosing = !closing && (rawTag.endsWith("/") || tagName == "br" ||
                    tagName == "hr" || tagName == "img" || tagName == "meta" ||
                    tagName == "link" || tagName == "source" || tagName == "input")
                val attrs = if (closing) emptyMap() else parseAttrs(body)

                when {
                    closing -> handleClose(b, tagName)
                    selfClosing -> handleEmpty(b, tagName, attrs)
                    else -> handleOpen(b, tagName, attrs)
                }
            } else {
                val next = cleaned.indexOf('<', i)
                val chunk = if (next < 0) cleaned.substring(i) else cleaned.substring(i, next)
                i = if (next < 0) n else next
                if (chunk.isNotEmpty()) {
                    if (b.inPre) {
                        // pre：保留空白与换行
                        val kept = chunk.replace("\r\n", "\n").replace('\r', '\n')
                        if (kept.isNotEmpty()) b.appendDecoded(kept)
                    } else {
                        // 折叠空白（保留换行意图由块标签处理）
                        val normalized = chunk
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                            .replace(Regex("[\\t\\x0B\\f]+"), " ")
                        if (normalized.isNotEmpty()) {
                            val sb = StringBuilder(normalized.length)
                            var lastWasSpace = false
                            for (ch in normalized) {
                                when {
                                    ch == '\n' -> {
                                        if (!lastWasSpace && sb.isNotEmpty()) {
                                            sb.append(' ')
                                            lastWasSpace = true
                                        }
                                    }
                                    ch.isWhitespace() -> {
                                        if (!lastWasSpace) {
                                            sb.append(' ')
                                            lastWasSpace = true
                                        }
                                    }
                                    else -> {
                                        sb.append(ch)
                                        lastWasSpace = false
                                    }
                                }
                            }
                            if (sb.isNotEmpty()) b.appendDecoded(sb.toString())
                        }
                    }
                }
            }
        }
        b.flush()
        return b.blocks
    }

    private fun handleOpen(b: Builder, tag: String, attrs: Map<String, String>) {
        when (tag) {
            "pre" -> {
                b.enterPre()
                noteId(b, attrs)
            }
            "ul" -> {
                b.flush()
                b.pushList(ordered = false)
                noteId(b, attrs)
            }
            "ol" -> {
                b.flush()
                val start = attrs["start"]?.toIntOrNull() ?: 1
                b.pushList(ordered = true, startAt = start)
                noteId(b, attrs)
            }
            "li" -> {
                b.flush()
                b.beginListItem()
                b.blockAlign = parseTextAlign(attrs) ?: TextAlign.START
                noteId(b, attrs)
            }
            "table" -> {
                b.beginTable()
                noteId(b, attrs)
            }
            "tr" -> {
                b.beginRow()
                noteId(b, attrs)
            }
            "td", "th" -> {
                // 单元格对齐不作用于整行；列对齐在 emit 时统一处理
                b.beginCell(header = tag == "th")
                noteId(b, attrs)
            }
            "p", "div", "section", "article", "blockquote",
            "header", "footer", "nav", "aside", "figure",
            "figcaption", "dd", "dt",
            -> {
                b.flush()
                b.blockAlign = parseTextAlign(attrs) ?: TextAlign.START
                noteId(b, attrs)
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                b.enterChapter()
                b.blockAlign = parseTextAlign(attrs) ?: TextAlign.START
                noteId(b, attrs)
            }
            "br" -> b.softBreak()
            "hr" -> {
                b.flush()
                b.softBreak()
            }
            "img" -> {
                noteId(b, attrs)
                val src = attrs["src"] ?: attrs["xlink:href"] ?: ""
                if (src.isNotBlank()) {
                    val ds = parseImageDisplaySize(attrs)
                    b.addImage(
                        src,
                        forceBlock = isBlockImageAttrs(attrs, ds),
                        displaySize = ds,
                    )
                }
            }
            "b", "strong" -> {
                noteId(b, attrs)
                val cur = b.currentStyle()
                b.pushStyle(cur.copy(bold = true))
            }
            "i", "em" -> {
                noteId(b, attrs)
                val cur = b.currentStyle()
                b.pushStyle(cur.copy(italic = true))
            }
            "u" -> {
                noteId(b, attrs)
                val cur = b.currentStyle()
                b.pushStyle(cur.copy(underline = true))
            }
            "font" -> {
                noteId(b, attrs)
                val cur = b.currentStyle()
                val col = parseColor(attrs["color"])
                b.pushStyle(cur.copy(color = col ?: cur.color))
            }
            "a" -> {
                noteId(b, attrs)
                val href = (attrs["href"] ?: attrs["xlink:href"]).orEmpty().trim()
                val cur = b.currentStyle()
                val style = attrs["style"].orEmpty()
                val col = parseCssColor(style, "color") ?: cur.color ?: DEFAULT_LINK_COLOR
                val bg = parseCssColor(style, "background-color")
                    ?: parseCssColor(style, "background")
                    ?: cur.backgroundColor
                b.pushStyle(
                    cur.copy(
                        underline = true,
                        color = col,
                        backgroundColor = bg,
                        linkHref = href.ifEmpty { cur.linkHref },
                    ),
                )
            }
            "span", "label", "sub", "sup", "small", "big", "code", "tt" -> {
                noteId(b, attrs)
                val cur = b.currentStyle()
                val style = attrs["style"].orEmpty()
                val col = parseCssColor(style, "color") ?: cur.color
                val bg = parseCssColor(style, "background-color")
                    ?: parseCssColor(style, "background")
                    ?: cur.backgroundColor
                if (col != cur.color || bg != cur.backgroundColor) {
                    b.pushStyle(cur.copy(color = col, backgroundColor = bg))
                } else {
                    b.pushStyle(cur)
                }
            }
            else -> noteId(b, attrs)
        }
    }

    private fun noteId(b: Builder, attrs: Map<String, String>) {
        val id = attrs["id"]?.trim().orEmpty()
        if (id.isNotEmpty()) b.markAnchor(id)
        val name = attrs["name"]?.trim().orEmpty()
        if (name.isNotEmpty() && name != id) b.markAnchor(name)
    }

    private fun handleClose(b: Builder, tag: String) {
        when (tag) {
            "pre" -> b.leavePre()
            "ul", "ol" -> {
                b.flush()
                b.popList()
            }
            "li" -> b.flush()
            "tr" -> b.endRow()
            "td", "th" -> b.endCell()
            "table" -> b.endTable()
            "p", "div", "section", "article", "blockquote",
            "header", "footer", "nav", "aside", "figure",
            "figcaption", "dd", "dt",
            -> b.flush()
            "h1", "h2", "h3", "h4", "h5", "h6" -> b.leaveChapter()
            "b", "strong", "i", "em", "u", "font" -> b.popStyle()
            "a", "span", "label", "sub", "sup", "small", "big", "code", "tt" -> b.popStyle()
            "body", "html" -> b.flush()
        }
    }

    /** 解析 text-align / align 属性 */
    fun parseTextAlign(attrs: Map<String, String>): TextAlign? {
        val fromAttr = attrs["align"]?.trim()?.lowercase(Locale.ROOT)
        val fromStyle = run {
            val style = attrs["style"].orEmpty()
            Regex("""(?i)\btext-align\s*:\s*([a-z]+)""").find(style)
                ?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        }
        val v = fromStyle ?: fromAttr ?: return null
        return when (v) {
            "center", "middle" -> TextAlign.CENTER
            "right", "end" -> TextAlign.END
            "left", "start", "justify" -> TextAlign.START
            else -> null
        }
    }

    private fun handleEmpty(b: Builder, tag: String, attrs: Map<String, String>) {
        when (tag) {
            "br", "hr" -> b.softBreak()
            "img" -> {
                val src = attrs["src"] ?: attrs["xlink:href"] ?: ""
                if (src.isNotBlank()) {
                    val ds = parseImageDisplaySize(attrs)
                    b.addImage(
                        src,
                        forceBlock = isBlockImageAttrs(attrs, ds),
                        displaySize = ds,
                    )
                }
            }
            "p", "div" -> b.flush()
        }
    }

    /** 解析 img 的 width/height（属性 + style） */
    fun parseImageDisplaySize(attrs: Map<String, String>): ImageDisplaySize? {
        var wEm: Float? = null
        var hEm: Float? = null
        var wPx: Float? = null
        var hPx: Float? = null
        var wPct: Float? = null
        var hPct: Float? = null
        fun apply(raw: String?, isW: Boolean) {
            if (raw.isNullOrBlank()) return
            val v = raw.trim().lowercase(Locale.ROOT)
            when {
                v.endsWith("em") -> {
                    val n = v.removeSuffix("em").trim().toFloatOrNull() ?: return
                    if (isW) wEm = n else hEm = n
                }
                v.endsWith("%") -> {
                    val n = v.removeSuffix("%").trim().toFloatOrNull() ?: return
                    if (isW) wPct = n else hPct = n
                }
                v.endsWith("px") -> {
                    val n = v.removeSuffix("px").trim().toFloatOrNull() ?: return
                    if (isW) wPx = n else hPx = n
                }
                v.toFloatOrNull() != null -> {
                    // 无单位：HTML 惯例为 CSS px
                    val n = v.toFloat()
                    if (isW) wPx = n else hPx = n
                }
            }
        }
        apply(attrs["width"], true)
        apply(attrs["height"], false)
        val style = attrs["style"].orEmpty()
        Regex("""(?i)\bwidth\s*:\s*([^;]+)""").find(style)?.groupValues?.get(1)?.let {
            apply(it, true)
        }
        Regex("""(?i)\bheight\s*:\s*([^;]+)""").find(style)?.groupValues?.get(1)?.let {
            apply(it, false)
        }
        val ds = ImageDisplaySize(wEm, hEm, wPx, hPx, wPct, hPct)
        return if (ds.hasAny) ds else null
    }

    /** CSS/属性启发式：整行图（小 em 外字图不当块） */
    private fun isBlockImageAttrs(
        attrs: Map<String, String>,
        displaySize: ImageDisplaySize? = null,
    ): Boolean {
        // 明确 1em 左右的外字/注音图 → 行内
        val em = displaySize?.widthEm ?: displaySize?.heightEm
        if (em != null && em <= 1.5f) return false
        val wPx = displaySize?.widthPx
        if (wPx != null && wPx <= 48f && (displaySize?.heightPx ?: wPx) <= 48f) return false

        val style = attrs["style"].orEmpty().lowercase(Locale.ROOT)
        if (style.contains("display:block") || style.contains("display: block")) return true
        val cls = attrs["class"].orEmpty().lowercase(Locale.ROOT)
        if (cls.contains("cover") || cls.contains("full") || cls.contains("block-img")) return true
        val w = attrs["width"].orEmpty().trim()
        if (w.endsWith("%")) {
            val pct = w.dropLast(1).toFloatOrNull() ?: 0f
            if (pct >= 80f) return true
        }
        if ((displaySize?.widthPercent ?: 0f) >= 80f) return true
        return false
    }

    private fun parseAttrs(tagBody: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // name="value" | name='value' | name=value
        val re = Regex(
            """([:@\w\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        )
        for (m in re.findAll(tagBody)) {
            val key = m.groupValues[1].lowercase(Locale.ROOT)
            val value = m.groupValues[2]
                .ifEmpty { m.groupValues[3] }
                .ifEmpty { m.groupValues[4] }
            out[key] = value
        }
        return out
    }

    private fun parseCssColor(style: String, prop: String): Int? {
        if (style.isBlank()) return null
        val re = Regex(
            """(?i)\b${Regex.escape(prop)}\s*:\s*([^;]+)""",
        )
        val m = re.find(style) ?: return null
        var v = m.groupValues[1].trim()
        // background: #fff url(...) → 只取色
        if (prop == "background") {
            v = v.split(Regex("\\s+")).firstOrNull().orEmpty()
        }
        return parseColor(v)
    }

    fun parseColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
        if (s.equals("transparent", true) || s.equals("inherit", true) ||
            s.equals("initial", true) || s.equals("none", true)
        ) {
            return null
        }
        try {
            if (s.startsWith("#")) {
                val hex = s.substring(1)
                when (hex.length) {
                    3 -> {
                        val r = hex[0].digitToInt(16) * 17
                        val g = hex[1].digitToInt(16) * 17
                        val b = hex[2].digitToInt(16) * 17
                        return Color.rgb(r, g, b)
                    }
                    4 -> {
                        val r = hex[0].digitToInt(16) * 17
                        val g = hex[1].digitToInt(16) * 17
                        val b = hex[2].digitToInt(16) * 17
                        val a = hex[3].digitToInt(16) * 17
                        return Color.argb(a, r, g, b)
                    }
                    6 -> return Color.parseColor("#$hex")
                    8 -> {
                        // #RRGGBBAA → Android ARGB
                        val r = hex.substring(0, 2).toInt(16)
                        val g = hex.substring(2, 4).toInt(16)
                        val b = hex.substring(4, 6).toInt(16)
                        val a = hex.substring(6, 8).toInt(16)
                        return Color.argb(a, r, g, b)
                    }
                }
            }
            val rgb = Regex(
                """(?i)rgba?\(\s*([0-9.]+%?)\s*,\s*([0-9.]+%?)\s*,\s*([0-9.]+%?)(?:\s*,\s*([0-9.]+))?\s*\)""",
            ).find(s)
            if (rgb != null) {
                fun comp(v: String): Int {
                    return if (v.endsWith("%")) {
                        ((v.dropLast(1).toFloat() / 100f) * 255f).toInt().coerceIn(0, 255)
                    } else {
                        v.toFloat().toInt().coerceIn(0, 255)
                    }
                }
                val r = comp(rgb.groupValues[1])
                val g = comp(rgb.groupValues[2])
                val b = comp(rgb.groupValues[3])
                val a = if (rgb.groupValues[4].isNotEmpty()) {
                    (rgb.groupValues[4].toFloat() * 255f).toInt().coerceIn(0, 255)
                } else {
                    255
                }
                return Color.argb(a, r, g, b)
            }
            // 常见命名色
            val named = NAMED_COLORS[s.lowercase(Locale.ROOT)]
            if (named != null) return named
            return Color.parseColor(s)
        } catch (_: Exception) {
            return null
        }
    }

    private val NAMED_COLORS = mapOf(
        "black" to Color.BLACK,
        "white" to Color.WHITE,
        "red" to Color.RED,
        "green" to Color.GREEN,
        "blue" to Color.BLUE,
        "yellow" to Color.YELLOW,
        "cyan" to Color.CYAN,
        "magenta" to Color.MAGENTA,
        "gray" to Color.GRAY,
        "grey" to Color.GRAY,
        "darkgray" to Color.DKGRAY,
        "darkgrey" to Color.DKGRAY,
        "lightgray" to Color.LTGRAY,
        "lightgrey" to Color.LTGRAY,
        "orange" to 0xFFFFA500.toInt(),
        "purple" to 0xFF800080.toInt(),
        "navy" to 0xFF000080.toInt(),
        "maroon" to 0xFF800000.toInt(),
        "olive" to 0xFF808000.toInt(),
        "teal" to 0xFF008080.toInt(),
        "silver" to 0xFFC0C0C0.toInt(),
        "aqua" to Color.CYAN,
        "fuchsia" to Color.MAGENTA,
        "lime" to 0xFF00FF00.toInt(),
    )

    /** 显示宽度：CJK 计 2，便于表格列对齐 */
    private fun displayWidth(s: String): Int {
        var w = 0
        for (ch in s) {
            w += when {
                ch.code <= 0x7F -> 1
                ch.isHighSurrogate() -> 0 // 与 low 一起计
                else -> 2 // 汉字/全角等
            }
        }
        return w.coerceAtLeast(0)
    }

    private fun padDisplay(s: String, width: Int): String {
        val dw = displayWidth(s)
        if (dw >= width) {
            // 过长截断到约 width
            if (dw <= width + 2) return s
            val sb = StringBuilder()
            var used = 0
            for (ch in s) {
                val cw = if (ch.code <= 0x7F) 1 else 2
                if (used + cw > width - 1) break
                sb.append(ch)
                used += cw
            }
            return sb.append('…').toString()
        }
        return s + " ".repeat(width - dw)
    }

    fun decodeEntities(input: String): String {
        if (input.indexOf('&') < 0) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = input.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(c)
                i++
                continue
            }
            val ent = input.substring(i + 1, semi)
            val ch = when {
                ent.equals("nbsp", true) -> ' '
                ent.equals("lt", true) -> '<'
                ent.equals("gt", true) -> '>'
                ent.equals("amp", true) -> '&'
                ent.equals("quot", true) -> '"'
                ent.equals("apos", true) -> '\''
                ent.equals("mdash", true) -> '—'
                ent.equals("ndash", true) -> '–'
                ent.equals("hellip", true) -> '…'
                ent.equals("lsquo", true) -> '‘'
                ent.equals("rsquo", true) -> '’'
                ent.equals("ldquo", true) -> '“'
                ent.equals("rdquo", true) -> '”'
                ent.startsWith("#x") || ent.startsWith("#X") -> {
                    ent.substring(2).toIntOrNull(16)?.toChar()
                }
                ent.startsWith("#") -> {
                    ent.substring(1).toIntOrNull()?.toChar()
                }
                else -> null
            }
            if (ch != null) {
                sb.append(ch)
                i = semi + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun mergeSpans(spans: List<TextSpanStyle>): List<TextSpanStyle> {
        if (spans.size <= 1) return spans
        return spans.sortedWith(compareBy({ it.start }, { it.end }))
    }

    /**
     * 将 trim 前的 span 对齐到 trim 后文本。
     */
    private fun alignSpansAfterTrim(
        raw: String,
        trimmed: String,
        spans: List<TextSpanStyle>,
    ): List<TextSpanStyle> {
        if (spans.isEmpty() || raw == trimmed) return spans
        val startPad = trimStartPad(raw, trimmed)
        return spans.mapNotNull { sp ->
            val a = (sp.start - startPad).coerceAtLeast(0)
            val b = (sp.end - startPad).coerceAtMost(trimmed.length)
            if (a >= b) null
            else sp.copy(start = a, end = b)
        }
    }

    private fun alignInlineAfterTrim(
        raw: String,
        trimmed: String,
        images: List<InlineImageRef>,
    ): List<InlineImageRef> {
        if (images.isEmpty()) return emptyList()
        val startPad = if (raw == trimmed) 0 else trimStartPad(raw, trimmed)
        return images.mapNotNull { im ->
            val a = (im.start - startPad).coerceAtLeast(0)
            val b = (im.end - startPad).coerceAtMost(trimmed.length)
            if (a >= b || a >= trimmed.length) null
            else im.copy(start = a, end = b.coerceAtLeast(a + 1))
        }
    }

    private fun alignAnchorsAfterTrim(
        raw: String,
        trimmed: String,
        anchors: List<AnchorRef>,
    ): List<AnchorRef> {
        if (anchors.isEmpty()) return emptyList()
        val startPad = if (raw == trimmed) 0 else trimStartPad(raw, trimmed)
        return anchors.map { a ->
            a.copy(offset = (a.offset - startPad).coerceIn(0, trimmed.length))
        }
    }

    private fun trimStartPad(raw: String, trimmed: String): Int {
        val lead = raw.indexOf(trimmed)
        return if (lead >= 0 && raw.regionMatches(lead, trimmed, 0, trimmed.length)) {
            lead
        } else {
            raw.length - raw.trimStart().length
        }
    }
}
