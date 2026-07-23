package com.whj.reader.pdf.render

import kotlin.math.max

/**
 * 页布局度量：长图判定、逻辑显示高度（与渲染分辨率无关的列表项高度计算）。
 */
object PdfLayoutMetrics {

    fun tallThresholdPx(screenHeightPx: Int): Int {
        val sh = screenHeightPx.coerceAtLeast(800)
        return max(
            (sh * PdfRenderConfig.TALL_PAGE_MIN_FACTOR).toInt(),
            PdfRenderConfig.TALL_PAGE_MIN_PX,
        )
    }

    /** 裁切后在 [targetWidth] 下的逻辑显示高度（px） */
    fun logicalDisplayHeight(
        pageW: Float,
        pageH: Float,
        margins: FloatArray,
        targetWidth: Int,
    ): Int {
        val cl = margins.getOrElse(0) { 0f }.coerceIn(0f, 0.30f)
        val ct = margins.getOrElse(1) { 0f }.coerceIn(0f, 0.30f)
        val cr = margins.getOrElse(2) { 0f }.coerceIn(0f, 0.30f)
        val cb = margins.getOrElse(3) { 0f }.coerceIn(0f, 0.30f)
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        val tw = targetWidth.coerceAtLeast(1).toFloat()
        if (srcW <= 0.01f) return 1
        val scale = tw / srcW
        return (srcH * scale).toInt().coerceAtLeast(1)
    }

    fun isTallPage(
        pageW: Float,
        pageH: Float,
        margins: FloatArray,
        targetWidth: Int,
        screenHeightPx: Int,
    ): Boolean {
        val h = logicalDisplayHeight(pageW, pageH, margins, targetWidth)
        return h > tallThresholdPx(screenHeightPx)
    }

    fun tileHeightForDevice(screenHeightPx: Int): Int {
        val sh = screenHeightPx.coerceAtLeast(800)
        return (sh * PdfRenderConfig.TILE_HEIGHT_FACTOR).toInt().coerceIn(800, 3200)
    }
}
