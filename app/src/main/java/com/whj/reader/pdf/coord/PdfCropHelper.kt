package com.whj.reader.pdf.coord

/**
 * 按页切边（奇偶对称时左右互换）。
 */
object PdfCropHelper {

    /**
     * @param base L,T,R,B
     * @param mirrorOddEven 是否奇偶页左右镜像
     * @param pageIndex 0-based
     */
    fun cropForPage(
        base: FloatArray,
        pageIndex: Int,
        mirrorOddEven: Boolean,
    ): FloatArray {
        val l = base.getOrElse(0) { 0f }
        val t = base.getOrElse(1) { 0f }
        val r = base.getOrElse(2) { 0f }
        val b = base.getOrElse(3) { 0f }
        if (!mirrorOddEven || pageIndex % 2 == 0) {
            return floatArrayOf(l, t, r, b)
        }
        return floatArrayOf(r, t, l, b)
    }
}
