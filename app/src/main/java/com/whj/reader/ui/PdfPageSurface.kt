package com.whj.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 连续模式单页表面：
 * - 矮页：整页 bitmap（[setFullBitmap]）
 * - 长页：按固定高度分块，绘制已缓存的 tile；可见区上下预取由宿主调度
 *
 * 选区坐标用逻辑页尺寸映射。
 */
class PdfPageSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var pageIndex: Int = -1
        private set

    var bindGeneration: Long = 0L
        private set

    private var pageW = 1f
    private var pageH = 1f
    private var cropL = 0f
    private var cropT = 0f
    private var cropR = 0f
    private var cropB = 0f

    private enum class Mode { EMPTY, FULL, TILES }

    private var mode = Mode.EMPTY
    private var fullBitmap: Bitmap? = null

    /** 单块逻辑高度（View 坐标 px） */
    var tileHeightPx: Int = 2000
        private set
    var tileCount: Int = 0
        private set

    /** tileIndex → bitmap（本 View 持有显示引用） */
    private val tiles = SparseArray<Bitmap>()
    /** 已在飞的 tile，避免重复请求 */
    private val pendingTiles = HashSet<Int>()

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bgColor = Color.WHITE
    private var colorFilter: ColorFilter? = null

    /**
     * 请求渲染某块。[tileTopPx]/[tileBottomPx] 为 View 坐标。
     */
    var onNeedTile: ((
        pageIndex: Int,
        surface: PdfPageSurface,
        tileIndex: Int,
        tileTopPx: Int,
        tileBottomPx: Int,
        targetWidth: Int,
        bindGeneration: Long,
    ) -> Unit)? = null

    val isFullMode: Boolean get() = mode == Mode.FULL
    val isTileMode: Boolean get() = mode == Mode.TILES
    val logicalHeight: Int
        get() = (layoutParams?.height?.takeIf { it > 0 } ?: height).coerceAtLeast(0)

    fun bind(
        pageIndex: Int,
        pageW: Float,
        pageH: Float,
        cropL: Float,
        cropT: Float,
        cropR: Float,
        cropB: Float,
        targetWidth: Int,
        tileHeightPx: Int,
        useTiles: Boolean,
    ) {
        this.pageIndex = pageIndex
        this.pageW = pageW.coerceAtLeast(1f)
        this.pageH = pageH.coerceAtLeast(1f)
        this.cropL = cropL.coerceIn(0f, 0.30f)
        this.cropT = cropT.coerceIn(0f, 0.30f)
        this.cropR = cropR.coerceIn(0f, 0.30f)
        this.cropB = cropB.coerceIn(0f, 0.30f)
        bindGeneration++
        // tile 位图由 Activity tileCache 持有，此处只摘引用
        clearBitmapsInternal(recycleTiles = false)
        pendingTiles.clear()

        val tw = targetWidth.coerceAtLeast(1)
        val srcW = this.pageW * (1f - this.cropL - this.cropR).coerceAtLeast(0.2f)
        val srcH = this.pageH * (1f - this.cropT - this.cropB).coerceAtLeast(0.2f)
        val displayH = max(1, (tw * srcH / srcW).toInt())
        this.tileHeightPx = tileHeightPx.coerceAtLeast(400)
        this.tileCount = if (useTiles) {
            max(1, (displayH + this.tileHeightPx - 1) / this.tileHeightPx)
        } else {
            0
        }
        mode = if (useTiles) Mode.TILES else Mode.EMPTY

        val lp = layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            displayH,
        )
        if (lp.height != displayH || lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = displayH
            layoutParams = lp
        }
        minimumHeight = displayH
        requestLayout()
        invalidate()
    }

    fun clearContent() {
        pageIndex = -1
        bindGeneration++
        pendingTiles.clear()
        clearBitmapsInternal(recycleTiles = false)
        mode = Mode.EMPTY
        tileCount = 0
        invalidate()
    }

    fun setFullBitmap(bmp: Bitmap?) {
        clearTilesOnly(recycle = false)
        pendingTiles.clear()
        fullBitmap = bmp
        mode = if (bmp != null && !bmp.isRecycled) Mode.FULL else Mode.EMPTY
        invalidate()
    }

    fun tileTop(index: Int): Int = index * tileHeightPx

    fun tileBottom(index: Int, pageHeight: Int = logicalHeight.coerceAtLeast(1)): Int =
        min(pageHeight, (index + 1) * tileHeightPx)

    fun hasTile(index: Int): Boolean {
        val b = tiles.get(index) ?: return false
        return !b.isRecycled
    }

    fun peekTile(index: Int): Bitmap? = tiles.get(index)?.takeUnless { it.isRecycled }

    /** 取出当前全部 tile 引用并清空（供 Activity unpin） */
    fun drainTiles(): List<Bitmap> {
        val out = ArrayList<Bitmap>(tiles.size())
        for (i in 0 until tiles.size()) {
            val b = tiles.valueAt(i) ?: continue
            if (!b.isRecycled) out.add(b)
        }
        tiles.clear()
        pendingTiles.clear()
        return out
    }

    /**
     * 安装一块。返回被替换下来的旧图（可能 null），由调用方 unpin。
     */
    fun setTile(tileIndex: Int, bmp: Bitmap, bindGen: Long, owned: Boolean = true): Bitmap? {
        if (bmp.isRecycled) return null
        if (bindGen != bindGeneration || pageIndex < 0) {
            if (owned) runCatching { bmp.recycle() }
            return null
        }
        if (tileIndex !in 0 until tileCount) {
            if (owned) runCatching { bmp.recycle() }
            return null
        }
        val old = tiles.get(tileIndex)
        tiles.put(tileIndex, bmp)
        pendingTiles.remove(tileIndex)
        mode = Mode.TILES
        fullBitmap = null
        invalidate()
        return old?.takeUnless { it === bmp || it.isRecycled }
    }

    /**
     * 确保可见区及 [prefetch] 块屏外预取已请求。
     * 请求顺序：可见块 → 下方 → 上方，优先填满屏幕。
     */
    fun ensureTilesForVisible(
        visTop: Int,
        visBottom: Int,
        targetWidth: Int,
        prefetch: Int,
    ) {
        if (mode == Mode.FULL && fullBitmap != null && !fullBitmap!!.isRecycled) return
        if (pageIndex < 0 || tileCount <= 0 || mode == Mode.FULL) return
        mode = Mode.TILES
        val pageH = max(logicalHeight, height).coerceAtLeast(1)
        val th = tileHeightPx.coerceAtLeast(1)
        val firstVis = (visTop.coerceAtLeast(0) / th).coerceIn(0, tileCount - 1)
        val lastVis = ((visBottom.coerceAtLeast(visTop + 1) - 1) / th).coerceIn(0, tileCount - 1)
        val first = (firstVis - prefetch).coerceAtLeast(0)
        val last = (lastVis + prefetch).coerceAtMost(tileCount - 1)

        val order = ArrayList<Int>(last - first + 1)
        for (i in firstVis..lastVis) order.add(i)
        for (i in lastVis + 1..last) order.add(i)
        for (i in firstVis - 1 downTo first) order.add(i)

        val tw = targetWidth.coerceAtLeast(1)
        val gen = bindGeneration
        for (i in order) {
            if (hasTile(i)) continue
            if (i in pendingTiles) continue
            pendingTiles.add(i)
            val top = tileTop(i)
            val bottom = tileBottom(i, pageH)
            onNeedTile?.invoke(pageIndex, this, i, top, bottom, tw, gen)
        }

        // 摘掉远离可见区的引用；返回值由 ensure 的调用方 unpin
        // （此处只摘引用，unpin 在 Activity.refresh 里做）
    }

    /** 移除远离可见区的 tile，返回被摘下的 bitmap 列表（供 unpin） */
    fun dropTilesOutside(visTop: Int, visBottom: Int, prefetch: Int): List<Bitmap> {
        if (tileCount <= 0 || tileHeightPx <= 0) return emptyList()
        val th = tileHeightPx
        val firstVis = (visTop.coerceAtLeast(0) / th).coerceIn(0, (tileCount - 1).coerceAtLeast(0))
        val lastVis = ((visBottom.coerceAtLeast(visTop + 1) - 1) / th)
            .coerceIn(0, (tileCount - 1).coerceAtLeast(0))
        val keepFirst = (firstVis - prefetch - 1).coerceAtLeast(0)
        val keepLast = (lastVis + prefetch + 1).coerceAtMost(tileCount - 1)
        val dropped = ArrayList<Bitmap>()
        var i = 0
        while (i < tiles.size()) {
            val idx = tiles.keyAt(i)
            if (idx < keepFirst || idx > keepLast) {
                val b = tiles.valueAt(i)
                tiles.removeAt(i)
                pendingTiles.remove(idx)
                if (b != null && !b.isRecycled) dropped.add(b)
            } else {
                i++
            }
        }
        return dropped
    }

    fun setNightMode(night: Boolean) {
        colorFilter = if (night) {
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -0.8f, 0f, 0f, 0f, 255f,
                        0f, -0.8f, 0f, 0f, 255f,
                        0f, 0f, -0.8f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        } else {
            null
        }
        invalidate()
    }

    fun setPageBackground(color: Int) {
        bgColor = color
        invalidate()
    }

    fun viewToPage(localX: Float, localY: Float): FloatArray {
        val vw = width.toFloat().coerceAtLeast(1f)
        val h = max(height.toFloat(), logicalHeight.toFloat()).coerceAtLeast(1f)
        val srcW = pageW * (1f - cropL - cropR).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - cropT - cropB).coerceAtLeast(0.2f)
        val pageX = pageW * cropL + (localX / vw) * srcW
        val pageY = pageH * cropT + (localY / h) * srcH
        return floatArrayOf(pageX, pageY)
    }

    fun pageRectToView(pageRect: RectF): RectF {
        val vw = width.toFloat().coerceAtLeast(1f)
        val h = max(height.toFloat(), logicalHeight.toFloat()).coerceAtLeast(1f)
        val srcW = pageW * (1f - cropL - cropR).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - cropT - cropB).coerceAtLeast(0.2f)
        val cropLeft = pageW * cropL
        val cropTop = pageH * cropT
        fun px(x: Float) = ((x - cropLeft) / srcW) * vw
        fun py(y: Float) = ((y - cropTop) / srcH) * h
        return RectF(px(pageRect.left), py(pageRect.top), px(pageRect.right), py(pageRect.bottom))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        paint.colorFilter = colorFilter
        val w = width.toFloat().coerceAtLeast(1f)
        when (mode) {
            Mode.FULL -> {
                val bmp = fullBitmap ?: return
                if (bmp.isRecycled) return
                val h = height.toFloat().coerceAtLeast(1f)
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), paint)
            }
            Mode.TILES -> {
                val pageH = max(height, logicalHeight).coerceAtLeast(1)
                val bounds = canvas.clipBounds
                val clipTop = bounds.top
                val clipBottom = bounds.bottom
                // clip 异常时仍尝试画全部已有块，避免整页空白
                val hasClip = clipBottom > clipTop
                for (i in 0 until tiles.size()) {
                    val idx = tiles.keyAt(i)
                    val bmp = tiles.valueAt(i) ?: continue
                    if (bmp.isRecycled) continue
                    val top = tileTop(idx)
                    val bottom = tileBottom(idx, pageH)
                    if (hasClip && (bottom < clipTop || top > clipBottom)) continue
                    canvas.drawBitmap(
                        bmp,
                        null,
                        RectF(0f, top.toFloat(), w, bottom.toFloat()),
                        paint,
                    )
                }
            }
            Mode.EMPTY -> Unit
        }
    }

    private fun clearTilesOnly(recycle: Boolean) {
        if (recycle) {
            for (i in 0 until tiles.size()) {
                val b = tiles.valueAt(i)
                if (b != null && !b.isRecycled) runCatching { b.recycle() }
            }
        }
        tiles.clear()
    }

    private fun clearBitmapsInternal(recycleTiles: Boolean) {
        clearTilesOnly(recycle = recycleTiles)
        fullBitmap = null
    }
}
