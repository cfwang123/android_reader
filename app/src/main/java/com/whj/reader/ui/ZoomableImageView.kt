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
 * 单图查看：双指缩放、单指平移/惯性、双击缩放切换。
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

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tmpVals = FloatArray(9)

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f
    private var transX = 0f
    private var transY = 0f

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
    /** 本手势在放大态下开始，禁止横向切页 */
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
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
                if (currentScale < minScale) {
                    currentScale = minScale
                    clampTranslation()
                    applyMatrix()
                    invalidate()
                }
            }
        },
    )

    /**
     * 仅处理双击缩放、中心单击（确认后）。
     * **侧边翻页在 ACTION_UP 里立刻处理**，绝不走 onSingleTapConfirmed（双击等待 ~300ms）。
     */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                abortFling()
                val target = if (currentScale < minScale * 1.4f) {
                    min(minScale * 2.5f, maxScale)
                } else {
                    minScale
                }
                val factor = target / currentScale.coerceAtLeast(0.001f)
                currentScale = target
                transX = e.x - (e.x - transX) * factor
                transY = e.y - (e.y - transY) * factor
                clampTranslation()
                applyMatrix()
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 侧边已在 UP 处理；中心等双击确认后再响应
                if (isSideZone(e.x)) return true
                onCenterTap?.invoke()
                return true
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
        bitmap = bmp
        resetTransform()
        invalidate()
    }

    fun isZoomed(): Boolean = currentScale > minScale * 1.05f

    private fun resetTransform() {
        val bmp = bitmap
        currentScale = 1f
        minScale = 1f
        maxScale = 5f
        transX = 0f
        transY = 0f
        if (bmp == null || width <= 0 || height <= 0 || bmp.width <= 0 || bmp.height <= 0) {
            matrix.reset()
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = min(vw / bw, vh / bh)
        minScale = scale
        maxScale = max(scale * 5f, scale * 2f)
        currentScale = scale
        transX = (vw - bw * scale) / 2f
        transY = (vh - bh * scale) / 2f
        applyMatrix()
    }

    private fun applyMatrix() {
        matrix.reset()
        matrix.setScale(currentScale, currentScale)
        matrix.postTranslate(transX, transY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) resetTransform()
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
                pageSwipeLocked = isZoomed()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pageSwipeLocked = true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (pinching || event.pointerCount > 1) {
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

                if (isZoomed() || pageSwipeLocked) {
                    transX += dx
                    transY += dy
                    clampTranslation()
                    applyMatrix()
                    invalidate()
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

                if (!pinching && moved) {
                    if (isZoomed() || pageSwipeLocked) {
                        if (abs(vx) > minFlingV || abs(vy) > minFlingV) {
                            startPanFling(vx, vy)
                        } else {
                            clampTranslation()
                            applyMatrix()
                            invalidate()
                        }
                    } else {
                        val totalDx = event.x - downX
                        val totalDy = event.y - downY
                        val horizontal = abs(totalDx) > abs(totalDy) * 1.1f
                        val byDist = horizontal && abs(totalDx) >= swipeMinDistance
                        val byVel = abs(vx) >= minFlingV * 1.2f && abs(vx) > abs(vy) * 1.2f
                        if (byDist || byVel) {
                            val forward = if (byVel) vx < 0f else totalDx < 0f
                            onSwipePage?.invoke(forward)
                        }
                    }
                } else if (!pinching &&
                    !moved &&
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    isSideZone(event.x) &&
                    !isZoomed()
                ) {
                    // 侧边轻点：松手立刻翻页（无双击确认延迟）
                    fireSideTap(event.x)
                }
            }
        }
        return true
    }

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        val next = (currentScale * factor).coerceIn(minScale * 0.9f, maxScale)
        val real = next / currentScale.coerceAtLeast(0.001f)
        if (abs(real - 1f) < 0.001f) return
        // 焦点缩放：新平移 = focus - (focus - oldTrans) * real
        transX = focusX - (focusX - transX) * real
        transY = focusY - (focusY - transY) * real
        currentScale = next
        clampTranslation()
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
