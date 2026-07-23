package com.whj.reader.pdf.text

import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.model.Paragraph

/**
 * PDF 懒加载文字缓存：原始页字符、切边后字符、段落链接。
 * 重建逻辑仍由宿主调用 [PdfTextExtractor.buildFromCachedPages]。
 */
class PdfTextCache {

    /** key = 0-based pageIndex */
    val rawPageCache = LinkedHashMap<Int, List<PdfTextExtractor.PdfChar>>()

    /** 0-based page → 带坐标字符（已按切边过滤） */
    var pageChars: Map<Int, List<PdfTextExtractor.PdfChar>> = emptyMap()
        private set

    var paraLinks: List<PdfTextExtractor.ParaLink> = emptyList()
        private set

    var paragraphs: List<Paragraph> = emptyList()
        private set

    fun clear() {
        rawPageCache.clear()
        pageChars = emptyMap()
        paraLinks = emptyList()
        paragraphs = emptyList()
    }

    fun putRaw(page: Int, chars: List<PdfTextExtractor.PdfChar>) {
        rawPageCache[page] = chars
    }

    fun hasRaw(page: Int): Boolean = page in rawPageCache

    fun applyBuilt(built: PdfTextExtractor.Extracted) {
        paragraphs = built.paragraphs
        pageChars = built.pageChars
        paraLinks = built.paraLinks
    }

    fun applyEmpty() {
        paragraphs = emptyList()
        pageChars = emptyMap()
        paraLinks = emptyList()
    }
}
