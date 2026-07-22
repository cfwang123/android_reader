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

        // 高度必须在 bind 时同步定死，避免滚动中途改高导致 RV 回弹。
        // 仅在宽/高变化时 requestLayout；相同高度再 requestLayout 会在快速滑页时抖动/跳页。
        val lp = layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            displayH,
        )
        val needResize =
            lp.height != displayH || lp.width != ViewGroup.LayoutParams.MATCH_PARENT
        if (needResize) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = displayH
            layoutParams = lp
        }
        if (minimumHeight != displayH) {
            minimumHeight = displayH
        }
        if (needResize) {
            requestLayout()
        }
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

    /** 取出整图引用并清空（rebind 时丢给调用方/GC，勿 recycle 正在显示的图） */
    fun drainFullBitmap(): Bitmap? {
        val b = fullBitmap
        fullBitmap = null
        if (mode == Mode.FULL) mode = Mode.EMPTY
        return b?.takeUnless { it.isRecycled }
    }

    fun setFullBitmap(bmp: Bitmap?) {
        clearTilesOnly(recycle = false)
        pendingTiles.clear()
        fullBitmap = bmp
        mode = if (bmp != null && !bmp.isRecycled) Mode.FULL else Mode.EMPTY
        // 位图宽高比纠正 View 高度：防止估算页高过矮把图纵向压扁
        if (bmp != null && !bmp.isRecycled) {
            syncHeightToBitmapAspect(bmp)
        }
        invalidate()
    }

    /**
     * 按位图宽高比校正 layout 高度（与 draw 时 stretch 一致）。
     * 估算页高错误时若不修正，会整页被压成扁条。
     */
    fun syncHeightToBitmapAspect(bmp: Bitmap) {
        if (bmp.isRecycled || bmp.width <= 0) return
        fun apply(vw: Int) {
            if (vw <= 0) return
            val expectedH = max(1, (vw.toFloat() * bmp.height / bmp.width.toFloat()).toInt())
            val lp = layoutParams ?: return
            if (kotlin.math.abs(lp.height - expectedH) <= 2) return
            lp.height = expectedH
            layoutParams = lp
            minimumHeight = expectedH
            requestLayout()
        }
        val vw = width.takeIf { it > 0 }
        if (vw != null) {
            apply(vw)
        } else {
            // 尚未 layout：下一帧再按真实宽度校正
            post {
                val b = fullBitmap
                if (b != null && !b.isRecycled && b === bmp) {
                    apply(width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)
                }
            }
        }
    }

    /**
     * 用真实 PDF 页尺寸校正显示高度（尺寸异步到达后调用）。
     * @return 是否发生了高度变化
     */
    fun correctDisplayGeometry(
        pageW: Float,
        pageH: Float,
        cropL: Float,
        cropT: Float,
        cropR: Float,
        cropB: Float,
        targetWidth: Int,
        tileHeightPx: Int,
        useTiles: Boolean,
    ): Boolean {
        this.pageW = pageW.coerceAtLeast(1f)
        this.pageH = pageH.coerceAtLeast(1f)
        this.cropL = cropL.coerceIn(0f, 0.30f)
        this.cropT = cropT.coerceIn(0f, 0.30f)
        this.cropR = cropR.coerceIn(0f, 0.30f)
        this.cropB = cropB.coerceIn(0f, 0.30f)
        val tw = targetWidth.coerceAtLeast(1)
        val srcW = this.pageW * (1f - this.cropL - this.cropR).coerceAtLeast(0.2f)
        val srcH = this.pageH * (1f - this.cropT - this.cropB).coerceAtLeast(0.2f)
        val displayH = max(1, (tw * srcH / srcW).toInt())
        val oldH = layoutParams?.height ?: 0
        val oldTiles = tileCount
        this.tileHeightPx = tileHeightPx.coerceAtLeast(400)
        this.tileCount = if (useTiles) {
            max(1, (displayH + this.tileHeightPx - 1) / this.tileHeightPx)
        } else {
            0
        }
        val needResize = oldH != displayH
        if (needResize) {
            val lp = layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                displayH,
            )
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = displayH
            layoutParams = lp
            minimumHeight = displayH
            requestLayout()
        }
        // 矮页↔长页切换或 tile 数变化：清内容等宿主重渲
        if (useTiles != (mode == Mode.TILES) || (useTiles && oldTiles != tileCount)) {
            clearBitmapsInternal(recycleTiles = false)
            pendingTiles.clear()
            mode = if (useTiles) Mode.TILES else Mode.EMPTY
            bindGeneration++ // 使旧异步结果失效
            return true
        }
        return needResize
    }

    /** 矮页已定高但尚未有有效整图（白页），滚动时需补渲 */
    fun isWaitingFullBitmap(): Boolean {
        if (pageIndex < 0 || tileCount > 0) return false
        if (mode == Mode.TILES) return false
        val b = fullBitmap
        return b == null || b.isRecycled
    }

    /** 当前是否几乎无画面（整图空 / 长图无任何 tile）→ 需补渲 */
    fun needsContent(): Boolean {
        if (pageIndex < 0) return false
        if (mode == Mode.FULL) {
            val b = fullBitmap
            return b == null || b.isRecycled
        }
        if (mode == Mode.TILES || tileCount > 0) {
            for (i in 0 until tiles.size()) {
                val b = tiles.valueAt(i)
                if (b != null && !b.isRecycled) return false
            }
            return true
        }
        // EMPTY 矮页
        return true
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

    fun installedTileCount(): Int {
        var n = 0
        for (i in 0 until tiles.size()) {
            val b = tiles.valueAt(i)
            if (b != null && !b.isRecycled) n++
        }
        return n
    }

    fun debugModeLabel(): String = when (mode) {
        Mode.FULL -> "FULL"
        Mode.TILES -> "TILES"
        Mode.EMPTY -> "EMPTY"
    }

    private fun ancestorScaled(): Boolean {
        var p: android.view.ViewParent? = parent
        while (true) {
            val v = p as? View ?: break
            if (v.scaleX != 1f || v.scaleY != 1f) return true
            p = v.parent
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        paint.colorFilter = colorFilter
        val w = width.toFloat().coerceAtLeast(1f)
        when (mode) {
            Mode.FULL -> {
                val bmp = fullBitmap
                if (bmp == null || bmp.isRecycled) {
                    if (ancestorScaled()) {
                        android.util.Log.w(
                            "PdfZoom",
                            "onDraw FULL empty page=$pageIndex scaled recycled=${bmp?.isRecycled}",
                        )
                    }
                    // 已被 cache 误 recycle 或尚未渲染：只留底色，等待 Activity 补渲
                    if (bmp != null && bmp.isRecycled) {
                        fullBitmap = null
                        mode = Mode.EMPTY
                    }
                    return
                }
                val h = height.toFloat().coerceAtLeast(1f)
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), paint)
            }
            Mode.TILES -> {
                val pageH = max(height, logicalHeight).coerceAtLeast(1)
                val bounds = canvas.clipBounds
                val clipTop = bounds.top
                val clipBottom = bounds.bottom
                val hasClip = clipBottom > clipTop
                val parentScaled = ancestorScaled()
                var drawn = 0
                var clipSkipped = 0
                for (i in 0 until tiles.size()) {
                    val idx = tiles.keyAt(i)
                    val bmp = tiles.valueAt(i) ?: continue
                    if (bmp.isRecycled) continue
                    val top = tileTop(idx)
                    val bottom = tileBottom(idx, pageH)
                    if (!parentScaled && hasClip && (bottom < clipTop || top > clipBottom)) {
                        clipSkipped++
                        continue
                    }
                    canvas.drawBitmap(
                        bmp,
                        null,
                        RectF(0f, top.toFloat(), w, bottom.toFloat()),
                        paint,
                    )
                    drawn++
                }
                if (parentScaled && clipSkipped > 0 && drawn == 0 && tiles.size() > 0) {
                    android.util.Log.w(
                        "PdfZoom",
                        "onDraw page=$pageIndex scaled clipSkip=$clipSkipped tiles=${tiles.size()} " +
                            "clip=$clipTop..$clipBottom viewH=$height",
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
