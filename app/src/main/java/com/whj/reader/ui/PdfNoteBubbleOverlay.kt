package com.whj.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.whj.reader.R

/**
 * PDF 笔记气泡：固定在阅读区右侧（不随水平平移），纵向跟随高亮位置。
 */
class PdfNoteBubbleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class BubbleItem(
        val id: String,
        val centerY: Float,
        val tintColor: Int,
    )

    private val bubbles = ArrayList<BubbleItem>()
    private val hitRects = ArrayList<Pair<String, RectF>>()
    private var bubbleDrawable: Drawable? = null

    var onBubbleClick: ((highlightId: String) -> Unit)? = null

    private val density get() = resources.displayMetrics.density
    private val bubbleSize get() = (11f * density).toInt().coerceAtLeast(9)
    private val edgeMargin get() = (2f * density)
    private val hitHalf get() = 24f * density

    fun setBubbles(items: List<BubbleItem>) {
        bubbles.clear()
        bubbles.addAll(items)
        isClickable = items.isNotEmpty()
        invalidate()
    }

    fun clearBubbles() {
        if (bubbles.isEmpty()) return
        bubbles.clear()
        hitRects.clear()
        isClickable = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isEmpty() || width <= 0 || height <= 0) return
        if (bubbleDrawable == null) {
            bubbleDrawable = ContextCompat.getDrawable(context, R.drawable.ic_note_bubble)
        }
        val template = bubbleDrawable ?: return
        val size = bubbleSize
        val x = width - paddingRight - edgeMargin - size
        hitRects.clear()
        for (item in bubbles) {
            val top = (item.centerY - size * 0.5f).coerceIn(0f, (height - size).toFloat())
            val bubble = template.constantState?.newDrawable()?.mutate() ?: template.mutate()
            DrawableCompat.setTint(bubble, item.tintColor)
            bubble.setBounds(
                x.toInt(),
                top.toInt(),
                (x + size).toInt(),
                (top + size).toInt(),
            )
            bubble.draw(canvas)
            val cx = x + size / 2f
            val cy = top + size / 2f
            hitRects.add(
                item.id to RectF(cx - hitHalf, cy - hitHalf, cx + hitHalf, cy + hitHalf),
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bubbles.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return hitRects.any { it.second.contains(event.x, event.y) }
            }
            MotionEvent.ACTION_UP -> {
                for ((id, rect) in hitRects) {
                    if (rect.contains(event.x, event.y)) {
                        onBubbleClick?.invoke(id)
                        return true
                    }
                }
            }
        }
        return false
    }
}
