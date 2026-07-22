package com.whj.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 单图查看：双指缩放、单指平移/惯性（无双击放大）。
 * 相对 fit 比例可缩到 [minZoomFactor]（默认 50%）、放到 [maxZoomFactor]。
 * 未放大时横向滑动 / fling 回调 [onSwipePage]（上一张/下一张）。
 * 侧边轻点 [onSideTap]（0=左 2=右）；中心轻点 [onCenterTap]。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onSwipePage: ((forward: Boolean) -> Unit)? = null
    var onSideTap: ((zone: Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    /** 相对 fit 的缩放变化（1=适应屏） */
    var onZoomChanged: ((relativeZoom: Float) -> Unit)? = null
    var onTransformChanged: (() -> Unit)? = null

    /** 相对 fit 的最小缩放，默认 0.5（50%） */
    var minZoomFactor: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.1f, 1f)
            recomputeScaleRange()
        }

    /** 相对 fit 的最大缩放 */
    var maxZoomFactor: Float = 5f
        set(value) {
            field = value.coerceAtLeast(1.5f)
            recomputeScaleRange()
        }

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tmpVals = FloatArray(9)

    /** 适应屏幕的基准 scale */
    private var fitScale = 1f
    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f
    private var transX = 0f
    private var transY = 0f

    /** 换图时是否保留相对缩放（漫画翻页保持缩放） */
    var keepRelativeZoomOnBitmapChange: Boolean = false

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingV = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingV = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val swipeMinDistance = (56f * resources.displayMetrics.density).toInt()
        .coerceAtLeast(touchSlop * 2)

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pinching = false
    /**
     * 禁止本手势触发边缘/滑动翻页。
     * 双指缩放、已放大、多指落下时锁定；整段手势结束前不解除，
     * 避免松指时落在侧边误触发翻页。
     */
    private var pageSwipeLocked = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                pageSwipeLocked = true
                abortFling()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = detector.scaleFactor
                if (f.isNaN() || f.isInfinite() || f <= 0f) return false
                applyScale(f.coerceIn(0.85f, 1.2f), detector.focusX, detector.focusY)
                // 双指缩放过程中禁止侧点/滑翻页
                pageSwipeLocked = true
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
                pageSwipeLocked = true
                if (currentScale < minScale) {
                    currentScale = minScale
                    clampTranslation()
                    applyMatrix()
                    invalidate()
                }
                android.util.Log.i(
                    "MangaZoom",
                    "ImageView onScaleEnd relZoom=${getRelativeZoom()} " +
                        "pan=($transX,$transY) fit=$fitScale cur=$currentScale",
                )
                onZoomChanged?.invoke(getRelativeZoom())
                onTransformChanged?.invoke()
            }
        },
    ).also {
        // 关闭双击拖拽缩放（Quick Scale）
        it.isQuickScaleEnabled = false
    }

    /**
     * 中心单击；侧边翻页在 ACTION_UP 立刻处理。
     * 不启用双击放大（与 PDF ZoomableFrameLayout 一致）。
     */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean = false

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 中心立即响应，不等 double-tap 超时
                if (isSideZone(e.x)) return false
                onCenterTap?.invoke()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 已在 onSingleTapUp 处理
                return false
            }
        },
    ).also {
        // 长按无意义，关掉可略减手势判定开销
        it.setIsLongpressEnabled(false)
    }

    private fun isSideZone(x: Float): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        return x < w / 3f || x > w * 2f / 3f
    }

    private fun fireSideTap(x: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        if (x < w / 3f) onSideTap?.invoke(0)
        else if (x > w * 2f / 3f) onSideTap?.invoke(2)
    }

    fun setImageBitmap(bmp: Bitmap?) {
        abortFling()
        val prevRel = if (keepRelativeZoomOnBitmapChange && bitmap != null) {
            getRelativeZoom()
        } else {
            1f
        }
        val prevPanX = if (keepRelativeZoomOnBitmapChange && bitmap != null) transX else 0f
        val prevPanY = if (keepRelativeZoomOnBitmapChange && bitmap != null) transY else 0f
        val keep = keepRelativeZoomOnBitmapChange && bitmap != null && abs(prevRel - 1f) > 0.02f
        bitmap = bmp
        resetTransform()
        if (keep && bmp != null) {
            setTransform(prevRel, prevPanX, prevPanY, notify = false)
        }
        invalidate()
    }

    /** 相对 fit 的缩放：1=适应屏，0.5=缩小一半 */
    fun getRelativeZoom(): Float = currentScale / fitScale.coerceAtLeast(0.001f)

    fun getPanX(): Float = transX
    fun getPanY(): Float = transY

    /**
     * 设置相对缩放与平移（[relativeZoom] 相对 fit，1=适应）。
     * 需在有 bitmap 且 layout 完成后调用。
     */
    fun setTransform(
        relativeZoom: Float,
        panX: Float,
        panY: Float,
        notify: Boolean = false,
    ) {
        abortFling()
        if (bitmap == null || fitScale <= 0f || width <= 0 || height <= 0) return
        val rel = relativeZoom.coerceIn(minZoomFactor, maxZoomFactor)
        currentScale = (fitScale * rel).coerceIn(minScale, maxScale)
        if (abs(rel - 1f) < 0.01f) {
            currentScale = fitScale
            // fit 下也可恢复平移（长图滚动位置），勿一律居中冲掉
            if (abs(panX) < 0.5f && abs(panY) < 0.5f) {
                centerAtFit()
            } else {
                transX = panX
                transY = panY
                clampTranslation()
            }
        } else {
            transX = panX
            transY = panY
            clampTranslation()
        }
        applyMatrix()
        invalidate()
        if (notify) {
            onZoomChanged?.invoke(getRelativeZoom())
            onTransformChanged?.invoke()
        }
    }

    /** 是否已绑定可绘制位图 */
    fun hasBitmap(): Boolean = bitmap != null && !(bitmap?.isRecycled ?: true)

    fun resetZoom(notify: Boolean = false) {
        setTransform(1f, 0f, 0f, notify)
    }

    /** 是否放大（>fit）：用于平移接管与禁止侧点翻页 */
    fun isZoomed(): Boolean = isEnlarged()

    /** 是否相对 fit 有缩放（含缩小） */
    fun isScaled(): Boolean = abs(getRelativeZoom() - 1f) > 0.01f

    private fun isEnlarged(): Boolean = currentScale > fitScale * 1.05f

    /** 缩小或放大后内容可能超出视口，允许平移 */
    private fun needsPan(): Boolean {
        val bmp = bitmap ?: return false
        val dispW = bmp.width * currentScale
        val dispH = bmp.height * currentScale
        return dispW > width + 1f || dispH > height + 1f || isEnlarged()
    }

    private fun recomputeScaleRange() {
        if (fitScale <= 0f) return
        minScale = fitScale * minZoomFactor
        maxScale = max(fitScale * maxZoomFactor, fitScale * 2f)
        currentScale = currentScale.coerceIn(minScale, maxScale)
    }

    private fun centerAtFit() {
        val bmp = bitmap ?: return
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = currentScale
        transX = (vw - bw * scale) / 2f
        val scaledH = bh * scale
        val landscape = vw > vh
        transY = if (landscape && scaledH > vh) {
            0f
        } else {
            (vh - scaledH) / 2f
        }
    }

    private fun resetTransform() {
        val bmp = bitmap
        if (bmp == null || width <= 0 || height <= 0 || bmp.width <= 0 || bmp.height <= 0) {
            fitScale = 1f
            minScale = minZoomFactor
            maxScale = maxZoomFactor
            currentScale = 1f
            transX = 0f
            transY = 0f
            matrix.reset()
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        // 横屏：按宽度铺满；竖屏：整页适应（fitCenter）
        val landscape = vw > vh
        val scale = if (landscape) {
            vw / bw
        } else {
            min(vw / bw, vh / bh)
        }
        fitScale = scale
        minScale = scale * minZoomFactor
        maxScale = max(scale * maxZoomFactor, scale * 2f)
        currentScale = scale
        centerAtFit()
        applyMatrix()
    }

    private fun applyMatrix() {
        matrix.reset()
        matrix.setScale(currentScale, currentScale)
        matrix.postTranslate(transX, transY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && bitmap != null) {
            val rel = getRelativeZoom()
            val px = transX
            val py = transY
            val scaled = abs(rel - 1f) > 0.02f
            resetTransform()
            if (scaled) {
                setTransform(rel, px, py, notify = false)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        transX = scroller.currX.toFloat()
        transY = scroller.currY.toFloat()
        applyMatrix()
        invalidate()
        if (!scroller.isFinished) {
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                abortFling()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                // 新单指手势：仅在放大态锁翻页（缩小到 50% 仍可侧点/滑翻）
                pageSwipeLocked = isEnlarged()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二指落下即锁翻页，避免松指落在侧边误翻
                pageSwipeLocked = true
                moved = true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (pinching || event.pointerCount > 1 || scaleDetector.isInProgress) {
                    pageSwipeLocked = true
                    moved = true
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (!moved) {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        moved = true
                    }
                }
                if (!moved) return true

                if (needsPan() || pageSwipeLocked) {
                    if (needsPan()) {
                        transX += dx
                        transY += dy
                        clampTranslation()
                        applyMatrix()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                val vt = velocityTracker
                vt?.computeCurrentVelocity(1000, maxFlingV.toFloat())
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                val multiOrPinch =
                    pinching || pageSwipeLocked || scaleDetector.isInProgress
                if (!multiOrPinch && moved) {
                    if (isEnlarged() && needsPan()) {
                        if (abs(vx) > minFlingV || abs(vy) > minFlingV) {
                            startPanFling(vx, vy)
                        } else {
                            clampTranslation()
                            applyMatrix()
                            invalidate()
                        }
                        onTransformChanged?.invoke()
                    } else if (!isEnlarged()) {
                        // 未放大（含 fit / 缩小）：水平滑翻页
                        val totalDx = event.x - downX
                        val totalDy = event.y - downY
                        val horizontal = abs(totalDx) > abs(totalDy) * 1.1f
                        val byDist = horizontal && abs(totalDx) >= swipeMinDistance
                        val byVel = abs(vx) >= minFlingV * 1.2f && abs(vx) > abs(vy) * 1.2f
                        if (byDist || byVel) {
                            val forward = if (byVel) vx < 0f else totalDx < 0f
                            onSwipePage?.invoke(forward)
                        }
                    } else {
                        onTransformChanged?.invoke()
                    }
                } else if (isEnlarged() || pageSwipeLocked) {
                    // 缩放/多指手势松手：只做平移惯性，绝不翻页
                    if (event.actionMasked == MotionEvent.ACTION_UP && moved && isEnlarged()) {
                        if (abs(vx) > minFlingV || abs(vy) > minFlingV) {
                            startPanFling(vx, vy)
                        }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onTransformChanged?.invoke()
                    }
                } else if (!pinching &&
                    !moved &&
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    isSideZone(event.x) &&
                    !isEnlarged() &&
                    !pageSwipeLocked
                ) {
                    // 侧边轻点：松手立刻翻页（无双击确认延迟）；缩小态也允许
                    fireSideTap(event.x)
                }
            }
        }
        return true
    }

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        val next = (currentScale * factor).coerceIn(minScale, maxScale)
        val real = next / currentScale.coerceAtLeast(0.001f)
        if (abs(real - 1f) < 0.001f) return
        // 焦点缩放：新平移 = focus - (focus - oldTrans) * real
        transX = focusX - (focusX - transX) * real
        transY = focusY - (focusY - transY) * real
        currentScale = next
        // 贴近 fit 时吸附
        if (abs(currentScale - fitScale) < fitScale * 0.02f) {
            currentScale = fitScale
            centerAtFit()
        } else {
            clampTranslation()
        }
        applyMatrix()
        invalidate()
    }

    private fun startPanFling(vx: Float, vy: Float) {
        val (xMin, xMax, yMin, yMax) = panRange()
        scroller.fling(
            transX.toInt(),
            transY.toInt(),
            vx.toInt(),
            vy.toInt(),
            xMin.toInt(),
            xMax.toInt(),
            yMin.toInt(),
            yMax.toInt(),
        )
        postInvalidateOnAnimation()
    }

    private fun panRange(): FloatArray {
        val bmp = bitmap ?: return floatArrayOf(0f, 0f, 0f, 0f)
        val dispW = bmp.width * currentScale
        val dispH = bmp.height * currentScale
        val xMin: Float
        val xMax: Float
        if (dispW <= width) {
            val cx = (width - dispW) / 2f
            xMin = cx
            xMax = cx
        } else {
            xMin = width - dispW
            xMax = 0f
        }
        val yMin: Float
        val yMax: Float
        if (dispH <= height) {
            val cy = (height - dispH) / 2f
            yMin = cy
            yMax = cy
        } else {
            yMin = height - dispH
            yMax = 0f
        }
        return floatArrayOf(xMin, xMax, yMin, yMax)
    }

    private fun clampTranslation() {
        val (xMin, xMax, yMin, yMax) = panRange()
        transX = transX.coerceIn(min(xMin, xMax), max(xMin, xMax))
        transY = transY.coerceIn(min(yMin, yMax), max(yMin, yMax))
    }

    private fun abortFling() {
        if (!scroller.isFinished) scroller.forceFinished(true)
    }

    override fun onDetachedFromWindow() {
        abortFling()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }
}
