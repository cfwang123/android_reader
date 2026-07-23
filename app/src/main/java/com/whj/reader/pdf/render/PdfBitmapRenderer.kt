package com.whj.reader.pdf.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import kotlin.math.sqrt

/**
 * PdfRenderer 页/条带位图渲染（无状态）。
 * 调用方必须在 [renderLock] 内 open/close 页，并保证同一时间仅一页打开。
 */
object PdfBitmapRenderer {

    /**
     * 渲染时应用四边切边。
     *
     * **必须等比缩放**：不可对宽/高分别 coerceIn，否则长图会被纵向压扁。
     */
    fun renderPageBitmap(
        page: PdfRenderer.Page,
        targetWidth: Int,
        margins: FloatArray,
        maxRenderWidth: Int,
        targetHeight: Int? = null,
    ): Bitmap {
        val cl = margins.getOrElse(0) { 0f }.coerceIn(0f, 0.30f)
        val ct = margins.getOrElse(1) { 0f }.coerceIn(0f, 0.30f)
        val cr = margins.getOrElse(2) { 0f }.coerceIn(0f, 0.30f)
        val cb = margins.getOrElse(3) { 0f }.coerceIn(0f, 0.30f)
        val srcW = page.width * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = page.height * (1f - ct - cb).coerceAtLeast(0.2f)
        val tw = targetWidth.coerceAtLeast(1)
        val maxEdge = maxRenderWidth.coerceAtLeast(1)
        val cappedTw = tw.coerceAtMost(maxEdge).toFloat()
        val area = srcW * srcH
        var scale = if (targetHeight != null) {
            minOf(cappedTw / srcW, targetHeight / srcH, PdfRenderConfig.RENDER_MAX_SCALE)
        } else {
            minOf(cappedTw / srcW, PdfRenderConfig.RENDER_MAX_SCALE)
        }
        if (scale <= 0f || scale.isNaN() || scale.isInfinite()) scale = 0.05f
        if (targetHeight == null) {
            val minScale = cappedTw / srcW
            if (scale < minScale) scale = minScale
            var bhEst = srcH * scale
            if (bhEst > PdfRenderConfig.SINGLE_TALL_MAX_HEIGHT) {
                scale = PdfRenderConfig.SINGLE_TALL_MAX_HEIGHT / srcH
            }
            if (area > 0f && area * scale * scale > PdfRenderConfig.SINGLE_TALL_MAX_PIXELS) {
                scale = sqrt(PdfRenderConfig.SINGLE_TALL_MAX_PIXELS.toFloat() / area)
            }
        } else {
            if (area > 0f && area * scale * scale > PdfRenderConfig.RENDER_MAX_PIXELS) {
                scale = sqrt(PdfRenderConfig.RENDER_MAX_PIXELS / area)
            }
            if (srcW * scale > PdfRenderConfig.RENDER_MAX_DIM) {
                scale = PdfRenderConfig.RENDER_MAX_DIM / srcW
            }
            if (srcH * scale > PdfRenderConfig.RENDER_MAX_DIM) {
                scale = PdfRenderConfig.RENDER_MAX_DIM / srcH
            }
        }
        scale = scale.coerceAtLeast(0.02f)

        val bw = (srcW * scale).toInt().coerceAtLeast(1)
        val bh = (srcH * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(-page.width * cl * scale, -page.height * ct * scale)
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    /**
     * 渲染裁切后内容的纵向条带 [srcY0, srcY1)（page points，已扣 crop 顶）。
     */
    fun renderPageStripBitmap(
        page: PdfRenderer.Page,
        targetWidth: Int,
        srcY0: Float,
        srcY1: Float,
        margins: FloatArray,
        maxRenderWidth: Int,
    ): Bitmap {
        val cl = margins.getOrElse(0) { 0f }.coerceIn(0f, 0.30f)
        val ct = margins.getOrElse(1) { 0f }.coerceIn(0f, 0.30f)
        val cr = margins.getOrElse(2) { 0f }.coerceIn(0f, 0.30f)
        val cb = margins.getOrElse(3) { 0f }.coerceIn(0f, 0.30f)
        val srcW = page.width * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = page.height * (1f - ct - cb).coerceAtLeast(0.2f)
        val y0 = srcY0.coerceIn(0f, srcH)
        val y1 = srcY1.coerceIn(y0 + 0.5f, srcH)
        val stripH = (y1 - y0).coerceAtLeast(0.5f)
        val maxTw = maxRenderWidth.coerceAtLeast(1)
        val tw = targetWidth.coerceAtLeast(1).coerceAtMost(maxTw)
        var scale = (tw / srcW).coerceAtLeast(0.05f)
        if (srcW * stripH * scale * scale > PdfRenderConfig.TILE_MAX_PIXELS) {
            scale = sqrt(PdfRenderConfig.TILE_MAX_PIXELS / (srcW * stripH))
        }
        if (srcW * scale > PdfRenderConfig.RENDER_MAX_DIM) {
            scale = PdfRenderConfig.RENDER_MAX_DIM / srcW
        }
        if (stripH * scale > PdfRenderConfig.RENDER_MAX_DIM) {
            scale = PdfRenderConfig.RENDER_MAX_DIM / stripH
        }
        scale = scale.coerceAtLeast(0.05f)

        val bw = (srcW * scale).toInt().coerceAtLeast(1)
        val bh = (stripH * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val pageLeft = page.width * cl
        val pageTop = page.height * ct + y0
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(-pageLeft * scale, -pageTop * scale)
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }
}
