package com.whj.reader.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/** 目录/书签 ViewPager2 内竖直列表：优先竖滚，明显横滑才切换页 */
object TocVpScrollHelper {
    private object AttachedMarker

    fun attachVerticalList(list: RecyclerView, pager: ViewPager2) {
        if (list.tag === AttachedMarker) return
        list.tag = AttachedMarker
        val slop = ViewConfiguration.get(list.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        pager.isUserInputEnabled = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(e.x - downX)
                        val dy = abs(e.y - downY)
                        if (dx > slop || dy > slop) {
                            pager.isUserInputEnabled = dx > dy
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pager.isUserInputEnabled = true
                    }
                }
                return false
            }
        })
    }
}
