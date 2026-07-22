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
     * 漫画单图捏合（满幅度、抗跳动）：
     * - 比例：startScale * (span/startSpan)，不限制步进
     * - 缩放：始终 [centerAtScale] 等价于绕视口中心缩放，任意大步进都连续
     * - >fit 时再叠加双指焦点位移做平移（缩放本身不跟侧边焦点，避免跨 fit 与 clamp 对打）
     *
     * adb 实测：旧「焦点缩放 + clamp」在贴边快速跨 fit 时 dPan 可达 200~450。
     */
    private var pinchStartScale = 1f
    private var pinchStartSpan = 1f
    private var pinchFrozen = false
    private var pinchLastFocusX = 0f
    private var pinchLastFocusY = 0f
    private var pinchHasLastFocus = false
    /** >fit 时相对「居中 pan」的偏移，随缩放同比变化并叠双指平移 */
    private var pinchOffX = 0f
    private var pinchOffY = 0f
    private val maxPinchFocusJump =
        (80f * resources.displayMetrics.density).coerceAtLeast(48f)
    /**
     * 双指结束后到下一次 ACTION_DOWN 前禁止 pan fling。
     * 否则最后一指抬起时 VelocityTracker 速度很大，会惯性滑一下 =「松手跳」。
     */
    private var blockPanFlingAfterPinch = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                pageSwipeLocked = true
                pinchFrozen = false
                blockPanFlingAfterPinch = true
                abortFling() // 掐掉进行中的惯性
                pinchStartScale = currentScale.coerceAtLeast(0.001f)
                pinchStartSpan = detector.currentSpan.coerceAtLeast(1f)
                pinchLastFocusX = detector.focusX
                pinchLastFocusY = detector.focusY
                pinchHasLastFocus = false
                val (cx, cy) = centerPanFor(currentScale)
                if (currentScale <= fitScale * 1.001f) {
                    pinchOffX = 0f
                    pinchOffY = 0f
                    centerAtScale(currentScale)
                    applyMatrix()
                } else {
                    pinchOffX = transX - cx
                    pinchOffY = transY - cy
                }
                android.util.Log.i(
                    "MangaZoom",
                    "imgBegin sc=$currentScale fit=$fitScale span=$pinchStartSpan " +
                        "pan=($transX,$transY) off=($pinchOffX,$pinchOffY) " +
                        "focus=(${detector.focusX},${detector.focusY})",
                )
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
                val beforeSc = currentScale
                val beforeTx = transX
                val beforeTy = transY
                pinching = false
                pageSwipeLocked = true
                pinchFrozen = false
                pinchHasLastFocus = false
                // 直到全部手指抬起前：禁止 fling + 禁止剩余单指 pan
                blockPanFlingAfterPinch = true
                abortFling()
                settleAfterPinch()
                // 同步 last 点，避免后续若误 pan 用旧坐标产生大 dx
                lastX = detector.focusX
                lastY = detector.focusY
                val dPan = max(abs(transX - beforeTx), abs(transY - beforeTy))
                android.util.Log.i(
                    "MangaZoom",
                    "imgScaleEnd sc ${"%.4f".format(beforeSc)}→${"%.4f".format(currentScale)} " +
                        "pan (${"%.1f".format(beforeTx)},${"%.1f".format(beforeTy)})→" +
                        "(${"%.1f".format(transX)},${"%.1f".format(transY)}) " +
                        "dPan=${"%.1f".format(dPan)} settleJUMP=${dPan > 20f} " +
                        "blockMoveAndFling=true",
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

    /** 视口中心对应的 pan（任意 scale 连续） */
    private fun centerPanFor(scale: Float): Pair<Float, Float> {
        val bmp = bitmap ?: return 0f to 0f
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val sc = scale.coerceAtLeast(0.001f)
        return (vw - bmp.width * sc) / 2f to (vh - bmp.height * sc) / 2f
    }

    private fun centerAtScale(scale: Float) {
        val (cx, cy) = centerPanFor(scale)
        transX = cx
        transY = cy
    }

    private fun applyPinchFrame(focusX: Float, focusY: Float, span: Float) {
        val startSpan = pinchStartSpan.coerceAtLeast(1f)
        val s = span.coerceAtLeast(1f)
        val ratio = (s / startSpan).coerceIn(0.05f, 20f)
        val next = (pinchStartScale * ratio).coerceIn(minScale, maxScale)
        val prevScale = currentScale.coerceAtLeast(0.001f)
        val prevTx = transX
        val prevTy = transY

        // 缩放：始终走居中 pan 几何（满幅度也 dContent=0）
        currentScale = next
        val (cx, cy) = centerPanFor(next)

        if (next <= fitScale * 1.001f) {
            pinchOffX = 0f
            pinchOffY = 0f
            transX = cx
            transY = cy
        } else {
            // >fit：偏移随缩放同比变化，再叠双指平移
            val f = next / prevScale
            if (prevScale > fitScale * 1.001f && abs(f - 1f) > 0.0001f) {
                pinchOffX *= f
                pinchOffY *= f
            } else if (prevScale <= fitScale * 1.001f) {
                // 刚过 fit：从 0 偏移起步
                pinchOffX = 0f
                pinchOffY = 0f
            }
            if (pinchHasLastFocus) {
                val dx = focusX - pinchLastFocusX
                val dy = focusY - pinchLastFocusY
                if (abs(dx) <= maxPinchFocusJump && abs(dy) <= maxPinchFocusJump) {
                    pinchOffX += dx
                    pinchOffY += dy
                }
            }
            transX = cx + pinchOffX
            transY = cy + pinchOffY
            clampTranslation()
            // clamp 后回写偏移，避免越界累积
            pinchOffX = transX - cx
            pinchOffY = transY - cy
        }

        pinchLastFocusX = focusX
        pinchLastFocusY = focusY
        pinchHasLastFocus = true

        applyMatrix()
        invalidate()

        val dPan = max(abs(transX - prevTx), abs(transY - prevTy))
        val prevCx = (width / 2f - prevTx) / prevScale
        val prevCy = (height / 2f - prevTy) / prevScale
        val nowCx = (width / 2f - transX) / currentScale.coerceAtLeast(0.001f)
        val nowCy = (height / 2f - transY) / currentScale.coerceAtLeast(0.001f)
        val dContent = max(abs(nowCx - prevCx), abs(nowCy - prevCy)) *
            min(prevScale, currentScale)
        val cross = (prevScale - fitScale) * (currentScale - fitScale) <= 0f
        if (cross || dContent > 8f || abs(currentScale / prevScale - 1f) > 0.2f) {
            android.util.Log.i(
                "MangaZoom",
                "imgFrame sc ${"%.4f".format(prevScale)}→${"%.4f".format(currentScale)} " +
                    "fit=${"%.4f".format(fitScale)} ratio=${"%.3f".format(ratio)} " +
                    "pan (${"%.1f".format(prevTx)},${"%.1f".format(prevTy)})→" +
                    "(${"%.1f".format(transX)},${"%.1f".format(transY)}) " +
                    "dPan=${"%.1f".format(dPan)} dContent=${"%.1f".format(dContent)} " +
                    "JUMP=${dContent > 20f}",
            )
        }
    }

    /**
     * adb：run-as 写 files/debug_manga_pinch 后回前台。
     * 覆盖：快速跨 fit + 带平移松手 settle + 模拟旧版 fling 危害。
     */
    fun debugSimulateFastSidePinch() {
        val bmp = bitmap
        if (bmp == null || width <= 0 || height <= 0 || fitScale <= 0f) {
            android.util.Log.e("MangaZoom", "debugSimulate: not ready")
            return
        }
        abortFling()
        val startSc = (fitScale * 0.5f).coerceIn(minScale, maxScale)
        currentScale = startSc
        centerAtScale(startSc)
        applyMatrix()
        invalidate()
        val focusX = width * 0.12f
        val focusY = height * 0.5f
        pinchStartScale = startSc
        pinchStartSpan = 100f
        pinchHasLastFocus = false
        pinchFrozen = false
        pinchOffX = 0f
        pinchOffY = 0f
        pinching = true
        blockPanFlingAfterPinch = true
        android.util.Log.i(
            "MangaZoom",
            "debugSimulate START sc=$startSc fit=$fitScale pan=($transX,$transY) " +
                "focus=($focusX,$focusY) view=${width}x$height bmp=${bmp.width}x${bmp.height}",
        )
        // 快速跨 fit
        var fx = focusX
        var fy = focusY
        for (sp in floatArrayOf(100f, 160f, 240f, 320f)) {
            applyPinchFrame(fx, fy, sp)
        }
        // >fit 后双指同向拖一段（模拟松手前平移）
        for (i in 1..4) {
            fx += 40f
            fy += 30f
            applyPinchFrame(fx, fy, 320f)
        }
        val preEndSc = currentScale
        val preEndTx = transX
        val preEndTy = transY
        // 模拟 onScaleEnd
        pinching = false
        settleAfterPinch()
        val settleD = max(abs(transX - preEndTx), abs(transY - preEndTy))
        android.util.Log.i(
            "MangaZoom",
            "debugSimulate SETTLE sc $preEndSc→$currentScale " +
                "pan ($preEndTx,$preEndTy)→($transX,$transY) dPan=$settleD " +
                "settleJUMP=${settleD > 20f}",
        )
        // 模拟旧逻辑：松手 fling（应被 block）
        val wouldFling = isEnlarged() && (abs(2500f) > minFlingV)
        android.util.Log.i(
            "MangaZoom",
            "debugSimulate RELEASE blockFling=$blockPanFlingAfterPinch " +
                "wouldHaveFling=$wouldFling sc=$currentScale pan=($transX,$transY) " +
                "rel=${getRelativeZoom()}",
        )
        if (!blockPanFlingAfterPinch && wouldFling) {
            startPanFling(2500f, 1800f)
            android.util.Log.e("MangaZoom", "debugSimulate BAD: fling started on release")
        } else {
            android.util.Log.i("MangaZoom", "debugSimulate OK: no fling on release")
        }
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
                // 新单指才允许 fling；双指结束后到此处前保持 block
                blockPanFlingAfterPinch = false
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
                // 双指过程中、或双指刚结束尚未全部抬起：禁止单指 pan
                // （日志：scaleEnd pan 稳定，release 时 pan 已被最后一指 MOVE 拖飞）
                if (pinching ||
                    event.pointerCount > 1 ||
                    scaleDetector.isInProgress ||
                    blockPanFlingAfterPinch
                ) {
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

                // 轻点优先：侧边立即翻页（含放大态）；中心开菜单
                if (isTap) {
                    if (isSideZone(event.x)) {
                        fireSideTap(event.x)
                    } else {
                        onCenterTap?.invoke()
                    }
                    return@onTouchEvent true
                }

                val multiOrPinch =
                    pinching || pageSwipeLocked || scaleDetector.isInProgress ||
                        blockPanFlingAfterPinch
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
                } else if (isEnlarged() || pageSwipeLocked || blockPanFlingAfterPinch) {
                    // 双指缩放后松手：禁止 fling（旧逻辑会用最后一指速度惯性滑=跳）
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        moved &&
                        isEnlarged() &&
                        !blockPanFlingAfterPinch
                    ) {
                        if (abs(vx) > minFlingV || abs(vy) > minFlingV) {
                            startPanFling(vx, vy)
                            android.util.Log.i(
                                "MangaZoom",
                                "releaseFling vx=$vx vy=$vy pan=($transX,$transY)",
                            )
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP &&
                        blockPanFlingAfterPinch
                    ) {
                        android.util.Log.i(
                            "MangaZoom",
                            "releaseNoFling afterPinch sc=$currentScale " +
                                "pan=($transX,$transY) vx=$vx vy=$vy " +
                                "(move also blocked until next DOWN)",
                        )
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onTransformChanged?.invoke()
                    }
                }
            }
        }
        return true
    }

    /** 松手：只夹边。居中缩放已保证 ≤fit 时 pan 正确，不再二次大改。 */
    private fun settleAfterPinch() {
        val before = Triple(currentScale, transX, transY)
        if (currentScale < minScale) {
            currentScale = minScale
            centerAtScale(currentScale)
        }
        clampTranslation()
        applyMatrix()
        invalidate()
        val dPan = max(abs(transX - before.second), abs(transY - before.third))
        android.util.Log.i(
            "MangaZoom",
            "imgSettle sc ${"%.4f".format(before.first)}→${"%.4f".format(currentScale)} " +
                "pan (${"%.1f".format(before.second)},${"%.1f".format(before.third)})→" +
                "(${"%.1f".format(transX)},${"%.1f".format(transY)}) dPan=${"%.1f".format(dPan)} " +
                "JUMP=${dPan > 80f}",
        )
    }

    private fun startPanFling(vx: Float, vy: Float) {
        if (blockPanFlingAfterPinch) {
            android.util.Log.i("MangaZoom", "startPanFling blocked after pinch")
            return
        }
        val (xMin, xMax, yMin, yMax) = panRange()
        android.util.Log.i(
            "MangaZoom",
            "startPanFling vx=$vx vy=$vy from=($transX,$transY)",
        )
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
