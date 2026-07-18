package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.model.Chapter
import com.whj.reader.model.Paragraph
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
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

    fun loadFromUri(context: Context, uri: Uri, displayName: String? = null): LoadedBook {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readAll(it) }
            ?: error("无法打开文件")
        val (text, encoding) = decodeBytes(bytes)
        val title = displayName
            ?: queryDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: context.getString(com.whj.reader.R.string.unnamed)
        return parseBook(text, title, encoding, uri.toString())
    }

    fun loadFromAssets(context: Context, assetPath: String, title: String): LoadedBook {
        val bytes = context.assets.open(assetPath).use { readAll(it) }
        val (text, encoding) = decodeBytes(bytes)
        return parseBook(text, title, encoding, "asset://$assetPath")
    }

    fun parseBook(text: String, title: String, encoding: String, uri: String): LoadedBook {
        val rawParas = text
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
        val chapters = paragraphs
            .filter { it.isChapter }
            .map { Chapter(title = it.text.lineSequence().first().take(60), paragraphIndex = it.index) }
            .ifEmpty {
                // 无章节时，用每隔若干段生成简易目录
                paragraphs.filterIndexed { i, _ -> i % 30 == 0 }.map {
                    Chapter(
                        title = it.text.take(40).replace('\n', ' ') +
                            if (it.text.length > 40) "…" else "",
                        paragraphIndex = it.index,
                    )
                }
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

    private fun decodeBytes(bytes: ByteArray): Pair<String, String> {
        if (bytes.isEmpty()) return "" to "UTF-8"
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

        val candidates = listOf("UTF-8", "GB18030", "GBK", "Big5")
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

    private fun scoreText(text: String, encoding: String): Int {
        var score = 0
        var replacement = 0
        var cjk = 0
        var printable = 0
        val sample = text.take(8000)
        for (ch in sample) {
            when {
                ch == '\uFFFD' -> replacement++
                ch.code in 0x4E00..0x9FFF -> {
                    cjk++
                    printable++
                }
                ch.isLetterOrDigit() || ch.isWhitespace() || "，。！？、；：\"\"''（）【】《》—…·,.!?;:'\"()-[]{}".contains(ch) ->
                    printable++
                ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' -> score -= 3
            }
        }
        score += printable
        score += cjk * 2
        score -= replacement * 20
        if (encoding == "UTF-8") score += 5
        if (encoding.startsWith("GB") && cjk > 20) score += 10
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

    /**
     * 按句号（及问号/感叹号）切分，供 TTS 逐句朗读。
     * 不以分号、省略号等切分，避免过碎。
     */
    fun splitSentences(paragraph: String): List<String> {
        val text = paragraph.trim()
        if (text.isEmpty()) return emptyList()
        val parts = text.split(Regex("(?<=[。！？!?])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(text) else parts
    }
}
