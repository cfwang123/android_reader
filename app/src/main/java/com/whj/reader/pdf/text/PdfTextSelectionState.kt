package com.whj.reader.pdf.text

/**
 * PDF 文字选区状态（文档序闭区间，可跨页）。
 * 纯状态 + 文档序运算，不依赖 Activity。
 */
class PdfTextSelectionState {

    var startPage: Int = -1
    var startChar: Int = -1
    var endPage: Int = -1
    var endChar: Int = -1
    var anchorPage: Int = -1
    var anchorChar: Int = -1

    fun hasSelection(): Boolean =
        startPage >= 0 && endPage >= 0 &&
            startChar >= 0 && endChar >= 0 &&
            compareDocPos(startPage, startChar, endPage, endChar) <= 0

    fun clear() {
        startPage = -1
        startChar = -1
        endPage = -1
        endChar = -1
        anchorPage = -1
        anchorChar = -1
    }

    fun setPoint(page: Int, char: Int) {
        anchorPage = page
        anchorChar = char
        startPage = page
        startChar = char
        endPage = page
        endChar = char
    }

    fun setFromAnchorAndHit(hitPage: Int, hitChar: Int) {
        if (anchorPage < 0 || anchorChar < 0) return
        if (compareDocPos(anchorPage, anchorChar, hitPage, hitChar) <= 0) {
            startPage = anchorPage
            startChar = anchorChar
            endPage = hitPage
            endChar = hitChar
        } else {
            startPage = hitPage
            startChar = hitChar
            endPage = anchorPage
            endChar = anchorChar
        }
    }

    fun normalizeOrder() {
        if (startPage < 0 || endPage < 0) return
        if (compareDocPos(startPage, startChar, endPage, endChar) > 0) {
            val tp = startPage
            val tc = startChar
            startPage = endPage
            startChar = endChar
            endPage = tp
            endChar = tc
        }
    }

    companion object {
        fun compareDocPos(pageA: Int, charA: Int, pageB: Int, charB: Int): Int {
            val pc = pageA.compareTo(pageB)
            return if (pc != 0) pc else charA.compareTo(charB)
        }
    }
}
