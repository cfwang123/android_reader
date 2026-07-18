package com.whj.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import com.whj.reader.R
import com.whj.reader.model.Paragraph
import com.whj.reader.model.ReadStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 纯自绘虚拟阅读。
 *
 * 性能原则：
 * - 翻页/滑动只改 scrollY
 * - onDraw 只画缓存行表，无分配、无断行
 * - 断行在空闲 fill 中做；单帧有时间预算（~4ms）
 * - tops 用估算，测量后不 O(n) 平移全书
 * - 侧边：抬起且未移动才翻页（移动=拖动）
 */
class VirtualReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onZoneTap: ((zone: Int) -> Unit)? = null
    var onScrollChangedListener: ((firstVisibleParagraph: Int) -> Unit)? = null
    /** 选区菜单：从本段开始朗读 */
    var onReadFromParagraph: ((paragraphIndex: Int) -> Unit)? = null
    /**
     * 左/右边缘上下滑动调节。
     * @param isLeft true=左边缘
     * @param direction +1 上滑，-1 下滑
     */
    var onEdgeAdjust: ((isLeft: Boolean, direction: Int) -> Unit)? = null
    /** 左/右边缘是否启用（由设置决定；未启用则走普通滚动） */
    var leftEdgeEnabled: Boolean = true
    var rightEdgeEnabled: Boolean = true

    private var paragraphs: List<Paragraph> = emptyList()
    private var style: ReadStyle = ReadStyle()
    private var textColor: Int = 0xFF2C2C2C.toInt()
    private var highlightColor: Int = 0x66FFE082.toInt()
    private var highlightParagraph: Int = -1

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
    }
    private val chapterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        isFakeBoldText = true
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x663B82F6 }

    private val contentPaddingH: Int
    private val contentPaddingV: Int
    private var contentWidth: Int = 0

    private var tops: FloatArray = FloatArray(0)
    private var heights: FloatArray = FloatArray(0)
    private var totalHeight: Float = 0f

    private val layoutCache = object : LruCache<Int, ParaLayout>(CACHE_SIZE) {}

    private var scrollYF = 0f
    private val scroller = OverScroller(context)
    private val density = resources.displayMetrics.density
    private var avgCharWidth = 24f
    private var lineHeight = 24f
    private var fontAscent = 0f

    private var selStartPara = -1
    private var selStartOff = 0
    private var selEndPara = -1
    private var selEndOff = 0
    private var selecting = false
    private var actionMode: ActionMode? = null

    private var lastScrollNotifyMs = 0L
    private var lastProgressUiMs = 0L
    private var interactingUntilMs = 0L

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var downZone = 1
    /** 0=无 1=左 2=右 */
    private var edgeSide = 0
    private var edgeAccum = 0f
    private val edgeWidthPx: Float get() = max(40f * density, width * 0.12f)
    private val edgeStepPx: Float get() = 28f * density

    // 复用缓冲，减少 GC
    private var charWidthsBuf = FloatArray(512)
    private val lineList = ArrayList<LineRec>(64)

    private var fillRunning = false
    private val fillBlankRunnable = object : Runnable {
        override fun run() {
            fillRunning = false
            if (paragraphs.isEmpty() || contentWidth <= 0) return
            val busy = SystemClock.uptimeMillis() < interactingUntilMs
            val timeBudget = if (busy) FILL_TIME_BUSY_MS else FILL_TIME_IDLE_MS
            val deadline = SystemClock.uptimeMillis() + timeBudget
            val more = fillVisibleBlanksUntil(deadline)
            if (more || hasVisibleBlank()) {
                invalidate()
                scheduleFill(if (busy) 4L else 0L)
            } else {
                val first = firstVisibleParagraph()
                val aheadMore = fillRangeUntil(
                    first,
                    first + PREFETCH_AHEAD,
                    SystemClock.uptimeMillis() + FILL_TIME_IDLE_MS,
                )
                if (aheadMore) {
                    invalidate()
                    scheduleFill(8L)
                }
            }
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (!scroller.isFinished) scroller.forceFinished(true)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (moved) return
                // 长按开始选区，不立刻弹菜单（否则会吃掉后续拖动手势）
                parent?.requestDisallowInterceptTouchEvent(true)
                val hit = hitTest(e.x, e.y, allowBuild = true) ?: return
                selecting = true
                // 起点：英文扩词，中文先落在该字上，拖动再扩成块
                beginSelectionAt(hit)
                invalidate()
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (selecting) {
                    // 选区拖动由 onTouchEvent MOVE 处理，这里拦截避免滚屏
                    return true
                }
                if (abs(distanceX) < 0.5f && abs(distanceY) < 0.5f) return false
                moved = true
                markInteracting()
                // 边缘上下滑：调节语速/字号，不滚页
                if (edgeSide != 0) {
                    // distanceY>0 表示手指上移 → 增大
                    edgeAccum += distanceY
                    val step = edgeStepPx
                    while (abs(edgeAccum) >= step) {
                        val dir = if (edgeAccum > 0f) 1 else -1
                        edgeAccum -= dir * step
                        onEdgeAdjust?.invoke(edgeSide == 1, dir)
                    }
                    return true
                }
                scrollByInternal(distanceY)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (selecting || !moved || edgeSide != 0) return false
                markInteracting()
                scroller.fling(
                    0, scrollYF.toInt(), 0, -velocityY.toInt(),
                    0, 0, 0, maxScrollY(),
                )
                ViewCompat.postInvalidateOnAnimation(this@VirtualReaderView)
                scheduleFill(8L)
                return true
            }
        },
    )

    init {
        contentPaddingH = (18 * density).toInt()
        contentPaddingV = (12 * density).toInt()
        isClickable = true
        isLongClickable = true
        applyPaintFromStyle()
    }

    private class LineRec(
        val start: Int,
        val end: Int,
        val width: Float,
        val top: Float,
        val baseline: Float,
    )

    private class ParaLayout(
        val lines: Array<LineRec>,
        val height: Float,
    )

    // ─── API ────────────────────────────────────────────────

    fun setContent(list: List<Paragraph>) {
        paragraphs = list
        clearSelection(silent = true)
        layoutCache.evictAll()
        scrollYF = 0f
        highlightParagraph = -1
        rebuildMetricsEstimateOnly()
        invalidate()
        scheduleFill(0L)
    }

    fun applyStyle(
        style: ReadStyle,
        textColor: Int,
        highlightColor: Int,
        keepAnchor: Boolean = true,
    ) {
        val anchor = if (keepAnchor && paragraphs.isNotEmpty()) captureAnchor() else null
        this.style = style
        this.textColor = textColor
        this.highlightColor = highlightColor
        applyPaintFromStyle()
        layoutCache.evictAll()
        rebuildMetricsEstimateOnly()
        if (anchor != null) restoreAnchor(anchor) else clampScroll()
        invalidate()
        scheduleFill(0L)
        notifyScroll(force = true)
    }

    fun setHighlightParagraph(index: Int) {
        if (highlightParagraph == index) return
        highlightParagraph = index
        invalidate()
    }

    fun clearHighlight() = setHighlightParagraph(-1)
    fun currentHighlight(): Int = highlightParagraph

    fun firstVisibleParagraph(): Int {
        if (paragraphs.isEmpty() || tops.isEmpty()) return 0
        var i = tops.binarySearch(scrollYF)
        if (i < 0) i = (-i - 2).coerceAtLeast(0)
        return i.coerceIn(0, paragraphs.lastIndex)
    }

    fun progressPercent(): Float {
        val max = maxScrollY()
        if (max <= 0) return if (paragraphs.isEmpty()) 0f else 100f
        return ((scrollYF / max) * 100f).coerceIn(0f, 100f)
    }

    /** 按 0~100 进度跳转（支持小数），一边拖一边可调用 */
    fun scrollToProgressPercent(percent: Float) {
        if (paragraphs.isEmpty()) return
        val p = percent.coerceIn(0f, 100f)
        val max = maxScrollY()
        scrollYF = if (max <= 0) 0f else max * (p / 100f)
        clampScroll()
        if (!scroller.isFinished) scroller.abortAnimation()
        invalidate()
        scheduleFill(0L)
        notifyScroll(force = true)
    }

    fun scrollToParagraph(index: Int) {
        if (index !in paragraphs.indices || tops.isEmpty()) return
        scrollYF = tops.getOrElse(index) { 0f }
        clampScroll()
        invalidate()
        scheduleFill(0L)
        notifyScroll(force = true)
    }

    /** 段落是否与当前视口有交集（在屏上） */
    fun isParagraphVisible(index: Int): Boolean {
        if (index !in paragraphs.indices || tops.isEmpty() || height <= 0) return false
        val top = tops[index]
        val h = layoutCache.get(index)?.let { it.height + paraSpacingPx() } ?: heights[index]
        val bottom = top + h
        val viewTop = scrollYF
        val viewBottom = scrollYF + height
        return bottom > viewTop + 1f && top < viewBottom - 1f
    }

    /**
     * 仅当目标段不在当前屏幕内时才竖直滚动；已在屏内只刷新高亮。
     */
    fun scrollToParagraphIfNeeded(index: Int) {
        if (index !in paragraphs.indices) return
        if (isParagraphVisible(index)) {
            invalidate()
            return
        }
        scrollToParagraph(index)
    }

    /**
     * 当前选区在本 View 坐标系中的包围盒（供浮动菜单定位）。
     * @return 是否有有效选区
     */
    fun getSelectionContentRect(out: Rect): Boolean {
        val norm = normalizedSelection() ?: return false
        if (tops.isEmpty()) return false
        var minTop = Float.MAX_VALUE
        var maxBottom = Float.MIN_VALUE
        var minLeft = contentWidth.toFloat()
        var maxRight = 0f

        for (p in norm.first.para..norm.second.para) {
            if (p !in paragraphs.indices) continue
            val layout = layoutCache.get(p) ?: buildAndCache(p)
            val paraTopContent = tops[p]
            val text = paragraphs[p].text
            val paint = if (paragraphs[p].isChapter) chapterPaint else textPaint
            val selStart = if (p == norm.first.para) norm.first.offset else 0
            val selEnd = if (p == norm.second.para) norm.second.offset else text.length
            if (selStart >= selEnd) continue

            for (line in layout.lines) {
                val a = maxOf(line.start, selStart)
                val b = minOf(line.end, selEnd)
                if (a >= b) continue
                val x0 = if (a == line.start) 0f else paint.measureText(text, line.start, a)
                val x1 = paint.measureText(text, line.start, b)
                val lineTop = paraTopContent + line.top
                val lineBottom = lineTop + lineHeight
                minTop = min(minTop, lineTop)
                maxBottom = max(maxBottom, lineBottom)
                minLeft = min(minLeft, x0)
                maxRight = max(maxRight, x1)
            }
        }
        if (minTop == Float.MAX_VALUE) return false

        // content → view 坐标
        val l = (contentPaddingH + minLeft).toInt()
        val t = (contentPaddingV + minTop - scrollYF).toInt()
        val r = (contentPaddingH + maxRight).toInt().coerceAtLeast(l + 1)
        val b = (contentPaddingV + maxBottom - scrollYF).toInt().coerceAtLeast(t + 1)
        out.set(l, t, r, b)
        return true
    }

    fun pageTurn(forward: Boolean, overlapLines: Int = 1): Boolean {
        if (height <= 0 || paragraphs.isEmpty()) return false
        val page = pageDeltaPx(overlapLines)
        val before = scrollYF
        scrollYF += if (forward) page else -page
        clampScroll()
        if (scrollYF == before) return false
        if (!scroller.isFinished) scroller.abortAnimation()
        markInteracting()
        invalidate()
        notifyScroll(force = false)
        scheduleFill(8L)
        return true
    }

    fun canPage(forward: Boolean): Boolean =
        if (forward) scrollYF < maxScrollY() - 0.5f else scrollYF > 0.5f

    fun lineHeightPx(): Int = lineHeight.toInt().coerceIn(20, 160)

    fun shouldUpdateProgressUi(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastProgressUiMs < 200L) return false
        lastProgressUiMs = now
        return true
    }

    private fun pageDeltaPx(overlapLines: Int): Float {
        val line = lineHeight
        return (height - contentPaddingV * 2 - line * overlapLines)
            .toFloat()
            .coerceAtLeast(line)
    }

    private fun markInteracting() {
        interactingUntilMs = SystemClock.uptimeMillis() + INTERACT_HOLD_MS
    }

    // ─── 触摸：长按拖选；轻点取消选区/翻页 ─────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 先给 GestureDetector（长按识别）
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = SystemClock.uptimeMillis()
                moved = false
                edgeAccum = 0f
                val w = width.toFloat().coerceAtLeast(1f)
                val ew = edgeWidthPx
                edgeSide = when {
                    event.x <= ew && leftEdgeEnabled -> 1
                    event.x >= w - ew && rightEdgeEnabled -> 2
                    else -> 0
                }
                downZone = when {
                    event.x < w / 3f -> 0
                    event.x > w * 2f / 3f -> 2
                    else -> 1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    // 长按后拖动：扩展选区终点（跨段块选）
                    val hit = hitTest(event.x, event.y, allowBuild = true)
                    if (hit != null) {
                        selEndPara = hit.para
                        selEndOff = hit.offset
                        // 拖出屏幕上下边缘时自动滚一点
                        autoScrollWhileSelecting(event.y)
                        invalidate()
                    }
                    return true
                }
                if (!moved) {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        moved = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (selecting) {
                    // 结束拖选，弹出复制菜单
                    selecting = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (hasSelection()) {
                        startTextActionMode()
                    } else {
                        clearSelection()
                    }
                } else if (!moved) {
                    // 轻点：若已有选区 → 一律取消；否则翻页/菜单
                    if (hasSelection()) {
                        clearSelection()
                    } else {
                        val dt = SystemClock.uptimeMillis() - downTime
                        if (dt < ViewConfiguration.getLongPressTimeout()) {
                            onZoneTap?.invoke(downZone)
                        }
                    }
                }
                edgeSide = 0
                edgeAccum = 0f
                scheduleFill(8L)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (selecting) {
                    selecting = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (hasSelection()) startTextActionMode()
                }
                edgeSide = 0
                edgeAccum = 0f
                scheduleFill(8L)
            }
        }
        return true
    }

    /** 选区拖到顶/底边缘时跟手滚动 */
    private fun autoScrollWhileSelecting(y: Float) {
        val edge = height * 0.12f
        val step = lineHeight * 2f
        when {
            y < edge -> {
                scrollYF = (scrollYF - step).coerceAtLeast(0f)
                scheduleFill(0L)
            }
            y > height - edge -> {
                scrollYF = (scrollYF + step).coerceAtMost(maxScrollY().toFloat())
                scheduleFill(0L)
            }
        }
    }

    private fun beginSelectionAt(hit: Hit) {
        val text = paragraphs.getOrNull(hit.para)?.text ?: return
        if (text.isEmpty()) return
        val s = hit.offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        val c = text.getOrNull(s)
        if (c != null && c.isLetterOrDigit() && c.code < 0x4E00) {
            // 英文：先选中整词，拖动仍可改终点
            var start = s
            var end = (s + 1).coerceAtMost(text.length)
            while (start > 0 && text[start - 1].isLetterOrDigit() && text[start - 1].code < 0x4E00) {
                start--
            }
            while (end < text.length && text[end].isLetterOrDigit() && text[end].code < 0x4E00) {
                end++
            }
            selStartPara = hit.para
            selStartOff = start
            selEndPara = hit.para
            selEndOff = end
        } else {
            // 中文/其它：起点落在该字，拖动扩展
            selStartPara = hit.para
            selStartOff = s
            selEndPara = hit.para
            selEndOff = (s + 1).coerceAtMost(text.length)
        }
    }

    // ─── 锚点 ───────────────────────────────────────────────

    private data class Anchor(val para: Int, val offset: Int)

    private fun captureAnchor(): Anchor {
        if (paragraphs.isEmpty() || tops.isEmpty()) return Anchor(0, 0)
        val para = firstVisibleParagraph()
        val layout = layoutCache.get(para) ?: return Anchor(para, 0)
        val localY = (scrollYF - tops[para]).coerceAtLeast(0f)
        val line = lineAtY(layout, localY)
        return Anchor(para, layout.lines[line].start)
    }

    private fun restoreAnchor(anchor: Anchor) {
        if (paragraphs.isEmpty() || tops.isEmpty()) return
        val para = anchor.para.coerceIn(0, paragraphs.lastIndex)
        val layout = layoutCache.get(para)
        scrollYF = if (layout == null) {
            tops[para]
        } else {
            tops[para] + layout.lines[lineForOffset(layout, anchor.offset)].top
        }
        clampScroll()
    }

    // ─── 绘制：纯缓存读取 + drawText ────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val newCw = (w - contentPaddingH * 2).coerceAtLeast(1)
        if (newCw != contentWidth) {
            contentWidth = newCw
            layoutCache.evictAll()
            rebuildMetricsEstimateOnly()
            clampScroll()
            scheduleFill(0L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paragraphs.isEmpty() || contentWidth <= 0 || tops.isEmpty()) return

        val topVisible = scrollYF - contentPaddingV
        val bottomVisible = scrollYF + height + contentPaddingV
        val spacing = paraSpacingPx()

        var start = tops.binarySearch(topVisible)
        if (start < 0) start = (-start - 2).coerceAtLeast(0)
        start = start.coerceIn(0, paragraphs.lastIndex)
        val first = (start - 1).coerceAtLeast(0)

        val tp = textPaint
        val cp = chapterPaint
        tp.color = textColor
        cp.color = textColor

        canvas.save()
        canvas.translate(contentPaddingH.toFloat(), contentPaddingV - scrollYF)

        // 从 first 起按实测/占位高度累加 y
        var i = first
        var y = tops[first]
        var syncBuilt = 0
        var needMoreFill = false

        while (i <= paragraphs.lastIndex) {
            if (y > bottomVisible) break

            var layout = layoutCache.get(i)
            // 可见区内缺布局：同步补上，避免下半屏长期空白
            // （仅对与视口相交的段，每帧限量，其余交给 fill）
            if (layout == null && y < bottomVisible && y + heights[i] > topVisible) {
                if (syncBuilt < MAX_SYNC_BUILD_PER_DRAW) {
                    layout = buildAndCache(i)
                    syncBuilt++
                } else {
                    needMoreFill = true
                }
            }

            val blockH = if (layout != null) {
                layout.height + spacing
            } else {
                // 未建好：用较保守占位，避免单段估算过大“撑满”下半屏却不画字
                heights[i].coerceAtMost(lineHeight * MAX_BLANK_PLACEHOLDER_LINES + spacing)
            }

            if (layout != null && y + blockH >= topVisible) {
                if (i == highlightParagraph) {
                    highlightPaint.color = highlightColor
                    canvas.drawRect(
                        -4f, y, contentWidth + 4f, y + layout.height, highlightPaint,
                    )
                }
                if (hasSelection() && selectionOverlaps(i)) {
                    drawSelection(canvas, i, layout, y)
                }
                val paint = if (paragraphs[i].isChapter) cp else tp
                val text = paragraphs[i].text
                val lines = layout.lines
                val lh = lineHeight
                for (li in lines.indices) {
                    val line = lines[li]
                    val lineTopAbs = y + line.top
                    if (lineTopAbs + lh < topVisible) continue
                    if (lineTopAbs > bottomVisible) break
                    canvas.drawText(
                        text, line.start, line.end, 0f, y + line.baseline, paint,
                    )
                }
            }

            y += blockH
            i++
        }
        canvas.restore()

        if (needMoreFill || hasVisibleBlank()) {
            scheduleFill(0L)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYF = scroller.currY.toFloat()
            clampScroll()
            invalidate()
            notifyScroll(force = false)
            if (scroller.isFinished) scheduleFill(0L)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fillBlankRunnable)
        fillRunning = false
        super.onDetachedFromWindow()
    }

    // ─── 空闲补空白（有时间预算） ───────────────────────────

    private fun scheduleFill(delayMs: Long) {
        // 总是按最新滚动位置重排补空白，避免被旧任务挡住
        removeCallbacks(fillBlankRunnable)
        fillRunning = true
        if (delayMs <= 0L) postOnAnimation(fillBlankRunnable)
        else postDelayed(fillBlankRunnable, delayMs)
    }

    private fun hasVisibleBlank(): Boolean {
        if (paragraphs.isEmpty() || height <= 0) return false
        val first = firstVisibleParagraph()
        val pageParas = estimateParasPerPage()
        val to = (first + pageParas + 4).coerceAtMost(paragraphs.lastIndex)
        for (i in first.coerceAtLeast(0)..to) {
            if (layoutCache.get(i) == null) return true
        }
        return false
    }

    private fun fillVisibleBlanksUntil(deadline: Long): Boolean {
        val first = firstVisibleParagraph()
        val pageParas = estimateParasPerPage()
        val from = (first - 1).coerceAtLeast(0)
        // 多补一些：对话体短段一屏可能 40+ 段
        val to = (first + pageParas + 8).coerceAtMost(paragraphs.lastIndex)
        return fillRangeUntil(from, to, deadline)
    }

    /** @return 是否还有未填空白 */
    private fun fillRangeUntil(from: Int, to: Int, deadline: Long): Boolean {
        if (paragraphs.isEmpty()) return false
        val a = from.coerceIn(0, paragraphs.lastIndex)
        val b = to.coerceIn(0, paragraphs.lastIndex)
        for (i in a..b) {
            if (SystemClock.uptimeMillis() >= deadline) return true
            if (layoutCache.get(i) != null) continue
            buildAndCache(i)
        }
        for (i in a..b) {
            if (layoutCache.get(i) == null) return true
        }
        return false
    }

    private fun estimateParasPerPage(): Int {
        if (height <= 0) return 24
        // 按「约 1.5 行一段」估算对话体短段数量，保证一屏能铺满
        val byShort = ((height / lineHeight) * 1.2f).toInt() + 8
        return byShort.coerceIn(16, 80)
    }

    // ─── 滚动 ───────────────────────────────────────────────

    private fun scrollByInternal(dy: Float) {
        scrollYF += dy
        clampScroll()
        invalidate()
        notifyScroll(force = false)
        scheduleFill(8L)
    }

    private fun maxScrollY(): Int {
        val content = totalHeight + contentPaddingV * 2
        return max(0, (content - height).toInt())
    }

    private fun clampScroll() {
        val max = maxScrollY().toFloat()
        if (scrollYF < 0f) scrollYF = 0f
        if (scrollYF > max) scrollYF = max
    }

    private fun notifyScroll(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastScrollNotifyMs < 600L) return
        lastScrollNotifyMs = now
        onScrollChangedListener?.invoke(firstVisibleParagraph())
    }

    // ─── Paint / 估算 ───────────────────────────────────────

    private fun applyPaintFromStyle() {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            style.fontSizeSp,
            resources.displayMetrics,
        )
        textPaint.textSize = sizePx
        textPaint.typeface = Typeface.DEFAULT
        textPaint.letterSpacing = style.letterSpacing
        textPaint.color = textColor

        chapterPaint.textSize = sizePx
        chapterPaint.typeface = Typeface.DEFAULT_BOLD
        chapterPaint.letterSpacing = style.letterSpacing
        chapterPaint.color = textColor

        val fm = textPaint.fontMetrics
        fontAscent = -fm.ascent
        lineHeight = (fm.descent - fm.ascent) * style.lineSpacingMult
        avgCharWidth = textPaint.measureText("国").coerceAtLeast(8f)
    }

    private fun paraSpacingPx(): Float = style.paraSpacingDp * density

    private fun rebuildMetricsEstimateOnly() {
        val n = paragraphs.size
        if (n == 0) {
            tops = FloatArray(0)
            heights = FloatArray(0)
            totalHeight = 0f
            return
        }
        if (contentWidth <= 0) {
            contentWidth =
                (resources.displayMetrics.widthPixels - contentPaddingH * 2).coerceAtLeast(1)
        }
        tops = FloatArray(n)
        heights = FloatArray(n)
        val spacing = paraSpacingPx()
        val cpl = (contentWidth / avgCharWidth).coerceAtLeast(1f)
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            val lines = ((paragraphs[i].text.length / cpl).toInt() + 1).coerceAtLeast(1)
            val h = lines * lineHeight + spacing
            heights[i] = h
            y += h
        }
        totalHeight = y
    }

    // ─── 断行：getTextWidths 一次 + 累加（快） ──────────────

    private fun buildAndCache(index: Int): ParaLayout {
        layoutCache.get(index)?.let { return it }
        val layout = buildParaLayout(index)
        layoutCache.put(index, layout)
        // 实测高度写入，并平移后续 tops，避免后段叠到前段上
        applyMeasuredHeight(index, layout.height)
        return layout
    }

    /**
     * 将 index 段高度改为实测值，后续段 tops 整体平移。
     * 仅 float 加法，相对断行开销可忽略。
     */
    private fun applyMeasuredHeight(index: Int, contentH: Float) {
        if (index !in heights.indices) return
        val newH = contentH + paraSpacingPx()
        val delta = newH - heights[index]
        if (abs(delta) <= 0.5f) {
            heights[index] = newH
            return
        }
        heights[index] = newH
        var i = index + 1
        val n = tops.size
        while (i < n) {
            tops[i] += delta
            i++
        }
        totalHeight += delta
    }

    private fun buildParaLayout(index: Int): ParaLayout {
        val text = paragraphs[index].text
        val paint = if (paragraphs[index].isChapter) chapterPaint else textPaint
        paint.color = textColor
        val maxW = contentWidth.toFloat()
        if (text.isEmpty()) {
            return ParaLayout(EMPTY_LINES, lineHeight)
        }

        val len = text.length
        if (charWidthsBuf.size < len) {
            charWidthsBuf = FloatArray(len + 64)
        }
        paint.getTextWidths(text, 0, len, charWidthsBuf)

        lineList.clear()
        var i = 0
        var lineTop = 0f
        val ascent = fontAscent
        val lh = lineHeight
        val widths = charWidthsBuf

        while (i < len) {
            if (text[i] == '\n') {
                lineList.add(LineRec(i, i + 1, 0f, lineTop, lineTop + ascent))
                lineTop += lh
                i++
                continue
            }

            // 软换行后去掉行首空白
            while (i < len && (text[i] == ' ' || text[i] == '\t')) i++
            if (i >= len) break
            if (text[i] == '\n') continue

            val lineStart = i
            var width = 0f
            var lastBreak = -1
            var lastBreakWidth = 0f
            var broke = false

            while (i < len && text[i] != '\n') {
                val w = widths[i]
                if (width + w > maxW && i > lineStart) {
                    val end = if (lastBreak > lineStart) lastBreak else i
                    val ww = if (lastBreak > lineStart) lastBreakWidth else width
                    lineList.add(LineRec(lineStart, end, ww, lineTop, lineTop + ascent))
                    lineTop += lh
                    i = end
                    broke = true
                    break
                }
                width += w
                i++
                val prev = text[i - 1]
                if (prev == ' ' || prev == '\t' || isCjkBreak(prev)) {
                    lastBreak = i
                    lastBreakWidth = width
                }
            }

            if (broke) continue

            // 整行吃到换行或文末
            if (i > lineStart) {
                lineList.add(LineRec(lineStart, i, width, lineTop, lineTop + ascent))
                lineTop += lh
            } else if (i < len && text[i] != '\n') {
                // 单字超宽
                lineList.add(LineRec(i, i + 1, widths[i], lineTop, lineTop + ascent))
                lineTop += lh
                i++
            }
        }

        if (lineList.isEmpty()) {
            lineList.add(LineRec(0, 0, 0f, 0f, ascent))
            lineTop = lh
        }
        return ParaLayout(lineList.toTypedArray(), lineTop)
    }

    private fun isCjkBreak(ch: Char): Boolean {
        val c = ch.code
        return c >= 0x4E00 || c in 0x3000..0x303F || c in 0xFF00..0xFFEF
    }

    // ─── 命中 / 选区 ────────────────────────────────────────

    private data class Hit(val para: Int, val offset: Int)

    private fun hitTest(x: Float, y: Float, allowBuild: Boolean): Hit? {
        if (paragraphs.isEmpty() || tops.isEmpty()) return null
        val contentY = y - contentPaddingV + scrollYF
        val spacing = paraSpacingPx()

        // 与 onDraw 相同：从估算 first 起累计高度定位段落
        var start = tops.binarySearch(contentY)
        if (start < 0) start = (-start - 2).coerceAtLeast(0)
        start = start.coerceIn(0, paragraphs.lastIndex)
        val first = (start - 2).coerceAtLeast(0)

        var i = first
        var py = tops[first]
        var target = first
        var paraTop = py
        while (i <= paragraphs.lastIndex) {
            if (allowBuild && layoutCache.get(i) == null) {
                buildAndCache(i)
            }
            val layout = layoutCache.get(i)
            val blockH = if (layout != null) layout.height + spacing else heights[i]
            if (contentY < py + blockH || i == paragraphs.lastIndex) {
                target = i
                paraTop = py
                break
            }
            py += blockH
            i++
        }

        val layout = layoutCache.get(target)
            ?: if (allowBuild) buildAndCache(target) else null
            ?: return Hit(target, 0)

        val localY = (contentY - paraTop).coerceAtLeast(0f)
        val line = lineAtY(layout, localY)
        val rec = layout.lines[line]
        val localX = (x - contentPaddingH).coerceAtLeast(0f)
        val paint = if (paragraphs[target].isChapter) chapterPaint else textPaint
        val text = paragraphs[target].text
        var off = rec.start
        var acc = 0f
        while (off < rec.end) {
            val w = paint.measureText(text, off, off + 1)
            if (acc + w * 0.5f >= localX) break
            acc += w
            off++
        }
        // 落到行尾时允许选到 end（exclusive）
        return Hit(target, off.coerceIn(rec.start, rec.end))
    }

    private fun lineAtY(layout: ParaLayout, localY: Float): Int {
        val lines = layout.lines
        if (lines.isEmpty()) return 0
        for (i in lines.indices) {
            val bottom = if (i + 1 < lines.size) lines[i + 1].top else layout.height
            if (localY < bottom) return i
        }
        return lines.lastIndex
    }

    private fun lineForOffset(layout: ParaLayout, offset: Int): Int {
        val lines = layout.lines
        if (lines.isEmpty()) return 0
        for (i in lines.indices) {
            if (offset < lines[i].end || i == lines.lastIndex) return i
        }
        return lines.lastIndex
    }

    private fun hasSelection(): Boolean {
        if (selStartPara < 0 || selEndPara < 0) return false
        return selStartPara != selEndPara || selStartOff != selEndOff
    }

    private fun clearSelection(silent: Boolean = false) {
        selStartPara = -1
        selStartOff = 0
        selEndPara = -1
        selEndOff = 0
        selecting = false
        actionMode?.finish()
        actionMode = null
        if (!silent) invalidate()
    }

    private fun normalizedSelection(): Pair<Hit, Hit>? {
        if (!hasSelection()) return null
        val a = Hit(selStartPara, selStartOff)
        val b = Hit(selEndPara, selEndOff)
        return if (compareHit(a, b) <= 0) a to b else b to a
    }

    private fun compareHit(a: Hit, b: Hit): Int {
        if (a.para != b.para) return a.para - b.para
        return a.offset - b.offset
    }

    private fun selectionOverlaps(para: Int): Boolean {
        val n = normalizedSelection() ?: return false
        return para in n.first.para..n.second.para
    }

    private fun drawSelection(canvas: Canvas, para: Int, layout: ParaLayout, paraTop: Float) {
        val n = normalizedSelection() ?: return
        val textLen = paragraphs[para].text.length
        val selStart = if (para == n.first.para) n.first.offset else 0
        val selEnd = if (para == n.second.para) n.second.offset else textLen
        if (selStart >= selEnd) return
        val paint = if (paragraphs[para].isChapter) chapterPaint else textPaint
        val text = paragraphs[para].text
        for (line in layout.lines) {
            val a = maxOf(line.start, selStart)
            val b = minOf(line.end, selEnd)
            if (a >= b) continue
            val x0 = if (a == line.start) 0f else paint.measureText(text, line.start, a)
            val x1 = paint.measureText(text, line.start, b)
            val top = paraTop + line.top
            canvas.drawRect(x0, top, x1, top + lineHeight, selectionPaint)
        }
    }

    private fun selectedText(): String {
        val n = normalizedSelection() ?: return ""
        if (n.first.para == n.second.para) {
            val t = paragraphs[n.first.para].text
            return t.substring(
                n.first.offset.coerceIn(0, t.length),
                n.second.offset.coerceIn(0, t.length),
            )
        }
        val sb = StringBuilder()
        val last = minOf(n.second.para, paragraphs.lastIndex)
        for (i in n.first.para..last) {
            val t = paragraphs[i].text
            when (i) {
                n.first.para -> sb.append(t.substring(n.first.offset.coerceIn(0, t.length)))
                n.second.para -> sb.append(t.substring(0, n.second.offset.coerceIn(0, t.length)))
                else -> sb.append(t)
            }
            if (i != last) sb.append("\n\n")
        }
        return sb.toString()
    }

    private fun startTextActionMode() {
        if (!hasSelection()) return
        actionMode?.finish()
        // Callback2 + onGetContentRect：浮动菜单贴在选区上/下方
        actionMode = startActionMode(
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(0, 1, 0, android.R.string.copy)
                    menu.add(0, 2, 1, R.string.select_all_paragraph)
                    menu.add(0, 3, 2, R.string.tts_read_from_here)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.itemId) {
                        1 -> {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("reader", selectedText()),
                            )
                            mode.finish()
                            clearSelection()
                            return true
                        }
                        2 -> {
                            val p = selStartPara.coerceAtLeast(0)
                            if (p in paragraphs.indices) {
                                selStartPara = p
                                selStartOff = 0
                                selEndPara = p
                                selEndOff = paragraphs[p].text.length
                                buildAndCache(p)
                                invalidate()
                                mode.invalidateContentRect()
                            }
                            return true
                        }
                        3 -> {
                            // 从选区起点所在段开始朗读
                            val norm = normalizedSelection()
                            val para = norm?.first?.para
                                ?: selStartPara.coerceAtLeast(0)
                            mode.finish()
                            clearSelection()
                            if (para in paragraphs.indices) {
                                onReadFromParagraph?.invoke(para)
                            }
                            return true
                        }
                    }
                    return false
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    actionMode = null
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    if (!getSelectionContentRect(outRect)) {
                        // 回退：触点附近
                        outRect.set(
                            (width / 2 - 40).coerceAtLeast(0),
                            (height / 3).coerceAtLeast(0),
                            (width / 2 + 40).coerceAtMost(width),
                            (height / 3 + 40).coerceAtMost(height),
                        )
                    }
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    companion object {
        private val EMPTY_LINES = emptyArray<LineRec>()
        private const val CACHE_SIZE = 400
        private const val FILL_TIME_IDLE_MS = 8L
        private const val FILL_TIME_BUSY_MS = 4L
        private const val PREFETCH_AHEAD = 24
        private const val INTERACT_HOLD_MS = 80L
        /** 每帧绘制时最多同步补多少段可见空白 */
        private const val MAX_SYNC_BUILD_PER_DRAW = 12
        /** 未建布局时占位高度上限（行数），避免估算过大撑出半屏空白 */
        private const val MAX_BLANK_PLACEHOLDER_LINES = 4f
    }
}
