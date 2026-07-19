package com.whj.reader.data

import com.whj.reader.ocr.TfliteOcrEngine
import kotlin.math.max

/**
 * OCR 行框（渲染位图像素坐标）→ PDF 页坐标字符 [PdfTextExtractor.PdfChar]。
 *
 * 位图为切边后渲染；映射回完整页坐标以便与选字/高亮一致。
 */
object PdfOcrConverter {

    /**
     * @param bmpW 渲染图宽
     * @param bmpH 渲染图高
     * @param pageW PDF 页宽（PdfRenderer）
     * @param pageH PDF 页高
     * @param cropL/T/R/B 切边比例 0~1
     */
    fun linesToPdfChars(
        pageIndex: Int,
        lines: List<TfliteOcrEngine.LineResult>,
        bmpW: Int,
        bmpH: Int,
        pageW: Float,
        pageH: Float,
        cropL: Float,
        cropT: Float,
        cropR: Float,
        cropB: Float,
    ): List<PdfTextExtractor.PdfChar> {
        if (bmpW <= 0 || bmpH <= 0 || pageW <= 1f || pageH <= 1f) return emptyList()
        val contentL = pageW * cropL.coerceIn(0f, 0.4f)
        val contentT = pageH * cropT.coerceIn(0f, 0.4f)
        val contentW = pageW * (1f - cropL - cropR).coerceAtLeast(0.2f)
        val contentH = pageH * (1f - cropT - cropB).coerceAtLeast(0.2f)
        val sx = contentW / bmpW
        val sy = contentH / bmpH

        val out = ArrayList<PdfTextExtractor.PdfChar>()
        var index = 0
        for (line in lines) {
            val text = line.text
            if (text.isEmpty()) continue
            val box = line.box
            if (box == null || box.size < 8) {
                // 无框：整行堆在页中（少见）
                continue
            }
            val xs = floatArrayOf(box[0], box[2], box[4], box[6])
            val ys = floatArrayOf(box[1], box[3], box[5], box[7])
            val leftB = xs.min()
            val rightB = xs.max()
            val topB = ys.min()
            val bottomB = ys.max()
            val n = text.length
            val charW = max(1f, (rightB - leftB) / n)
            for (i in text.indices) {
                val ch = text[i]
                if (ch == '\n' || ch == '\r') continue
                val l = leftB + i * charW
                val r = l + charW
                out.add(
                    PdfTextExtractor.PdfChar(
                        pageIndex = pageIndex,
                        indexOnPage = index++,
                        char = ch,
                        left = contentL + l * sx,
                        top = contentT + topB * sy,
                        right = contentL + r * sx,
                        bottom = contentT + bottomB * sy,
                        pageWidth = pageW,
                        pageHeight = pageH,
                    ),
                )
            }
            // 行尾加空格，便于朗读断句
            if (out.isNotEmpty()) {
                val last = out.last()
                out.add(
                    last.copy(
                        indexOnPage = index++,
                        char = ' ',
                        left = last.right,
                        right = last.right + max(1f, charW * 0.3f),
                    ),
                )
            }
        }
        // 重编号
        return out.mapIndexed { i, c -> c.copy(indexOnPage = i) }
    }
}
