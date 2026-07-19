package com.whj.reader.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 在图片上叠加 OCR 文字层（50% 黑底白字），
 * 选择逻辑对齐 PDF：长按定锚点，拖动扩展连续区间。
 */
class OcrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onSelectionChanged(selected: List<TfliteOcrEngine.LineResult>)
        /** 长按开始选区后回调（不自动复制） */
        fun onSelectionStarted()
    }

    var listener: Listener? = null

    private var bitmap: Bitmap? = null
    private var lines: List<TfliteOcrEngine.LineResult> = emptyList()

    /** 是否绘制识别文字层（黑底白字）；隐藏后仍可选中/显示选区 */
    var textLayerVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 与 PDF 相同：锚点 + 起止下标（闭区间，按行） */
    private var selAnchor = -1
    private var selStart = -1
    private var selEnd = -1

    private var selecting = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 图片 → 视图 变换 */
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val tmpPts = FloatArray(8)
    private val path = Path()
    private val boxRect = RectF()

    // 选区高亮（与 PDF 一致蓝）
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x663B82F6
    }
    private val selectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0xCC3B82F6.toInt()
    }
    private val faintBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x66FFFFFF
    }
    // 文字层：50% 黑底 + 白字
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x80000000.toInt() // 50% 黑
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT
        isSubpixelText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 180, 190, 200)
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    private val gesture = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (selecting) return false
                // 点空白取消选区（与 PDF 点空白清选一致）
                if (hasSelection()) {
                    clearSelection()
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                val idx = hitTest(e.x, e.y)
                if (idx < 0) return
                selecting = true
                parent?.requestDisallowInterceptTouchEvent(true)
                selAnchor = idx
                selStart = idx
                selEnd = idx
                notifySelection()
                invalidate()
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                listener?.onSelectionStarted()
            }
        },
    )

    fun setImage(bmp: Bitmap?) {
        bitmap = bmp
        clearSelection(silent = true)
        recomputeMatrix()
        invalidate()
    }

    fun setLines(newLines: List<TfliteOcrEngine.LineResult>) {
        lines = newLines
        clearSelection(silent = true)
        invalidate()
    }

    fun hasSelection(): Boolean =
        selStart >= 0 && selEnd >= 0 && selStart <= selEnd && lines.isNotEmpty()

    fun getSelectedLines(): List<TfliteOcrEngine.LineResult> {
        if (!hasSelection()) return emptyList()
        val a = selStart.coerceIn(0, lines.lastIndex)
        val b = selEnd.coerceIn(0, lines.lastIndex)
        return lines.subList(a, b + 1)
    }

    fun getAllText(): String =
        lines.joinToString("\n") { it.text }

    fun getSelectedText(): String =
        getSelectedLines().joinToString("\n") { it.text }

    /** 当前选中行闭区间；无选中时 null */
    fun getSelectionLineRange(): IntRange? {
        if (!hasSelection()) return null
        val a = selStart.coerceIn(0, lines.lastIndex)
        val b = selEnd.coerceIn(0, lines.lastIndex)
        return a..b
    }

    fun lineCount(): Int = lines.size

    fun selectAll() {
        if (lines.isEmpty()) return
        selAnchor = 0
        selStart = 0
        selEnd = lines.lastIndex
        notifySelection()
        invalidate()
    }

    fun clearSelection(silent: Boolean = false) {
        val had = hasSelection()
        selAnchor = -1
        selStart = -1
        selEnd = -1
        selecting = false
        if (had || !silent) {
            if (!silent) notifySelection()
            invalidate()
        }
    }

    private fun notifySelection() {
        listener?.onSelectionChanged(getSelectedLines())
    }

    private fun applyRange(anchor: Int, current: Int) {
        if (lines.isEmpty()) return
        val a = anchor.coerceIn(0, lines.lastIndex)
        val c = current.coerceIn(0, lines.lastIndex)
        selAnchor = a
        selStart = min(a, c)
        selEnd = max(a, c)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMatrix()
    }

    private fun recomputeMatrix() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = min(width / bw, height / bh)
        val dx = (width - bw * scale) / 2f
        val dy = (height - bh * scale) / 2f
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        imageMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText("选择图片或拍照", width / 2f, height / 2f, emptyPaint)
            return
        }
        canvas.drawBitmap(bmp, imageMatrix, null)

        for ((i, line) in lines.withIndex()) {
            val box = line.box ?: continue
            if (box.size < 8) continue
            mapBoxToView(box)
            boxRect.set(
                minOf(tmpPts[0], tmpPts[2], tmpPts[4], tmpPts[6]),
                minOf(tmpPts[1], tmpPts[3], tmpPts[5], tmpPts[7]),
                maxOf(tmpPts[0], tmpPts[2], tmpPts[4], tmpPts[6]),
                maxOf(tmpPts[1], tmpPts[3], tmpPts[5], tmpPts[7]),
            )
            val left = boxRect.left
            val top = boxRect.top
            val right = boxRect.right
            val bottom = boxRect.bottom
            val boxH = max(8f, bottom - top)
            val boxW = max(8f, right - left)

            if (textLayerVisible) {
                // 文字层：50% 黑底 + 白字
                canvas.drawRect(boxRect, textBgPaint)
                textPaint.textSize = (boxH * 0.78f).coerceIn(16f, 64f)
                val save = canvas.save()
                canvas.clipRect(boxRect)
                val fm = textPaint.fontMetrics
                val textY = top + (boxH - fm.ascent - fm.descent) / 2f - fm.ascent
                canvas.drawText(ellipsize(line.text, boxW - 8f), left + 4f, textY, textPaint)
                canvas.restoreToCount(save)
            }

            // 选区高亮（叠在文字层上；隐藏文字层时也显示选区框）
            if (hasSelection() && i in selStart..selEnd) {
                path.reset()
                path.moveTo(tmpPts[0], tmpPts[1])
                path.lineTo(tmpPts[2], tmpPts[3])
                path.lineTo(tmpPts[4], tmpPts[5])
                path.lineTo(tmpPts[6], tmpPts[7])
                path.close()
                canvas.drawPath(path, selectionPaint)
                canvas.drawPath(path, selectionStrokePaint)
            } else if (!textLayerVisible) {
                // 隐藏文字层时画淡框，便于感知文字位置
                path.reset()
                path.moveTo(tmpPts[0], tmpPts[1])
                path.lineTo(tmpPts[2], tmpPts[3])
                path.lineTo(tmpPts[4], tmpPts[5])
                path.lineTo(tmpPts[6], tmpPts[7])
                path.close()
                canvas.drawPath(path, faintBoxPaint)
            }
        }
    }

    private fun ellipsize(text: String, maxW: Float): String {
        if (textPaint.measureText(text) <= maxW) return text
        var end = text.length
        val ellipsis = "…"
        while (end > 0 && textPaint.measureText(text, 0, end) + textPaint.measureText(ellipsis) > maxW) {
            end--
        }
        return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
    }

    private fun mapBoxToView(box: FloatArray) {
        for (i in 0 until 8) tmpPts[i] = box[i]
        imageMatrix.mapPoints(tmpPts)
    }

    private fun hitTest(vx: Float, vy: Float): Int {
        val p = floatArrayOf(vx, vy)
        inverseMatrix.mapPoints(p)
        val ix = p[0]
        val iy = p[1]
        // 最近行：先精确命中框，否则用中心距离（拖动经过行间空隙也能选到）
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in lines.indices) {
            val box = lines[i].box ?: continue
            if (box.size < 8) continue
            if (pointInQuad(ix, iy, box)) return i
            val cx = (box[0] + box[2] + box[4] + box[6]) / 4f
            val cy = (box[1] + box[3] + box[5] + box[7]) / 4f
            val d = abs(ix - cx) + abs(iy - cy)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        // 拖动时允许一定容差吸附到最近行
        return if (selecting && best >= 0) best else -1
    }

    private fun pointInQuad(x: Float, y: Float, box: FloatArray): Boolean {
        val xs = floatArrayOf(box[0], box[2], box[4], box[6])
        val ys = floatArrayOf(box[1], box[3], box[5], box[7])
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        // 略放大命中区域，方便拖选
        val pad = 4f
        return x in (minX - pad)..(maxX + pad) && y in (minY - pad)..(maxY + pad)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selecting = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting && selAnchor >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val idx = hitTest(event.x, event.y)
                    if (idx >= 0) {
                        val oldS = selStart
                        val oldE = selEnd
                        applyRange(selAnchor, idx)
                        if (oldS != selStart || oldE != selEnd) {
                            notifySelection()
                            invalidate()
                        }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selecting) {
                    selecting = false
                    // 松手后保持选区，便于点「复制选中」
                    notifySelection()
                    return true
                }
            }
        }
        gesture.onTouchEvent(event)
        return true
    }
}
