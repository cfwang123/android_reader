package com.whj.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * 两屏菜单分页：保留滑动惯性；fling 时落到惯性方向的一屏，
 * 慢速松手则吸附到最近一屏。
 */
class MenuPagerScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var pageCount: Int = 2
    var onPageSettled: ((page: Int) -> Unit)? = null

    private val minFlingVelocity =
        ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var settlingByUs = false
    private val settleEnd = Runnable {
        settlingByUs = false
        notifySettled()
    }

    override fun fling(velocityX: Int) {
        val w = width
        if (w <= 0 || pageCount <= 1) {
            super.fling(velocityX)
            return
        }
        // HSV：velocityX > 0 表示内容向左滚（scrollX 增大）= 手指左滑
        val target = targetPageForFling(velocityX)
        settleToPage(target)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 无 fling 时 super 不会调 fling()，松手后吸附最近屏
                postDelayed({
                    if (!settlingByUs) {
                        val w = width
                        if (w > 0) {
                            val nearest = nearestPage()
                            if (scrollX != nearest * w) {
                                settleToPage(nearest)
                            } else {
                                notifySettled()
                            }
                        }
                    }
                }, 32L)
            }
        }
        return handled
    }

    private fun targetPageForFling(velocityX: Int): Int {
        val w = width.coerceAtLeast(1)
        val maxPage = (pageCount - 1).coerceAtLeast(0)
        // 当前所在页（向下取整）与最近页
        val floorPage = (scrollX / w).coerceIn(0, maxPage)
        val nearest = nearestPage()
        return when {
            abs(velocityX) < minFlingVelocity -> nearest
            velocityX > 0 -> (floorPage + 1).coerceAtMost(maxPage)
            else -> floorPage.coerceAtLeast(0)
        }
    }

    private fun nearestPage(): Int {
        val w = width.coerceAtLeast(1)
        val maxPage = (pageCount - 1).coerceAtLeast(0)
        return ((scrollX + w / 2f) / w).toInt().coerceIn(0, maxPage)
    }

    fun settleToPage(page: Int, smooth: Boolean = true) {
        val w = width
        if (w <= 0) return
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val x = p * w
        settlingByUs = true
        removeCallbacks(settleEnd)
        if (smooth) {
            smoothScrollTo(x, 0)
            // smoothScroll 时长约 250–400ms
            postDelayed(settleEnd, 380L)
        } else {
            scrollTo(x, 0)
            settlingByUs = false
            notifySettled()
        }
    }

    private fun notifySettled() {
        val w = width.coerceAtLeast(1)
        val page = ((scrollX + w / 2f) / w).toInt()
            .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        onPageSettled?.invoke(page)
    }
}
