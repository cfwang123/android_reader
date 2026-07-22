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
// min/max used by soft clamp while pinching

/**
 * 单图查看：双指缩放、单指平移/惯性（无双击放大）。
 * 相对 fit 比例可缩到 [minZoomFactor]（默认 25%）、放到 [maxZoomFactor]。
 * 基准 fit 为整图适应屏幕（fitCenter，横竖屏一致）。
 * 未放大时横向滑动 / fling 回调 [onSwipePage]（上一张/下一张）。
 * 侧边轻点 [onSideTap]（0=左 2=右）；中心轻点 [onCenterTap]（含放大态，用于开菜单）。
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

    /** 相对 fit 的最小缩放，默认 0.25（25%） */
    var minZoomFactor: Float = 0.25f
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
    /**
     * 双指：用 span 推绝对目标比例；矩阵用「上一帧 → 本帧」增量更新，
     * 避免 <fit 居中 与 >fit 焦点公式硬切换造成快速捏合跳动。
     */
    private var pinchStartScale = 1f
    private var pinchStartSpan = 1f
    private var pinchFrozen = false
    private var pinchLastFocusX = 0f
    private var pinchLastFocusY = 0f
    private var pinchHasLastFocus = false
    /** 单帧焦点位移上限（过滤抬指） */
    private val maxPinchFocusJump =
        (64f * resources.displayMetrics.density).coerceAtLeast(40f)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                pageSwipeLocked = true
                pinchFrozen = false
                pinchStartScale = currentScale.coerceAtLeast(0.001f)
                pinchStartSpan = detector.currentSpan.coerceAtLeast(1f)
                pinchLastFocusX = detector.focusX
                pinchLastFocusY = detector.focusY
                // 首帧不吃焦点位移
                pinchHasLastFocus = false
                abortFling()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (pinchFrozen) return true
                if (detector.currentSpan < 1f) return true
                applyPinchFrame(
                    detector.focusX,
                    detector.focusY,
                    detector.currentSpan,
                )
                pageSwipeLocked = true
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
                pageSwipeLocked = true
                pinchFrozen = false
                pinchHasLastFocus = false
                settleAfterPinch()
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
        it.isQuickScaleEnabled = false
    }

    private fun contentFitsAt(scale: Float): Boolean {
        val bmp = bitmap ?: return true
        val sc = scale.coerceAtLeast(0.001f)
        return bmp.width * sc <= width + 0.5f && bmp.height * sc <= height + 0.5f
    }

    private fun centerAtScale(scale: Float) {
        val bmp = bitmap ?: return
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val sc = scale.coerceAtLeast(0.001f)
        transX = (vw - bmp.width * sc) / 2f
        transY = (vh - bmp.height * sc) / 2f
    }

    /**
     * 漫画单图捏合一帧：
     * 1) 目标比例 = startScale * (span/startSpan)，并限制单帧步进
     * 2) **始终**用「绕焦点缩放」从上一帧矩阵增量更新（不切换公式）
     * 3) 双指同向：叠加焦点位移
     * 4) 仅当结果仍 ≤fit（整图进屏）时强制居中 —— 居中前后视觉连续，因整图本就小于屏
     */
    private fun applyPinchFrame(focusX: Float, focusY: Float, span: Float) {
        val startSpan = pinchStartSpan.coerceAtLeast(1f)
        val s = span.coerceAtLeast(1f)
        val ratio = (s / startSpan).coerceIn(0.15f, 6f)
        val target = (pinchStartScale * ratio).coerceIn(minScale, maxScale)
        val prevScale = currentScale.coerceAtLeast(0.001f)
        val next = smoothZoomStep(prevScale, target)
        val prevTx = transX
        val prevTy = transY

        // —— 绕焦点缩放（增量，跨 fit 也同一公式）——
        val factor = next / prevScale
        if (abs(factor - 1f) > 0.0001f) {
            transX = focusX - (focusX - transX) * factor
            transY = focusY - (focusY - transY) * factor
            currentScale = next
        } else if (abs(next - prevScale) > 0.0001f) {
            currentScale = next
        }

        // —— 双指同向平移 ——
        if (pinchHasLastFocus) {
            val dx = focusX - pinchLastFocusX
            val dy = focusY - pinchLastFocusY
            if (abs(dx) <= maxPinchFocusJump && abs(dy) <= maxPinchFocusJump) {
                transX += dx
                transY += dy
            }
        }
        pinchLastFocusX = focusX
        pinchLastFocusY = focusY
        pinchHasLastFocus = true

        // ≤fit：强制居中（松手不必再跳）。此时图未铺满，居中不会「甩」出焦点附近内容。
        if (contentFitsAt(currentScale)) {
            centerAtScale(currentScale)
        } else {
            clampTranslation()
        }

        applyMatrix()
        invalidate()

        if ((prevScale - fitScale) * (currentScale - fitScale) <= 0f ||
            abs(currentScale - fitScale) < fitScale * 0.03f
        ) {
            android.util.Log.i(
                "MangaZoom",
                "imgFrame sc $prevScale→$currentScale target=$target " +
                    "pan ($prevTx,$prevTy)→($transX,$transY) " +
                    "focus=($focusX,$focusY) fits=${contentFitsAt(currentScale)}",
            )
        }
    }

    /** 向 target 靠拢；快速捏合时分帧追上，避免单帧比例爆炸 */
    private fun smoothZoomStep(prev: Float, target: Float): Float {
        val p = prev.coerceAtLeast(0.001f)
        val t = target.coerceIn(minScale, maxScale)
        // 相对最多 8%，绝对最多 6% fit —— 再快也平滑
        val maxRel = 1.08f
        val maxAbs = fitScale * 0.06f
        var lo = max(p / maxRel, p - maxAbs)
        var hi = min(p * maxRel, p + maxAbs)
        return t.coerceIn(lo, hi).coerceIn(minScale, maxScale)
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
                // 点按统一在 ACTION_UP 处理（放大态中心也能开菜单）
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
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
        // fitCenter：水平/垂直均居中
        transX = (vw - bw * scale) / 2f
        transY = (vh - bh * scale) / 2f
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
        // 横竖屏均 fitCenter：整图进屏（横屏不再强制铺满宽导致上下裁切）
        val scale = min(vw / bw, vh / bh)
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
        // 必须先于 scaleDetector：抬指时冻结，阻止本帧用坏 span 改姿态
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinching && event.pointerCount <= 2) {
                    pinchFrozen = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > 2 && pinching) {
                    pinchFrozen = true
                }
            }
        }
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
            MotionEvent.ACTION_POINTER_UP -> {
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

                val totalMove = max(abs(event.x - downX), abs(event.y - downY))
                val isTap = event.actionMasked == MotionEvent.ACTION_UP &&
                    !pinching &&
                    totalMove <= touchSlop

                // 轻点优先：放大态中心点也可开菜单（勿被微小平移吞掉）
                if (isTap) {
                    if (isSideZone(event.x)) {
                        if (!isEnlarged() && !pageSwipeLocked) {
                            fireSideTap(event.x)
                        }
                        // 放大时侧点不翻页，仅结束手势
                    } else {
                        onCenterTap?.invoke()
                    }
                    return@onTouchEvent true
                }

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
                }
            }
        }
        return true
    }

    /** 松手：≤fit 已居中；>fit 只夹边。不做大幅改 pan。 */
    private fun settleAfterPinch() {
        val before = Triple(currentScale, transX, transY)
        if (currentScale < minScale) {
            currentScale = minScale
        } else if (abs(currentScale - fitScale) < fitScale * 0.01f) {
            // 极近 fit 才吸附，避免快速捏合停在 1.02 时松手大跳
            currentScale = fitScale
        }
        if (contentFitsAt(currentScale)) {
            centerAtScale(currentScale)
        } else {
            clampTranslation()
        }
        applyMatrix()
        invalidate()
        val dPan = max(abs(transX - before.second), abs(transY - before.third))
        android.util.Log.i(
            "MangaZoom",
            "imgSettle sc ${before.first}→$currentScale " +
                "pan (${before.second},${before.third})→($transX,$transY) dPan=$dPan",
        )
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
