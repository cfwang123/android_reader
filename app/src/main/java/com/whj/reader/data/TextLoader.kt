package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.model.Chapter
import com.whj.reader.model.Paragraph
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

data class LoadedBook(
    val title: String,
    val paragraphs: List<Paragraph>,
    val chapters: List<Chapter>,
    val encoding: String,
    val uri: String,
)

object TextLoader {
    /** 单段字符上限，过大则切开，保护布局与 TTS */
    private const val MAX_PARA_CHARS = 1200

    private val CHAPTER_PATTERNS = listOf(
        Pattern.compile("^\\s*第[0-9零一二三四五六七八九十百千两]+[章节回卷部篇集].{0,40}$"),
        Pattern.compile("^\\s*Chapter\\s+\\d+.{0,40}$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*[第]?[0-9]{1,4}[\\.、\\s].{1,40}$"),
        Pattern.compile("^\\s*[一二三四五六七八九十百千]+[、\\.．].{1,40}$"),
    )

    /**
     * @param preferredEncoding 指定编码（如 UTF-8 / GBK）；null 或空则自动判断
     */
    fun loadFromUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        preferredEncoding: String? = null,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
    ): LoadedBook {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readAll(it) }
            ?: error("无法打开文件")
        val (text, encoding) = decodeBytes(bytes, preferredEncoding)
        val title = displayName
            ?: queryDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: context.getString(com.whj.reader.R.string.unnamed)
        return parseBook(text, title, encoding, uri.toString(), chineseMode)
    }

    fun loadFromAssets(
        context: Context,
        assetPath: String,
        title: String,
        preferredEncoding: String? = null,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
    ): LoadedBook {
        val bytes = context.assets.open(assetPath).use { readAll(it) }
        val (text, encoding) = decodeBytes(bytes, preferredEncoding)
        return parseBook(text, title, encoding, "asset://$assetPath", chineseMode)
    }

    fun parseBook(
        text: String,
        title: String,
        encoding: String,
        uri: String,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
    ): LoadedBook {
        val body = ChineseConvert.apply(text, chineseMode)
        val rawParas = body
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split("\n")
            .map { it.trimEnd() }
            .fold(mutableListOf<StringBuilder>()) { acc, line ->
                if (line.isBlank()) {
                    if (acc.isEmpty() || acc.last().isNotEmpty()) {
                        acc.add(StringBuilder())
                    }
                } else {
                    if (acc.isEmpty()) acc.add(StringBuilder())
                    val last = acc.last()
                    if (last.isNotEmpty()) last.append('\n')
                    last.append(line.trim())
                }
                acc
            }
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }

        // 若几乎没有空行分段，按双换行再试；仍失败则按句号粗分
        val paragraphsText = when {
            rawParas.size >= 3 -> rawParas
            else -> {
                val byBlank = text.split(Regex("\\n\\s*\\n+"))
                    .map { it.replace('\n', ' ').trim() }
                    .filter { it.isNotEmpty() }
                if (byBlank.size >= 3) byBlank
                else splitLongText(text)
            }
        }

        // 超长段切开，避免单段数万字导致 StaticLayout/TTS 卡死
        val flat = ArrayList<String>(paragraphsText.size * 2)
        for (p in paragraphsText) {
            if (p.length <= MAX_PARA_CHARS) {
                flat.add(p)
            } else {
                flat.addAll(chunkParagraph(p, MAX_PARA_CHARS))
            }
        }

        // 章节标题单独成段，避免「标题+正文」粘在一起导致整段加粗
        val splitParas = ArrayList<String>(flat.size + 8)
        for (p in flat) {
            splitParas.addAll(splitChapterLead(p))
        }

        val paragraphs = splitParas.mapIndexed { index, p ->
            Paragraph(
                index = index,
                text = p,
                isChapter = isChapterTitle(p),
            )
        }
        // 仅用识别出的章节标题作目录；识别失败则为空（界面显示「无目录」），
        // 勿把正文/TTS 分段伪造成目录
        val chapters = paragraphs
            .filter { it.isChapter }
            .map {
                Chapter(
                    title = it.text.lineSequence().first().take(60),
                    paragraphIndex = it.index,
                )
            }

        return LoadedBook(
            title = title.removeSuffix(".txt").removeSuffix(".TXT"),
            paragraphs = paragraphs,
            chapters = chapters,
            encoding = encoding,
            uri = uri,
        )
    }

    private fun splitLongText(text: String): List<String> {
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (cleaned.isEmpty()) return emptyList()
        // 中文按句号/问号/感叹号分，英文按 .!?
        val parts = cleaned.split(Regex("(?<=[。！？!?；;])\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size >= 3) return parts
        // 最终兜底：固定长度切块
        return cleaned.chunked(MAX_PARA_CHARS)
    }

    /** 尽量在句号处切开超长段 */
    private fun chunkParagraph(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxLen).coerceAtMost(text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val breakAt = window.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '.', '!', '?', '；', ';'))
                if (breakAt > maxLen / 3) {
                    end = start + breakAt + 1
                }
            }
            out.add(text.substring(start, end).trim())
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return out.filter { it.isNotEmpty() }
    }

    /**
     * 若首行是章节标题且后面还有正文，拆成两段。
     * 例：「001. 第一章：xxx\n在我协助…」→ 标题段 + 正文段
     */
    private fun splitChapterLead(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val nl = trimmed.indexOf('\n')
        if (nl < 0) {
            return listOf(trimmed)
        }
        val first = trimmed.substring(0, nl).trim()
        val rest = trimmed.substring(nl + 1).trim()
        if (rest.isEmpty()) return listOf(first)
        // 仅当首行像标题、且正文明显更长时拆开
        if (isChapterTitleLine(first) && rest.length > 8) {
            return listOf(first, rest)
        }
        return listOf(trimmed)
    }

    /** 整段是否章节标题：短标题行，不能夹带大段正文 */
    private fun isChapterTitle(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // 多行且后文较长 → 不是纯标题（应由 splitChapterLead 拆开）
        val nl = trimmed.indexOf('\n')
        if (nl >= 0) {
            val rest = trimmed.substring(nl + 1).trim()
            if (rest.length > 8) return false
        }
        val first = trimmed.lineSequence().first().trim()
        return isChapterTitleLine(first)
    }

    private fun isChapterTitleLine(line: String): Boolean {
        val first = line.trim()
        if (first.isEmpty() || first.length > 50) return false
        return CHAPTER_PATTERNS.any { it.matcher(first).matches() }
    }

    private fun readAll(input: InputStream): ByteArray {
        val bis = BufferedInputStream(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = bis.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 自动判断顺序：
     * 1. 用户指定编码
     * 2. BOM（UTF-8 / UTF-16）
     * 3. **字节是否为合法 UTF-8** → 是则直接 UTF-8（避免日文假名/汉字 UTF-8 被 GBK 误解）
     * 4. 否则在 GB18030 / GBK / Big5 等间按文本评分选优
     */
    private fun decodeBytes(
        bytes: ByteArray,
        preferredEncoding: String? = null,
    ): Pair<String, String> {
        if (bytes.isEmpty()) return "" to "UTF-8"

        val forced = preferredEncoding?.trim().orEmpty()
        if (forced.isNotEmpty() &&
            !forced.equals("auto", ignoreCase = true)
        ) {
            return decodeWithCharset(bytes, forced)
        }

        // BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charset.forName("UTF-16LE")) to "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charset.forName("UTF-16BE")) to "UTF-16BE"
        }

        // 合法 UTF-8 优先（日文/中文无 BOM 的 UTF-8 常被 GBK 误判）
        if (isStrictUtf8(bytes)) {
            return String(bytes, Charsets.UTF_8) to "UTF-8"
        }

        // 非合法 UTF-8：再在中文编码间比分
        val candidates = listOf("GB18030", "GBK", "Big5", "UTF-8")
        var best: Pair<String, String>? = null
        var bestScore = Int.MIN_VALUE
        for (name in candidates) {
            val cs = Charset.forName(name)
            val text = runCatching { String(bytes, cs) }.getOrNull() ?: continue
            val score = scoreText(text, name)
            if (score > bestScore) {
                bestScore = score
                best = text to name
            }
        }
        return best ?: (String(bytes, Charsets.UTF_8) to "UTF-8")
    }

    /** 严格 UTF-8：遇非法序列则失败（不用 REPLACE） */
    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeWithCharset(bytes: ByteArray, name: String): Pair<String, String> {
        return try {
            val cs = Charset.forName(name)
            // 跳过 UTF-8 BOM
            if (name.equals("UTF-8", ignoreCase = true) &&
                bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) {
                String(bytes, 3, bytes.size - 3, cs) to name
            } else {
                String(bytes, cs) to name
            }
        } catch (_: Exception) {
            decodeBytes(bytes, preferredEncoding = null)
        }
    }

    /**
     * 文本启发式评分（仅用于非合法 UTF-8 时的候选比较）。
     * 会计入汉字、日文假名、韩文；并惩罚替换字符与控制符。
     */
    private fun scoreText(text: String, encoding: String): Int {
        var score = 0
        var replacement = 0
        var cjk = 0
        var kana = 0
        var hangul = 0
        var printable = 0
        val sample = text.take(8000)
        for (ch in sample) {
            val cp = ch.code
            when {
                ch == '\uFFFD' -> replacement++
                // 汉字
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF -> {
                    cjk++
                    printable++
                }
                // 日文平假名 / 片假名 / 半角片假名
                cp in 0x3040..0x309F || cp in 0x30A0..0x30FF || cp in 0xFF66..0xFF9D -> {
                    kana++
                    printable++
                }
                // 韩文
                cp in 0xAC00..0xD7AF -> {
                    hangul++
                    printable++
                }
                ch.isLetterOrDigit() || ch.isWhitespace() ||
                    "，。！？、；：\"\"''（）【】《》—…·「」『』,.!?;:'\"()-[]{}".contains(ch) ->
                    printable++
                cp < 32 && ch != '\n' && ch != '\r' && ch != '\t' -> score -= 5
                // GB 误解 UTF-8 时常见的怪异符号区
                cp in 0xE000..0xF8FF -> score -= 2
            }
        }
        score += printable
        score += cjk * 2
        score += kana * 3
        score += hangul * 3
        score -= replacement * 40
        // 假名很多时不应偏向 GB（GB 下假名很少，若误解多是乱码汉字）
        if (kana > 10 && encoding.startsWith("GB")) score -= 30
        if (kana > 10 && encoding == "UTF-8") score += 20
        return score
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    /** 句在原文中的区间（end 为 exclusive），供 TTS 高亮 */
    data class SentenceSpan(
        val text: String,
        val start: Int,
        val end: Int,
    )

    /**
     * 换行如何参与 TTS 分句。
     * - [NEWLINE]：TXT — 单个换行即句尾
     * - [NONE]：仅标点断句（PDF 用；双倍/大行距不能当句尾）
     */
    enum class SentenceLineBreakMode {
        NEWLINE,
        NONE,
    }

    /**
     * 按句末标点切分，保留原文偏移。
     * - 中文主断句：。！？；… 及 ．
     * - 英文：. ! ?（. 仅在后接空白/结尾时切，避免 3.14）
     * - 换行规则见 [SentenceLineBreakMode]
     * - 「…？」类：问号/叹号后若紧跟闭引号，断在引号后
     */
    fun splitSentenceSpans(
        paragraph: String,
        lineBreakMode: SentenceLineBreakMode = SentenceLineBreakMode.NEWLINE,
    ): List<SentenceSpan> {
        if (paragraph.isEmpty()) return emptyList()
        val text = paragraph.replace("\r\n", "\n").replace('\r', '\n')
        val spans = ArrayList<SentenceSpan>(8)
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                when (lineBreakMode) {
                    SentenceLineBreakMode.NEWLINE -> {
                        addTrimmedSpan(spans, text, start, i)
                        // 跳过连续换行
                        var j = i + 1
                        while (j < text.length && text[j] == '\n') j++
                        start = j
                        i = j
                        continue
                    }
                    SentenceLineBreakMode.NONE -> {
                        // 换行不断句（PDF 常有大行距/双倍行距）
                        i++
                        continue
                    }
                }
            }
            var endAt = -1
            when (c) {
                '。', '！', '？', '．', '；' -> endAt = i + 1
                '…' -> {
                    // …… 连续省略号
                    var j = i
                    while (j < text.length && (text[j] == '…' || text[j] == '.')) j++
                    endAt = j
                }
                '!', '?' -> endAt = i + 1
                '.' -> {
                    val next = text.getOrNull(i + 1)
                    if (next == null || next.isWhitespace() || next == '\n' ||
                        next == '"' || next == '\u201D' || next == '」' || next == '』'
                    ) {
                        endAt = i + 1
                    }
                }
            }
            if (endAt > 0) {
                // 句末后紧跟闭引号一并吃进本句
                while (endAt < text.length) {
                    val n = text[endAt]
                    if (n == '」' || n == '』' || n == '"' || n == '\u201D' || n == '\'') {
                        endAt++
                    } else {
                        break
                    }
                }
                addTrimmedSpan(spans, text, start, endAt)
                start = endAt
                i = endAt
                continue
            }
            i++
        }
        if (start < text.length) {
            addTrimmedSpan(spans, text, start, text.length)
        }
        if (spans.isEmpty()) {
            val t = text.trim()
            if (t.isNotEmpty()) {
                val s = text.indexOf(t)
                spans.add(SentenceSpan(t, s, s + t.length))
            }
        }
        return spans
    }

    /** 仅文本列表（兼容旧调用） */
    fun splitSentences(
        paragraph: String,
        lineBreakMode: SentenceLineBreakMode = SentenceLineBreakMode.NEWLINE,
    ): List<String> =
        splitSentenceSpans(paragraph, lineBreakMode).map { it.text }

    private fun addTrimmedSpan(
        out: MutableList<SentenceSpan>,
        paragraph: String,
        from: Int,
        to: Int,
    ) {
        var a = from
        var b = to
        while (a < b && paragraph[a].isWhitespace()) a++
        while (b > a && paragraph[b - 1].isWhitespace()) b--
        if (a < b) {
            out.add(SentenceSpan(paragraph.substring(a, b), a, b))
        }
    }
}
