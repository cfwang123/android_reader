package com.whj.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 页面裁剪框：红线矩形 + 8 个蓝色拖动手柄（四角 + 四边中点）。
 * 裁切以相对 [imageRect] 的边距比例表示（0~0.35）。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 页面位图在 View 中的显示区域（fitCenter 后） */
    var imageRect = RectF()
        set(value) {
            field = value
            invalidate()
        }

    /** 左、上、右、下裁边比例（相对图片宽/高） */
    var cropLeft = 0f
        private set
    var cropTop = 0f
        private set
    var cropRight = 0f
        private set
    var cropBottom = 0f
        private set

    var onCropChanged: ((l: Float, t: Float, r: Float, b: Float) -> Unit)? = null

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt()
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val density = resources.displayMetrics.density
    private val handleSize = 12f * density
    private val hitSlop = 22f * density
    private val minContent = 0.35f // 至少保留 35% 内容

    private enum class Handle {
        NONE, MOVE,
        TL, T, TR, R, BR, B, BL, L,
    }

    private var active = Handle.NONE
    private var lastX = 0f
    private var lastY = 0f

    fun setCrop(left: Float, top: Float, right: Float, bottom: Float) {
        cropLeft = left.coerceIn(0f, 0.35f)
        cropTop = top.coerceIn(0f, 0.35f)
        cropRight = right.coerceIn(0f, 0.35f)
        cropBottom = bottom.coerceIn(0f, 0.35f)
        clampCrop()
        invalidate()
    }

    fun cropArray(): FloatArray = floatArrayOf(cropLeft, cropTop, cropRight, cropBottom)

    private fun cropRect(): RectF {
        if (imageRect.isEmpty) return RectF()
        return RectF(
            imageRect.left + imageRect.width() * cropLeft,
            imageRect.top + imageRect.height() * cropTop,
            imageRect.right - imageRect.width() * cropRight,
            imageRect.bottom - imageRect.height() * cropBottom,
        )
    }

    private fun clampCrop() {
        val maxL = 1f - minContent - cropRight
        val maxR = 1f - minContent - cropLeft
        val maxT = 1f - minContent - cropBottom
        val maxB = 1f - minContent - cropTop
        cropLeft = cropLeft.coerceIn(0f, max(0f, maxL))
        cropRight = cropRight.coerceIn(0f, max(0f, maxR))
        cropTop = cropTop.coerceIn(0f, max(0f, maxT))
        cropBottom = cropBottom.coerceIn(0f, max(0f, maxB))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.isEmpty) return
        val cr = cropRect()
        // 四周遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), cr.top, dimPaint)
        canvas.drawRect(0f, cr.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cr.top, cr.left, cr.bottom, dimPaint)
        canvas.drawRect(cr.right, cr.top, width.toFloat(), cr.bottom, dimPaint)
        // 红框
        canvas.drawRect(cr, borderPaint)
        // 8 手柄
        val cx = cr.centerX()
        val cy = cr.centerY()
        drawHandle(canvas, cr.left, cr.top)
        drawHandle(canvas, cx, cr.top)
        drawHandle(canvas, cr.right, cr.top)
        drawHandle(canvas, cr.right, cy)
        drawHandle(canvas, cr.right, cr.bottom)
        drawHandle(canvas, cx, cr.bottom)
        drawHandle(canvas, cr.left, cr.bottom)
        drawHandle(canvas, cr.left, cy)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val hs = handleSize / 2f
        val r = RectF(x - hs, y - hs, x + hs, y + hs)
        canvas.drawRect(r, handlePaint)
        canvas.drawRect(r, handleStroke)
    }

    private fun hitTest(x: Float, y: Float): Handle {
        val cr = cropRect()
        val cx = cr.centerX()
        val cy = cr.centerY()
        fun near(px: Float, py: Float) = abs(x - px) <= hitSlop && abs(y - py) <= hitSlop
        return when {
            near(cr.left, cr.top) -> Handle.TL
            near(cx, cr.top) -> Handle.T
            near(cr.right, cr.top) -> Handle.TR
            near(cr.right, cy) -> Handle.R
            near(cr.right, cr.bottom) -> Handle.BR
            near(cx, cr.bottom) -> Handle.B
            near(cr.left, cr.bottom) -> Handle.BL
            near(cr.left, cy) -> Handle.L
            cr.contains(x, y) -> Handle.MOVE
            else -> Handle.NONE
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = hitTest(x, y)
                lastX = x
                lastY = y
                parent?.requestDisallowInterceptTouchEvent(active != Handle.NONE)
                return active != Handle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (active == Handle.NONE) return false
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                applyDrag(dx, dy)
                invalidate()
                onCropChanged?.invoke(cropLeft, cropTop, cropRight, cropBottom)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = active != Handle.NONE
                active = Handle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return handled
            }
        }
        return false
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val iw = imageRect.width().coerceAtLeast(1f)
        val ih = imageRect.height().coerceAtLeast(1f)
        val dL = dx / iw
        val dT = dy / ih
        when (active) {
            Handle.MOVE -> {
                // 平移整框：保持宽高
                var nl = cropLeft + dL
                var nr = cropRight - dL
                var nt = cropTop + dT
                var nb = cropBottom - dT
                if (nl < 0f) {
                    nr += nl; nl = 0f
                }
                if (nr < 0f) {
                    nl += nr; nr = 0f
                }
                if (nt < 0f) {
                    nb += nt; nt = 0f
                }
                if (nb < 0f) {
                    nt += nb; nb = 0f
                }
                cropLeft = nl; cropRight = nr; cropTop = nt; cropBottom = nb
            }
            Handle.L -> cropLeft += dL
            Handle.R -> cropRight -= dL
            Handle.T -> cropTop += dT
            Handle.B -> cropBottom -= dT
            Handle.TL -> {
                cropLeft += dL; cropTop += dT
            }
            Handle.TR -> {
                cropRight -= dL; cropTop += dT
            }
            Handle.BL -> {
                cropLeft += dL; cropBottom -= dT
            }
            Handle.BR -> {
                cropRight -= dL; cropBottom -= dT
            }
            else -> {}
        }
        clampCrop()
    }
}
