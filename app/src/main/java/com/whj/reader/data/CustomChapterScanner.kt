package com.whj.reader.data

import com.whj.reader.model.Chapter
import com.whj.reader.model.Paragraph
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 通配符章节目录扫描（非正则）：
 * - `*` 任意内容
 * - `x` 阿拉伯数字或中文数字（一、十、百…）
 * - `xxx` 三位数字（如 001）
 * - `xxxx` 四位数字（如 0001）
 *
 * 模式默认匹配整行。
 */
object CustomChapterScanner {

    data class Preset(val label: String, val pattern: String)

    val PRESETS: List<Preset> = listOf(
        Preset("第X章 + 标题", "第x章 *"),
        Preset("卷第X + 标题", "卷第x *"),
        Preset("…第X（行尾）", "*第x"),
        Preset("001. 标题", "xxx. *"),
        Preset("0001. 标题", "xxxx. *"),
        Preset("Chapter N", "Chapter x"),
        Preset("一、标题", "一、 *"),
    )

    private const val CJK_NUM = "[0-9零一二三四五六七八九十百千两〇]+"

    data class ScanResult(
        val chapters: List<Chapter>,
        val chapterParagraphIndices: Set<Int>,
    )

    fun compile(pattern: String, ignoreCase: Boolean = false): Pattern {
        val raw = pattern.trim()
        if (raw.isEmpty()) throw PatternSyntaxException("empty pattern", raw, 0)
        val regex = wildcardToRegex(raw)
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        return Pattern.compile(regex, flags)
    }

    /** 将通配符模式转为整行匹配的正则（供调试或测试） */
    fun wildcardToRegex(pattern: String): String {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("xxxx", i) -> {
                    sb.append("\\d{4}")
                    i += 4
                }
                pattern.startsWith("xxx", i) -> {
                    sb.append("\\d{3}")
                    i += 3
                }
                pattern[i] == 'x' -> {
                    sb.append(CJK_NUM)
                    i++
                }
                pattern[i] == '*' -> {
                    sb.append(".*")
                    i++
                }
                else -> {
                    sb.append(escapeRegexChar(pattern[i]))
                    i++
                }
            }
        }
        sb.append('$')
        return sb.toString()
    }

    fun scan(
        paragraphs: List<Paragraph>,
        pattern: String,
        ignoreCase: Boolean = false,
        maxTitleLineChars: Int = 80,
    ): ScanResult {
        val regex = compile(pattern, ignoreCase)
        val chapters = ArrayList<Chapter>(64)
        val indices = HashSet<Int>()
        for (p in paragraphs) {
            if (p.isBlockImage) continue
            val line = p.text.lineSequence().firstOrNull()?.trim().orEmpty()
            if (line.isEmpty() || line.length > maxTitleLineChars) continue
            if (!regex.matcher(line).matches()) continue
            indices.add(p.index)
            chapters.add(
                Chapter(
                    title = line.take(60),
                    paragraphIndex = p.index,
                ),
            )
        }
        return ScanResult(chapters, indices)
    }

    fun apply(
        book: LoadedBook,
        pattern: String,
        ignoreCase: Boolean = false,
    ): LoadedBook {
        val result = scan(book.paragraphs, pattern, ignoreCase)
        if (result.chapters.isEmpty()) {
            throw IllegalStateException("no_match")
        }
        val newParas = book.paragraphs.map { p ->
            val isCh = p.index in result.chapterParagraphIndices
            if (p.isChapter == isCh) p else p.copy(isChapter = isCh)
        }
        return book.copy(paragraphs = newParas, chapters = result.chapters)
    }

    private fun escapeRegexChar(ch: Char): String = when (ch) {
        '\\', '.', '^', '$', '|', '?', '+', '(', ')', '[', ']', '{', '}' -> "\\$ch"
        else -> ch.toString()
    }
}
