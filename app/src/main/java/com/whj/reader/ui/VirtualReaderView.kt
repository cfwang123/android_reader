package com.whj.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
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
import com.whj.reader.data.HtmlRichParser
import com.whj.reader.model.Paragraph
import com.whj.reader.model.ReadStyle
import com.whj.reader.model.TextSpanStyle
import java.io.File
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
    /** 选区菜单：从段内偏移起读到文末 */
    var onReadFromParagraph: ((paragraphIndex: Int, charOffset: Int) -> Unit)? = null
    /**
     * 左/右边缘上下滑动调节。
     * @param isLeft true=左边缘
     * @param direction +1 上滑，-1 下滑
     */
    var onEdgeAdjust: ((isLeft: Boolean, direction: Int) -> Unit)? = null
    /**
     * 水平滑动翻页。
     * [forward] true = 左滑（下一页），false = 右滑（上一页）。
     */
    var onHorizontalSwipe: ((forward: Boolean) -> Unit)? = null
    /** 左/右边缘是否启用（由设置决定；未启用则走普通滚动） */
    var leftEdgeEnabled: Boolean = true
    var rightEdgeEnabled: Boolean = true
    /**
     * 为 true 时：轻点段落回调 [onParagraphPicked]，不翻页/不弹菜单。
     * 用于合成语音选择起终点。
     */
    var paragraphPickEnabled: Boolean = false
    var onParagraphPicked: ((paraIndex: Int) -> Unit)? = null
    /** 长按图片段：进入全屏看图（参数为段落索引） */
    var onImageLongPress: ((paraIndex: Int) -> Unit)? = null
    /** 点击超链接（href 原值） */
    var onLinkClick: ((href: String) -> Unit)? = null
    /**
     * 底部被遮挡高度（View 坐标 px），如 TTS 控制条 + 状态栏。
     * TTS 判可见 / 跟读滚动时从正文可视底边扣除。
     */
    var bottomObscuredPx: Float = 0f
        set(value) {
            val v = value.coerceAtLeast(0f)
            if (field == v) return
            field = v
        }

    private var paragraphs: List<Paragraph> = emptyList()
    /** 是否已触发本手势的长按（UP 时不再当点击翻页） */
    private var longPressFired = false
    private var style: ReadStyle = ReadStyle()
    private var textColor: Int = 0xFF2C2C2C.toInt()
    private var highlightColor: Int = 0x66FFE082.toInt()
    /**
     * 漫画/图集模式：段落几乎全是图片时启用。
     * - 图片强制占满正文宽度（可放大）
     * - 翻页优先按视口高度滚动（不依赖文字行）
     */
    private var imageHeavyMode: Boolean = false
    /** TTS 当前句高亮：段索引 + 段内 [start, end) */
    private var highlightParagraph: Int = -1
    private var highlightStart: Int = 0
    private var highlightEnd: Int = 0
    /** 合成导出范围高亮：段索引 inclusive，-1 表示无 */
    private var exportRangeStart: Int = -1
    private var exportRangeEnd: Int = -1

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
    }
    private val chapterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        isFakeBoldText = true
    }
    /** 富文本绘制时临时改样式，避免污染主 paint */
    private val runPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
    }
    /** &lt;pre&gt; 等宽段 */
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val spanBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x663B82F6 }
    private val exportRangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5534D399 }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 图片解码缓存（按路径），约 12MB */
    private val bitmapCache = object : LruCache<String, Bitmap>((12 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

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
    private var chapterLineHeight = 28f
    private var chapterAscent = 0f

    private var selStartPara = -1
    private var selStartOff = 0
    private var selEndPara = -1
    private var selEndOff = 0
    private var selecting = false
    /** 拖动手柄调整选区起点/终点 */
    private var draggingHandle: TextSelectionHandles.Which? = null
    private var selectionDragActive = false
    private var lastSelDragX = 0f
    private var lastSelDragY = 0f
    private var edgeScrollLoopPosted = false
    private val selectionEdgeScrollState = TextSelectionHandles.EdgeScrollState()
    private var actionMode: ActionMode? = null

    private var lastScrollNotifyMs = 0L
    private var lastProgressUiMs = 0L
    private var interactingUntilMs = 0L

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val swipeMinDistance = (48f * density).toInt().coerceAtLeast(touchSlop * 2)
    private val swipeMinVelocity = minFlingVelocity.coerceAtLeast(400)
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var downZone = 1
    /**
     * 手势轴向：null=未判定，true=水平滑（翻页），false=竖向滑（滚页）。
     * 一旦判定水平，本手势不再竖向滚动。
     */
    private var swipeHorizontal: Boolean? = null
    /** 本手势是否已由 fling 触发翻页，避免 UP 再翻一次 */
    private var horizontalSwipeConsumed = false
    /** 0=无 1=左 2=右 */
    private var edgeSide = 0
    private var edgeAccum = 0f
    /** 侧边改字号/语速热区：仅贴边 10px，避免误触正文滚动 */
    private val edgeWidthPx: Float get() = 10f
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

    private val selectionEdgeScrollLoop = object : Runnable {
        override fun run() {
            val active = selecting || draggingHandle != null
            if (!active || !selectionDragActive) {
                edgeScrollLoopPosted = false
                return
            }
            val step = TextSelectionHandles.edgeScrollStep(
                lastSelDragY,
                height.toFloat(),
                lineHeight,
                density,
                selectionEdgeScrollState,
            )
            if (step != 0f) {
                scrollYF = (scrollYF + step).coerceIn(0f, maxScrollY().toFloat())
                updateSelectionAtFinger()
                invalidate()
                invalidateSelectionActionMode()
            }
            postOnAnimation(this)
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
                longPressFired = true
                val hit = hitTest(e.x, e.y, allowBuild = true) ?: return
                val para = paragraphs.getOrNull(hit.para) ?: return
                if (para.isBlockImage) {
                    onImageLongPress?.invoke(hit.para)
                    return
                }
                // 空段：不进入选区
                if (para.text.isEmpty()) return
                // 长按开始选区，不立刻弹菜单（否则会吃掉后续拖动手势）
                parent?.requestDisallowInterceptTouchEvent(true)
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
                // 判定水平/竖直：水平则吞掉竖滚，留给 UP/fling 翻页
                if (swipeHorizontal == null) {
                    val adx = abs(e2.x - downX)
                    val ady = abs(e2.y - downY)
                    if (adx > touchSlop || ady > touchSlop) {
                        swipeHorizontal = adx > ady * 1.2f && onHorizontalSwipe != null
                    }
                }
                if (swipeHorizontal == true) {
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
                if (selecting || edgeSide != 0) return false
                // 水平 fling → 翻页（左滑下一页）
                if (onHorizontalSwipe != null &&
                    abs(velocityX) >= swipeMinVelocity &&
                    abs(velocityX) > abs(velocityY) * 1.2f
                ) {
                    markInteracting()
                    horizontalSwipeConsumed = true
                    onHorizontalSwipe?.invoke(velocityX < 0f)
                    return true
                }
                if (!moved) return false
                if (swipeHorizontal == true) return true
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
        /** 本行高度；0 表示使用默认 [lineHeight] */
        val height: Float = 0f,
    ) {
        fun lineH(defaultH: Float): Float = if (height > 0.5f) height else defaultH
    }

    private class ParaLayout(
        val lines: Array<LineRec>,
        val height: Float,
        val imagePath: String? = null,
        val imageDrawW: Float = 0f,
        val imageDrawH: Float = 0f,
        /** 章节标题段顶部留白（已含入 height，行坐标相对 0 含 padTop） */
        val padTop: Float = 0f,
    )

    // ─── API ────────────────────────────────────────────────

    fun setContent(list: List<Paragraph>) {
        paragraphs = list
        imageHeavyMode = detectImageHeavy(list)
        clearSelection(silent = true)
        layoutCache.evictAll()
        bitmapCache.evictAll()
        imageBoundsCache.clear()
        scrollYF = 0f
        highlightParagraph = -1
        highlightStart = 0
        highlightEnd = 0
        rebuildMetricsEstimateOnly()
        invalidate()
        scheduleFill(0L)
    }

    /**
     * 流式续载：用更长列表替换内容，尽量保持当前滚动位置。
     * 约定：新列表以前缀兼容方式追加（旧索引仍有效）。
     */
    fun updateContent(list: List<Paragraph>, keepScroll: Boolean = true) {
        if (list.isEmpty()) {
            setContent(list)
            return
        }
        // 若只是前缀相同的追加，可保留已有 layout 缓存（index 不变且排版输入未变）
        val oldParas = paragraphs
        val oldSize = oldParas.size
        val layoutReuseOk = canReuseLayoutCache(oldParas, list, oldSize)
        val oldY = scrollYF
        paragraphs = list
        imageHeavyMode = detectImageHeavy(list)
        if (!layoutReuseOk) {
            layoutCache.evictAll()
        }
        // 不清除 bitmap 缓存（路径仍有效）
        rebuildMetricsEstimateOnly()
        if (keepScroll) {
            scrollYF = oldY
            clampScroll()
        }
        invalidate()
        scheduleFill(0L)
    }

    /** 流式追加时：旧段排版输入未变才可复用 layout 缓存 */
    private fun canReuseLayoutCache(
        old: List<Paragraph>,
        new: List<Paragraph>,
        oldSize: Int,
    ): Boolean {
        if (oldSize <= 0 || new.size < oldSize) return false
        for (i in 0 until oldSize) {
            if (!sameLayoutInput(old[i], new[i])) return false
        }
        return true
    }

    private fun sameLayoutInput(a: Paragraph, b: Paragraph): Boolean {
        return a.text == b.text &&
            a.isChapter == b.isChapter &&
            a.align == b.align &&
            a.preformatted == b.preformatted &&
            a.spans == b.spans &&
            a.inlineImages == b.inlineImages &&
            a.imagePath == b.imagePath &&
            a.imageDisplaySize == b.imageDisplaySize
    }

    /** 全图/近全图（漫画 MOBI 等） */
    private fun detectImageHeavy(list: List<Paragraph>): Boolean {
        if (list.isEmpty()) return false
        val images = list.count { it.isBlockImage }
        if (images == 0) return false
        val textParas = list.count { !it.isBlockImage && it.text.isNotBlank() }
        // 整行图 ≥ 80% 且正文段很少
        return images * 5 >= list.size * 4 && textParas <= max(2, list.size / 10)
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

    /**
     * 高亮某段内 [start, end) 字符（TTS 当前句）。
     * index &lt; 0 或 end &lt;= start 时清除。
     */
    /** 合成导出范围高亮（段 inclusive）；start&lt;0 清除 */
    fun setExportRangeHighlight(startPara: Int, endPara: Int) {
        if (startPara < 0 || endPara < 0 || paragraphs.isEmpty()) {
            if (exportRangeStart < 0) return
            exportRangeStart = -1
            exportRangeEnd = -1
            invalidate()
            return
        }
        val a = minOf(startPara, endPara).coerceIn(0, paragraphs.lastIndex)
        val b = maxOf(startPara, endPara).coerceIn(0, paragraphs.lastIndex)
        if (exportRangeStart == a && exportRangeEnd == b) return
        exportRangeStart = a
        exportRangeEnd = b
        invalidate()
    }

    fun clearExportRangeHighlight() = setExportRangeHighlight(-1, -1)

    fun setHighlightRange(index: Int, start: Int, end: Int) {
        if (index < 0 || end <= start) {
            if (highlightParagraph < 0) return
            highlightParagraph = -1
            highlightStart = 0
            highlightEnd = 0
            invalidate()
            return
        }
        if (highlightParagraph == index && highlightStart == start && highlightEnd == end) return
        highlightParagraph = index
        highlightStart = start.coerceAtLeast(0)
        highlightEnd = end
        invalidate()
    }

    /** @deprecated 整段高亮，改用 [setHighlightRange] */
    fun setHighlightParagraph(index: Int) {
        if (index < 0) {
            clearHighlight()
            return
        }
        val len = paragraphs.getOrNull(index)?.text?.length ?: 0
        setHighlightRange(index, 0, len)
    }

    fun clearHighlight() = setHighlightRange(-1, 0, 0)
    fun currentHighlight(): Int = highlightParagraph

    fun firstVisibleParagraph(): Int {
        if (paragraphs.isEmpty() || tops.isEmpty()) return 0
        var i = tops.binarySearch(scrollYF)
        if (i < 0) i = (-i - 2).coerceAtLeast(0)
        return i.coerceIn(0, paragraphs.lastIndex)
    }

    /**
     * 屏幕内容区最上方对应的段落（用于书签）。
     * [topInsetPx]：被顶栏等遮挡的高度（View 坐标系）。
     * 返回视口可见区顶部落在的那一段（顶部可被裁切）。
     */
    fun topScreenParagraph(topInsetPx: Float = 0f): Int {
        if (paragraphs.isEmpty() || tops.isEmpty() || height <= 0) return 0
        runCatching { ensureLayoutsForViewport() }
        // 可见区顶部（扣除遮挡后）对应的内容 Y
        // 绘制：viewY = contentPaddingV + contentY - scrollYF
        // 可见 contentY 起点 ≈ scrollYF + max(0, topInset - contentPaddingV)
        val inset = topInsetPx.coerceAtLeast(0f)
        val contentY = (scrollYF + (inset - contentPaddingV).coerceAtLeast(0f))
            .coerceIn(0f, (totalHeight - 1f).coerceAtLeast(0f))
        var i = tops.binarySearch(contentY)
        if (i < 0) {
            // insertion point: -i-1 为第一个 > contentY 的下标
            i = (-i - 2).coerceAtLeast(0)
        }
        // 若下一段顶已 ≤ contentY，继续前进（估算 tops 误差）
        while (i < paragraphs.lastIndex && tops[i + 1] <= contentY + 0.5f) {
            i++
        }
        return i.coerceIn(0, paragraphs.lastIndex)
    }

    /** 屏幕正文区底部（扣除底栏/TTS 遮挡）对应的段落，用于进度显示 */
    fun bottomScreenParagraph(): Int {
        if (paragraphs.isEmpty() || tops.isEmpty() || height <= 0) return 0
        runCatching { ensureLayoutsForViewport() }
        val contentY = (scrollYF + effectiveContentHeight())
            .coerceIn(0f, (totalHeight - 1f).coerceAtLeast(0f))
        var i = tops.binarySearch(contentY)
        if (i < 0) {
            i = (-i - 2).coerceAtLeast(0)
        }
        while (i < paragraphs.lastIndex && tops[i + 1] <= contentY + 0.5f) {
            i++
        }
        return i.coerceIn(0, paragraphs.lastIndex)
    }

    /** @deprecated 使用 [topScreenParagraph] */
    fun firstFullyVisibleParagraph(): Int = topScreenParagraph(0f)

    /** 若滚到某段顶部，对应的进度 0~100 */
    fun progressPercentForParagraph(index: Int): Float {
        if (paragraphs.isEmpty() || tops.isEmpty()) return 0f
        val max = maxScrollY()
        if (max <= 0) {
            return if (index >= paragraphs.lastIndex) 100f else 0f
        }
        val y = tops.getOrElse(index.coerceIn(0, paragraphs.lastIndex)) { 0f }
        return ((y / max) * 100f).coerceIn(0f, 100f)
    }

    /**
     * 可视区内「第一个完整显示」的字：返回 (段索引, 段内字符偏移)。
     * 完整：该行文字露出 ≥ [fullVisibleRatio]。
     */
    fun firstFullyVisibleCharPosition(): Pair<Int, Int> {
        if (paragraphs.isEmpty() || tops.isEmpty() || height <= 0) {
            return 0 to 0
        }
        val fullTop = scrollYF
        val fullBottom = scrollYF + pageContentHeight()
        var para = tops.binarySearch(fullTop.coerceAtLeast(0f))
        if (para < 0) para = (-para - 2).coerceAtLeast(0)
        para = (para - 1).coerceAtLeast(0)
        while (para <= paragraphs.lastIndex) {
            if (tops[para] > fullBottom) break
            val layout = layoutCache.get(para) ?: buildAndCache(para)
            val pTop = tops[para]
            for (line in layout.lines) {
                val lt = pTop + line.top
                if (textVisibleRatio(lt, fullTop, fullBottom) >= fullVisibleRatio) {
                    val off = line.start.coerceIn(0, paragraphs[para].text.length)
                    return para to off
                }
            }
            para++
        }
        // 回退：首个与视口相交的段起点
        return firstVisibleParagraph() to 0
    }

    fun progressPercent(): Float {
        val max = maxScrollY()
        if (max <= 0) return if (paragraphs.isEmpty()) 0f else 100f
        return ((scrollYF / max) * 100f).coerceIn(0f, 100f)
    }

    /** 按屏幕底部 y（正文区下沿）计算全书进度 0~100 */
    fun progressPercentAtBottom(): Float {
        val eff = effectiveContentHeight()
        val max = maxScrollY().toFloat()
        val denom = max + eff
        if (denom <= 0f) return if (paragraphs.isEmpty()) 0f else 100f
        return ((scrollYF + eff) / denom * 100f).coerceIn(0f, 100f)
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
        // tops[i] 依赖 0..i-1 的实测高度；只测目标附近会因封面/图估算误差跳错位
        ensureMeasuredThrough(index)
        // 目标后再量一两段，减少贴底裁切
        val after = (index + 2).coerceAtMost(paragraphs.lastIndex)
        for (i in (index + 1)..after) {
            if (layoutCache.get(i) == null) buildAndCache(i)
        }
        scrollYF = tops.getOrElse(index) { 0f }
        clampScroll()
        if (!scroller.isFinished) scroller.abortAnimation()
        invalidate()
        scheduleFill(0L)
        notifyScroll(force = true)
    }

    /**
     * 保证 [0, through] 段均已实测布局，从而 [tops] 在 through 处准确。
     * 已缓存的段会快速命中；大段跳转时最多测到目标段。
     */
    private fun ensureMeasuredThrough(through: Int) {
        if (through < 0 || paragraphs.isEmpty()) return
        val end = through.coerceAtMost(paragraphs.lastIndex)
        for (i in 0..end) {
            if (layoutCache.get(i) == null) buildAndCache(i)
        }
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
     * TTS 当前句：若未**完整**落在正文可视区内，则滚动使**句首行顶**对齐正文区最上沿；
     * 已全在屏内则只刷新高亮。
     */
    fun scrollToHighlightIfNeeded(paraIndex: Int, start: Int, end: Int) {
        if (paraIndex !in paragraphs.indices || tops.isEmpty() || height <= 0) return
        // 量好目标段及前后，避免 tops/行高仍为估算
        val from = (paraIndex - 1).coerceAtLeast(0)
        val to = (paraIndex + 1).coerceAtMost(paragraphs.lastIndex)
        for (i in from..to) {
            if (layoutCache.get(i) == null) buildAndCache(i)
        }
        val layout = layoutCache.get(paraIndex)
        if (layout == null) {
            scrollToParagraphIfNeeded(paraIndex)
            return
        }
        val paraTop = tops.getOrElse(paraIndex) { 0f }
        val bounds = highlightRangeYBounds(paraIndex, layout, paraTop, start, end)
        if (bounds == null) {
            scrollToParagraphIfNeeded(paraIndex)
            return
        }
        val (rangeTop, rangeBottom) = bounds
        // 正文可视 content Y：顶 = scrollYF，底再扣 TTS 等遮挡
        val viewTop = scrollYF
        val viewBottom = scrollYF + effectiveContentHeight()
        val pad = 2f
        val fullyVisible =
            rangeTop >= viewTop - pad && rangeBottom <= viewBottom + pad
        if (fullyVisible) {
            invalidate()
            return
        }
        // 下一句顶对齐正文区最上方
        scrollYF = rangeTop.coerceAtLeast(0f)
        clampScroll()
        if (!scroller.isFinished) scroller.abortAnimation()
        invalidate()
        scheduleFill(0L)
        notifyScroll(force = true)
    }

    /** @return (contentTop, contentBottom) of highlight range，无有效行则 null */
    private fun highlightRangeYBounds(
        para: Int,
        layout: ParaLayout,
        paraTop: Float,
        rangeStart: Int,
        rangeEnd: Int,
    ): Pair<Float, Float>? {
        val textLen = paragraphs.getOrNull(para)?.text?.length ?: return null
        val a = rangeStart.coerceIn(0, textLen)
        val b = rangeEnd.coerceIn(0, textLen)
        if (a >= b) return null
        var minTop = Float.MAX_VALUE
        var maxBottom = Float.MIN_VALUE
        var hit = false
        val baseLh = lineHeight
        for (line in layout.lines) {
            val la = maxOf(line.start, a)
            val lb = minOf(line.end, b)
            if (la >= lb) continue
            hit = true
            val top = paraTop + line.top
            val lh = line.lineH(baseLh)
            minTop = minOf(minTop, top)
            maxBottom = maxOf(maxBottom, top + lh)
        }
        return if (hit) minTop to maxBottom else null
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

    /**
     * 翻页（按行）：
     * - 下翻：当前屏最下面显示的一行 → 新页顶部（标题栏下完整显示）
     * - 上翻：当前屏最上面显示的一行 → 新页底部，上方内容顶对齐完整行
     * @param overlapLines 保留参数以兼容调用，已不再使用像素估算
     */
    @Suppress("UNUSED_PARAMETER")
    fun pageTurn(forward: Boolean, overlapLines: Int = 1): Boolean {
        if (height <= 0 || paragraphs.isEmpty() || tops.isEmpty()) return false
        val before = scrollYF
        if (!scroller.isFinished) scroller.abortAnimation()

        ensureLayoutsForViewport()

        // 漫画/图集：按视口高度翻页（图片无文字行时行锚点失效）
        if (imageHeavyMode || !hasAnyLayoutLinesNearViewport()) {
            if (!pageTurnByViewport(forward)) return false
        } else if (forward) {
            // 最下「完整显示」的一行 → 新页第 1 行；上一行不得露出
            var target = findLastFullyVisibleLineTop()
                ?: findViewportEdgeLineTop(wantLast = true)
            if (target == null) {
                if (!pageTurnByViewport(true)) return false
            } else {
                if (abs(target - before) < 0.5f) {
                    target = nextLineTopAfter(target)
                    if (target == null) {
                        if (!pageTurnByViewport(true)) return false
                    } else {
                        scrollYF = target.coerceAtLeast(0f)
                        clampScroll()
                    }
                } else {
                    scrollYF = target.coerceAtLeast(0f)
                    clampScroll()
                }
            }
        } else {
            // 最上可见行 → 新页最后一行（状态栏上方完整，不被裁切）
            val topLine = findViewportEdgeLineTop(wantLast = false)
            if (topLine == null) {
                if (!pageTurnByViewport(false)) return false
            } else {
                if (topLine <= 0.5f && before <= 0.5f) return false
                if (!scrollPageUpKeepingBottomLine(topLine) && abs(scrollYF - before) < 0.5f) {
                    val prev = prevLineTopBefore(topLine)
                    if (prev == null || !scrollPageUpKeepingBottomLine(prev)) {
                        if (!pageTurnByViewport(false)) return false
                    }
                }
            }
        }

        if (abs(scrollYF - before) < 0.5f) return false
        markInteracting()
        invalidate()
        notifyScroll(force = false)
        scheduleFill(8L)
        return true
    }

    /** 视口附近是否已有可用文字/伪行（用于决定是否走行锚点翻页） */
    private fun hasAnyLayoutLinesNearViewport(): Boolean {
        if (paragraphs.isEmpty() || tops.isEmpty() || height <= 0) return false
        val first = firstVisibleParagraph()
        val to = (first + estimateParasPerPage() + 4).coerceAtMost(paragraphs.lastIndex)
        for (i in first.coerceAtLeast(0)..to) {
            val layout = layoutCache.get(i) ?: continue
            if (layout.lines.isNotEmpty()) return true
        }
        return false
    }

    /**
     * 按正文可视高度翻一页（图片漫画 / 行锚点失败时的兜底）。
     * 下翻约 95% 屏高，避免整页贴边看不清衔接。
     */
    private fun pageTurnByViewport(forward: Boolean): Boolean {
        if (height <= 0) return false
        val before = scrollYF
        val pageH = pageContentHeight().coerceAtLeast(lineHeight)
        val step = pageH * 0.95f
        scrollYF = if (forward) {
            (scrollYF + step).coerceAtMost(maxScrollY().toFloat())
        } else {
            (scrollYF - step).coerceAtLeast(0f)
        }
        clampScroll()
        return abs(scrollYF - before) >= 0.5f
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

    /** 正文可视高度（去掉上下 contentPadding） */
    private fun pageContentHeight(): Float =
        (height - contentPaddingV * 2).toFloat().coerceAtLeast(lineHeight)

    /**
     * 跟读/可见判定用的正文高度：再扣除底部叠层（TTS 条等）。
     * bottomObscured 含 statusBar+tts 时，勿重复减 contentPadding 底部。
     */
    private fun effectiveContentHeight(): Float {
        val base = pageContentHeight()
        // bottomObscured 从 View 底往上；content 底 padding 也在其中，多扣一点无妨
        val obscureInContent = (bottomObscuredPx - contentPaddingV).coerceAtLeast(0f)
        return (base - obscureInContent).coerceAtLeast(lineHeight)
    }

    /** 预建视口附近段落布局，便于按行取 top */
    private fun ensureLayoutsForViewport() {
        if (paragraphs.isEmpty() || tops.isEmpty()) return
        val first = firstVisibleParagraph()
        val approx = estimateParasPerPage() + 4
        val from = (first - 2).coerceAtLeast(0)
        val to = (first + approx).coerceAtMost(paragraphs.lastIndex)
        for (i in from..to) {
            if (layoutCache.get(i) == null) buildAndCache(i)
        }
    }

    /** 字形露出 ≥ 此比例视为「完整显示」（翻页锚点；行距空白不计入） */
    private val fullVisibleRatio = 0.9f

    /**
     * 单行文字墨迹高度（ascent~descent），不含行距空白。
     * lineHeight = textInkHeight * lineSpacingMult。
     */
    private fun textInkHeight(): Float {
        val fm = textPaint.fontMetrics
        return (fm.descent - fm.ascent).coerceAtLeast(1f)
    }

    /**
     * 文字墨迹在 [rangeTop, rangeBottom) 内的可见比例（0~1）。
     * 只算字形高度，行距空白不算。
     */
    private fun textVisibleRatio(
        lineTop: Float,
        rangeTop: Float,
        rangeBottom: Float,
    ): Float {
        val inkH = textInkHeight()
        val textTop = lineTop
        val textBottom = lineTop + inkH
        val a = max(textTop, rangeTop)
        val b = min(textBottom, rangeBottom)
        val overlap = (b - a).coerceAtLeast(0f)
        return (overlap / inkH).coerceIn(0f, 1f)
    }

    /**
     * 当前屏最下一条「完整显示」的行顶（content Y）。
     * 完整：该行**文字**在正文区内露出 ≥ [fullVisibleRatio]（默认 90%），行距空白不计。
     */
    private fun findLastFullyVisibleLineTop(): Float? {
        if (paragraphs.isEmpty() || tops.isEmpty() || height <= 0) return null
        val pageH = pageContentHeight()
        // 正文可视区间（content）：[scrollYF, scrollYF + pageH]
        val fullTop = scrollYF
        val fullBottom = scrollYF + pageH
        val inkH = textInkHeight()

        var edge: Float? = null
        var para = tops.binarySearch(fullTop.coerceAtLeast(0f))
        if (para < 0) para = (-para - 2).coerceAtLeast(0)
        para = (para - 1).coerceAtLeast(0)

        while (para <= paragraphs.lastIndex) {
            if (tops[para] > fullBottom) break
            val layout = layoutCache.get(para) ?: buildAndCache(para)
            val pTop = tops[para]
            for (line in layout.lines) {
                val lt = pTop + line.top
                if (textVisibleRatio(lt, fullTop, fullBottom) >= fullVisibleRatio) {
                    edge = lt
                }
                // 文字顶已超出底边，后面行更不可能完整
                if (lt > fullBottom) break
                // 文字底已远超底边也可提前结束本段后续
                if (lt + inkH > fullBottom + lineHeight) break
            }
            para++
        }
        return edge
    }

    /**
     * 视口内相交的最顶/最底一行的 content Y（行顶）。
     * 上翻取最顶；下翻兜底时取最底相交行。
     */
    private fun findViewportEdgeLineTop(wantLast: Boolean): Float? {
        if (paragraphs.isEmpty() || tops.isEmpty()) return null
        val lh = lineHeight
        // 与绘制 clip 一致的正文区 content 范围
        val cTop = scrollYF
        val cBottom = scrollYF + pageContentHeight()

        var edge: Float? = null
        var para = tops.binarySearch(cTop.coerceAtLeast(0f))
        if (para < 0) para = (-para - 2).coerceAtLeast(0)
        para = (para - 1).coerceAtLeast(0)

        while (para <= paragraphs.lastIndex) {
            if (tops[para] > cBottom) break
            val layout = layoutCache.get(para) ?: buildAndCache(para)
            val pTop = tops[para]
            for (line in layout.lines) {
                val lt = pTop + line.top
                val lb = lt + lh
                if (lb > cTop + 0.5f && lt < cBottom - 0.5f) {
                    if (wantLast) {
                        edge = lt
                    } else {
                        return lt
                    }
                }
            }
            para++
        }
        return edge
    }

    private fun nextLineTopAfter(lineTop: Float): Float? {
        if (paragraphs.isEmpty() || tops.isEmpty()) return null
        var para = tops.binarySearch(lineTop)
        if (para < 0) para = (-para - 2).coerceAtLeast(0)
        para = para.coerceIn(0, paragraphs.lastIndex)
        while (para <= paragraphs.lastIndex) {
            val layout = layoutCache.get(para) ?: buildAndCache(para)
            val pTop = tops[para]
            for (line in layout.lines) {
                val lt = pTop + line.top
                if (lt > lineTop + 0.5f) return lt
            }
            para++
        }
        return null
    }

    private fun prevLineTopBefore(lineTop: Float): Float? {
        if (paragraphs.isEmpty() || tops.isEmpty()) return null
        var para = tops.binarySearch(lineTop)
        if (para < 0) para = (-para - 2).coerceAtLeast(0)
        para = para.coerceIn(0, paragraphs.lastIndex)
        var prev: Float? = null
        var i = 0
        while (i <= para) {
            val layout = layoutCache.get(i) ?: buildAndCache(i)
            val pTop = tops[i]
            for (line in layout.lines) {
                val lt = pTop + line.top
                if (lt >= lineTop - 0.5f) {
                    return prev
                }
                prev = lt
            }
            i++
        }
        return prev
    }

    /**
     * 上翻：把 [bottomLineTop] 放在视口底部（状态栏上方）完整显示，
     * 顶部再对齐到完整行；禁止把底行顶出视口（那是被状态栏挡住的原因）。
     *
     * 绘制：viewY = contentPaddingV + contentY - scrollYF
     * 底行完整：viewY_bottom <= height - contentPaddingV
     *   => scrollYF >= bottomLineTop + lineHeight - pageContentHeight()
     */
    private fun scrollPageUpKeepingBottomLine(bottomLineTop: Float): Boolean {
        val pageH = pageContentHeight()
        val minScroll = (bottomLineTop + lineHeight - pageH).coerceAtLeast(0f)
        // 精确贴底：底行下沿在 content 下 padding 处
        scrollYF = minScroll
        clampScroll()
        // 顶行若半截：只允许「向前」对齐到下一行顶（增大 scroll），不得回退
        snapScrollToLineTopForwardOnly(minScrollY = minScroll)
        clampScroll()
        return true
    }

    /**
     * 若视口顶落在半行上：对齐到该行顶仅当不小于 minScrollY；
     * 否则对齐到下一行顶（增大 scroll），保证底行仍完整在状态栏上方。
     */
    private fun snapScrollToLineTopForwardOnly(minScrollY: Float) {
        if (paragraphs.isEmpty() || tops.isEmpty() || height <= 0) return
        if (scrollYF <= 0.5f) {
            scrollYF = 0f
            return
        }
        val hit = lineTopAtOrBefore(scrollYF) ?: return
        val atLineStart = abs(scrollYF - hit) < 0.5f
        if (atLineStart) {
            scrollYF = hit.coerceAtLeast(minScrollY)
            return
        }
        // 半行：优先下一行顶（不把底行顶出屏）
        val next = nextLineTopAfter(hit)
        if (next != null && next + 0.5f >= minScrollY) {
            scrollYF = next
        } else if (hit + 0.5f >= minScrollY) {
            scrollYF = hit
        } else {
            scrollYF = minScrollY
        }
    }

    /** contentY 所在（或紧邻上方）行的行顶 */
    private fun lineTopAtOrBefore(contentY: Float): Float? {
        if (paragraphs.isEmpty() || tops.isEmpty()) return null
        var para = tops.binarySearch(contentY.coerceAtLeast(0f))
        if (para < 0) para = (-para - 2).coerceAtLeast(0)
        para = para.coerceIn(0, paragraphs.lastIndex)
        if (layoutCache.get(para) == null) buildAndCache(para)
        para = tops.binarySearch(contentY.coerceAtLeast(0f)).let {
            if (it < 0) (-it - 2).coerceAtLeast(0) else it
        }.coerceIn(0, paragraphs.lastIndex)

        val layout = layoutCache.get(para) ?: buildAndCache(para)
        val pTop = tops[para]
        val lh = lineHeight
        var best: Float? = null
        for (line in layout.lines) {
            val lt = pTop + line.top
            if (lt <= contentY + 0.5f) best = lt
            if (contentY < lt + lh) break
        }
        if (best != null) return best
        // 在段间距：用上一段末行
        if (para > 0) {
            val prevLayout = layoutCache.get(para - 1) ?: buildAndCache(para - 1)
            val last = prevLayout.lines.lastOrNull() ?: return tops[para]
            return tops[para - 1] + last.top
        }
        return pTop
    }

    /**
     * 将 scrollY 对齐到「视口顶部第一行」的行顶（可回退）。
     * 下翻后使用，保证标题栏下第 1 行完整。
     */
    private fun snapScrollToLineTop() {
        if (paragraphs.isEmpty() || tops.isEmpty() || height <= 0) return
        if (scrollYF <= 0.5f) {
            scrollYF = 0f
            return
        }
        val hit = lineTopAtOrBefore(scrollYF) ?: return
        // 半行则对齐到该行顶（回退），保证顶行完整
        scrollYF = hit.coerceAtLeast(0f)
        clampScroll()
    }

    private fun markInteracting() {
        interactingUntilMs = SystemClock.uptimeMillis() + INTERACT_HOLD_MS
    }

    // ─── 触摸：长按拖选；轻点取消选区/翻页 ─────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            downTime = SystemClock.uptimeMillis()
            moved = false
            longPressFired = false
            swipeHorizontal = null
            horizontalSwipeConsumed = false
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
            draggingHandle = null
            if (hasSelection() && !selecting) {
                selectionHandleAnchors()?.let { (s, e) ->
                    draggingHandle = TextSelectionHandles.hitTest(
                        event.x,
                        event.y,
                        s.x,
                        s.y,
                        e.x,
                        e.y,
                        density,
                    )
                }
            }
            if (draggingHandle != null) {
                selectionDragActive = true
                lastSelDragX = event.x
                lastSelDragY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }

        if (draggingHandle != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    lastSelDragX = event.x
                    lastSelDragY = event.y
                    updateSelectionAtFinger()
                    ensureSelectionEdgeScrollLoop()
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingHandle = null
                    selectionDragActive = false
                    selectionEdgeScrollState.reset()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidateSelectionActionMode()
                }
            }
            return true
        }

        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    lastSelDragX = event.x
                    lastSelDragY = event.y
                    updateSelectionAtFinger()
                    ensureSelectionEdgeScrollLoop()
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    selecting = false
                    selectionDragActive = false
                    selectionEdgeScrollState.reset()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (hasSelection()) {
                        startTextActionMode()
                        invalidate()
                    } else {
                        clearSelection()
                    }
                    edgeSide = 0
                    edgeAccum = 0f
                    swipeHorizontal = null
                    horizontalSwipeConsumed = false
                    scheduleFill(8L)
                }
                MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    selectionDragActive = false
                    selectionEdgeScrollState.reset()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (hasSelection()) {
                        startTextActionMode()
                        invalidate()
                    }
                    edgeSide = 0
                    edgeAccum = 0f
                    swipeHorizontal = null
                    horizontalSwipeConsumed = false
                    scheduleFill(8L)
                }
            }
            return true
        }

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 已在入口处理
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved) {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        moved = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !longPressFired) {
                    // 轻点：立刻响应，不再用长按超时卡死（避免侧边翻页像延时 0.5s）
                    if (hasSelection()) {
                        clearSelection()
                    } else if (paragraphPickEnabled) {
                        val hit = hitTest(event.x, event.y, allowBuild = true)
                        if (hit != null) {
                            onParagraphPicked?.invoke(hit.para)
                        }
                    } else {
                        val hit = hitTest(event.x, event.y, allowBuild = true)
                        val href = hit?.let { linkHrefAt(it) }
                        if (!href.isNullOrBlank() && onLinkClick != null) {
                            onLinkClick?.invoke(href)
                        } else {
                            onZoneTap?.invoke(downZone)
                        }
                    }
                } else if (edgeSide == 0 &&
                    !horizontalSwipeConsumed &&
                    tryHorizontalSwipePageTurn(event.x - downX, event.y - downY)
                ) {
                    // 慢速水平滑翻页（fling 未达阈值时）
                }
                edgeSide = 0
                edgeAccum = 0f
                swipeHorizontal = null
                horizontalSwipeConsumed = false
                scheduleFill(8L)
            }
            MotionEvent.ACTION_CANCEL -> {
                edgeSide = 0
                edgeAccum = 0f
                swipeHorizontal = null
                horizontalSwipeConsumed = false
                scheduleFill(8L)
            }
        }
        return true
    }

    /**
     * 水平滑动翻页：左滑下一页，右滑上一页。
     * @return 是否已处理
     */
    private fun tryHorizontalSwipePageTurn(dx: Float, dy: Float): Boolean {
        val cb = onHorizontalSwipe ?: return false
        val absDx = abs(dx)
        val absDy = abs(dy)
        val horizontal = swipeHorizontal == true ||
            (absDx >= swipeMinDistance && absDx > absDy * 1.2f)
        if (!horizontal) return false
        if (absDx < swipeMinDistance) return false
        // 右滑 dx>0 → 上一页；左滑 dx<0 → 下一页
        cb.invoke(dx < 0f)
        return true
    }

    /** 选区拖到顶/底边缘时跟手滚动（MOVE 即时步进 + 边缘循环） */
    private fun autoScrollWhileSelecting(y: Float) {
        val step = TextSelectionHandles.edgeScrollStep(
            y,
            height.toFloat(),
            lineHeight,
            density,
            selectionEdgeScrollState,
        )
        if (step == 0f) return
        scrollYF = (scrollYF + step).coerceIn(0f, maxScrollY().toFloat())
        scheduleFill(0L)
    }

    private fun ensureSelectionEdgeScrollLoop() {
        autoScrollWhileSelecting(lastSelDragY)
        if (!edgeScrollLoopPosted) {
            edgeScrollLoopPosted = true
            postOnAnimation(selectionEdgeScrollLoop)
        }
    }

    private fun updateSelectionAtFinger() {
        val hit = hitTest(lastSelDragX, lastSelDragY, allowBuild = true) ?: return
        val handle = draggingHandle
        if (handle != null) {
            applySelectionHandleDrag(handle, hit)
        } else if (selecting) {
            selEndPara = hit.para
            selEndOff = hit.offset
        }
    }

    private fun applySelectionHandleDrag(handle: TextSelectionHandles.Which, hit: Hit) {
        when (handle) {
            TextSelectionHandles.Which.START -> {
                selStartPara = hit.para
                selStartOff = hit.offset
            }
            TextSelectionHandles.Which.END -> {
                selEndPara = hit.para
                selEndOff = hit.offset
            }
        }
        normalizeSelectionOrder()
    }

    private fun normalizeSelectionOrder() {
        val a = Hit(selStartPara, selStartOff)
        val b = Hit(selEndPara, selEndOff)
        if (compareHit(a, b) <= 0) return
        selStartPara = b.para
        selStartOff = b.offset
        selEndPara = a.para
        selEndOff = a.offset
        draggingHandle = when (draggingHandle) {
            TextSelectionHandles.Which.START -> TextSelectionHandles.Which.END
            TextSelectionHandles.Which.END -> TextSelectionHandles.Which.START
            null -> null
        }
    }

    private fun selectionHandleAnchors(): Pair<PointF, PointF>? {
        val norm = normalizedSelection() ?: return null
        val start = charOffsetToViewPoint(norm.first.para, norm.first.offset, atEnd = false) ?: return null
        val end = charOffsetToViewPoint(norm.second.para, norm.second.offset, atEnd = true) ?: return null
        return start to end
    }

    private fun charOffsetToViewPoint(para: Int, offset: Int, atEnd: Boolean): PointF? {
        if (para !in paragraphs.indices || tops.isEmpty()) return null
        val layout = layoutCache.get(para) ?: buildAndCache(para)
        if (layout.lines.isEmpty()) return null
        val text = paragraphs[para].text
        if (text.isEmpty()) return null
        val paint = if (paragraphs[para].isChapter) chapterPaint else textPaint
        val off = when {
            atEnd && offset > 0 -> (offset - 1).coerceIn(0, text.length - 1)
            else -> offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        }
        val lineIdx = lineForOffset(layout, off)
        if (lineIdx < 0 || lineIdx > layout.lines.lastIndex) return null
        val line = layout.lines[lineIdx]
        val x = if (atEnd) {
            paint.measureText(text, line.start, (off + 1).coerceAtMost(text.length))
        } else if (off <= line.start) {
            0f
        } else {
            paint.measureText(text, line.start, off)
        }
        val lineBottom = tops[para] + line.top + lineHeight
        return PointF(contentPaddingH + x, contentPaddingV + lineBottom - scrollYF)
    }

    private fun invalidateSelectionActionMode() {
        if (actionMode == null || !hasSelection()) return
        actionMode?.invalidate()
        actionMode?.invalidateContentRect()
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
        if (layout.lines.isEmpty() || paragraphs[para].isBlockImage) return Anchor(para, 0)
        val localY = (scrollYF - tops[para]).coerceAtLeast(0f)
        val line = lineAtY(layout, localY)
        if (line < 0 || line > layout.lines.lastIndex) return Anchor(para, 0)
        return Anchor(para, layout.lines[line].start)
    }

    private fun restoreAnchor(anchor: Anchor) {
        if (paragraphs.isEmpty() || tops.isEmpty()) return
        val para = anchor.para.coerceIn(0, paragraphs.lastIndex)
        val layout = layoutCache.get(para)
        scrollYF = if (layout == null || layout.lines.isEmpty()) {
            tops[para]
        } else {
            val li = lineForOffset(layout, anchor.offset)
            if (li < 0 || li > layout.lines.lastIndex) tops[para]
            else tops[para] + layout.lines[li].top
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

        val spacing = paraSpacingPx()
        // 按正文顶定位首段（与 clip 后可见区一致）
        var start = tops.binarySearch(scrollYF)
        if (start < 0) start = (-start - 2).coerceAtLeast(0)
        start = start.coerceIn(0, paragraphs.lastIndex)
        val first = (start - 1).coerceAtLeast(0)

        val tp = textPaint
        val cp = chapterPaint
        tp.color = textColor
        cp.color = textColor

        canvas.save()
        // 裁剪到正文区：顶/底 padding 内不画字，避免翻页后「上一行」从标题下露出
        val clipTop = contentPaddingV.toFloat()
        val clipBottom = (height - contentPaddingV).toFloat().coerceAtLeast(clipTop + 1f)
        canvas.clipRect(0f, clipTop, width.toFloat(), clipBottom)
        canvas.translate(contentPaddingH.toFloat(), contentPaddingV - scrollYF)

        // 从 first 起按 tops 定位（测高后 tops 会变，每段以 tops[i] 为准，避免与 y 累加脱节）
        var i = first
        var syncBuilt = 0
        var needMoreFill = false
        var scrollBeforeDraw = scrollYF

        // 正文区对应的 content Y 范围（与 clip 一致）
        var contentTop = scrollYF
        var contentBottom = scrollYF + pageContentHeight()

        while (i <= paragraphs.lastIndex) {
            if (i !in tops.indices) break
            // 始终用 tops 对齐，防止未测图用压缩占位后 y 与 tops 漂移
            val y = tops[i]
            if (y > contentBottom + lineHeight) break

            var layout = layoutCache.get(i)
            val estH = if (i in heights.indices) heights[i] else lineHeight
            // 可见区内缺布局：同步补上，避免下半屏长期空白
            if (layout == null && y < contentBottom && y + estH > contentTop) {
                if (syncBuilt < MAX_SYNC_BUILD_PER_DRAW) {
                    layout = buildAndCache(i)
                    syncBuilt++
                    // 测高可能改了 scroll；同一帧坐标系已乱，下帧重画
                    if (abs(scrollYF - scrollBeforeDraw) > 0.5f) {
                        canvas.restore()
                        invalidate()
                        scheduleFill(0L)
                        return
                    }
                    contentTop = scrollYF
                    contentBottom = scrollYF + pageContentHeight()
                } else {
                    needMoreFill = true
                }
            }

            val blockH = if (layout != null) {
                layout.height + spacing
            } else if (paragraphs[i].isBlockImage) {
                // 块图：必须与 heights/tops 一致，不可压成 4 行，否则滑过时猛跳
                estH
            } else {
                // 未建好的长文本：保守占位（仅影响本帧绘制，不改正 tops）
                estH.coerceAtMost(lineHeight * MAX_BLANK_PLACEHOLDER_LINES + spacing)
            }

            if (layout != null && y + blockH >= contentTop - lineHeight) {
                if (exportRangeStart >= 0 && i in exportRangeStart..exportRangeEnd) {
                    canvas.drawRect(
                        0f,
                        y,
                        contentWidth.toFloat(),
                        y + layout.height,
                        exportRangePaint,
                    )
                }
                if (!layout.imagePath.isNullOrBlank()) {
                    drawImageParagraph(canvas, layout, y, contentTop, contentBottom)
                } else {
                    if (i == highlightParagraph && highlightEnd > highlightStart) {
                        drawHighlightRange(canvas, i, layout, y)
                    }
                    if (hasSelection() && selectionOverlaps(i)) {
                        drawSelection(canvas, i, layout, y)
                    }
                    val para = paragraphs[i]
                    val paint = paintForParagraph(para, cp, tp)
                    val text = para.text
                    val lines = layout.lines
                    val lh = lineHeight
                    val hasSpans = para.spans.isNotEmpty()
                    val hasInline = para.hasInlineImages
                    val maxW = contentWidth.toFloat()
                    for (li in lines.indices) {
                        val line = lines[li]
                        val lineH = line.lineH(lh)
                        val lineTopAbs = y + line.top
                        // 行底不超过正文顶 → 属上一行区域，不画
                        if (lineTopAbs + lineH <= contentTop + 0.5f) continue
                        if (lineTopAbs >= contentBottom - 0.5f) break
                        val x0 = lineStartX(line.width, maxW, para.align)
                        if (hasInline) {
                            drawLineWithInlineImages(
                                canvas,
                                para,
                                line,
                                x0,
                                y + line.baseline,
                                y + line.top,
                                lineH,
                                paint,
                            )
                        } else if (hasSpans) {
                            drawStyledLine(
                                canvas,
                                text,
                                line.start,
                                line.end,
                                x0,
                                y + line.baseline,
                                y + line.top,
                                para.spans,
                                para.isChapter,
                                paint,
                            )
                        } else {
                            paint.color = textColor
                            canvas.drawText(
                                text, line.start, line.end, x0, y + line.baseline, paint,
                            )
                        }
                    }
                }
            }

            i++
        }
        canvas.restore()

        if (needMoreFill || hasVisibleBlank()) {
            scheduleFill(0L)
        }
        if (hasSelection()) {
            selectionHandleAnchors()?.let { (s, e) ->
                TextSelectionHandles.draw(canvas, s.x, s.y, e.x, e.y, density)
            }
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYF = scroller.currY.toFloat()
            clampScroll()
            invalidate()
            notifyScroll(force = false)
            invalidateSelectionActionMode()
            if (scroller.isFinished) scheduleFill(0L)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fillBlankRunnable)
        removeCallbacks(selectionEdgeScrollLoop)
        fillRunning = false
        edgeScrollLoopPosted = false
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
        invalidateSelectionActionMode()
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
        textPaint.typeface = com.whj.reader.util.ReaderFonts.resolve(
            context,
            style.fontFamily,
            bold = false,
        )
        textPaint.letterSpacing = style.letterSpacing
        textPaint.color = textColor

        // 章节标题：加大字号
        chapterPaint.textSize = sizePx * 1.28f
        chapterPaint.typeface = com.whj.reader.util.ReaderFonts.resolve(
            context,
            style.fontFamily,
            bold = true,
        )
        chapterPaint.letterSpacing = style.letterSpacing
        chapterPaint.color = textColor

        val fm = textPaint.fontMetrics
        fontAscent = -fm.ascent
        lineHeight = (fm.descent - fm.ascent) * style.lineSpacingMult
        avgCharWidth = textPaint.measureText("国").coerceAtLeast(8f)
        val cfm = chapterPaint.fontMetrics
        chapterLineHeight = (cfm.descent - cfm.ascent) * style.lineSpacingMult
        chapterAscent = -cfm.ascent
    }

    private fun paraSpacingPx(): Float =
        if (imageHeavyMode) 0f else style.paraSpacingDp * density

    /** 章节标题段额外上下留白 */
    private fun chapterExtraPadPx(): Float =
        if (imageHeavyMode) 0f else lineHeight * 0.85f

    /** 图片最大绘制宽度：漫画模式铺满 View 全宽，普通模式为正文宽 */
    private fun imageMaxWidthPx(): Float {
        return if (imageHeavyMode && width > 0) {
            width.toFloat().coerceAtLeast(1f)
        } else {
            contentWidth.toFloat().coerceAtLeast(1f)
        }
    }

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
        for (i in 0 until n) {
            // 已有实测布局时必须用实测高，否则流式 updateContent 重置估算后
            // 会画出完整 pre/表行却只占估算高度 → 与后文重叠
            val cached = layoutCache.get(i)
            heights[i] = if (cached != null) {
                cached.height + spacing
            } else if (paragraphs[i].isBlockImage) {
                estimateBlockImageHeight(paragraphs[i]) + spacing
            } else {
                val para = paragraphs[i]
                val lh = if (para.isChapter) chapterLineHeight else lineHeight
                val lineCount = if (para.preformatted) {
                    // pre：按换行估算，避免把多行压成一两行
                    para.text.count { it == '\n' } + 1
                } else {
                    ((para.text.length / cpl).toInt() + 1).coerceAtLeast(1)
                }
                val boost = if (para.hasInlineImages) 1.35f else 1f
                val pad = if (para.isChapter) chapterExtraPadPx() * 2f else 0f
                lineCount * lh * boost + pad + spacing
            }
        }
        recomputeTopsFromHeights()
    }

    /** 根据 [heights] 重算 [tops] / [totalHeight]（不改 scroll） */
    private fun recomputeTopsFromHeights() {
        val n = heights.size
        if (tops.size != n) tops = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            y += heights[i]
        }
        totalHeight = y
    }

    /** 图片原始宽高缓存（仅 bounds，不解码像素） */
    private val imageBoundsCache = HashMap<String, Pair<Int, Int>>(32)

    private fun peekImageBounds(path: String): Pair<Int, Int>? {
        imageBoundsCache[path]?.let { return it }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val p = opts.outWidth to opts.outHeight
        imageBoundsCache[path] = p
        return p
    }

    /**
     * 块图高度估算：用文件 bounds 算与 [buildImageLayout] 一致的绘制高，
     * 减少「估算→实测」差值，从源头降低滑动跳变。
     */
    private fun estimateBlockImageHeight(para: Paragraph): Float {
        val path = para.imagePath
        val maxW = imageMaxWidthPx()
        val pad = if (imageHeavyMode) 0f else lineHeight * 0.5f
        val bounds = path?.let { peekImageBounds(it) }
        val aspect = if (bounds != null && bounds.second > 0) {
            bounds.first.toFloat() / bounds.second.toFloat()
        } else {
            1f
        }
        val fontPx = textPaint.textSize.coerceAtLeast(1f)
        val dens = density
        val ds = para.imageDisplaySize
        var w: Float? = null
        var h: Float? = null
        if (ds != null && ds.hasAny) {
            when {
                ds.widthEm != null -> w = ds.widthEm * fontPx
                ds.widthPercent != null -> w = maxW * (ds.widthPercent / 100f)
                ds.widthPx != null -> w = ds.widthPx * dens
            }
            when {
                ds.heightEm != null -> h = ds.heightEm * fontPx
                ds.heightPercent != null -> h = maxW * (ds.heightPercent / 100f)
                ds.heightPx != null -> h = ds.heightPx * dens
            }
            if (w != null && h == null) h = w / aspect
            if (h != null && w == null) w = h * aspect
        }
        if (w == null || h == null) {
            if (bounds != null) {
                val bw = bounds.first.toFloat().coerceAtLeast(1f)
                val bh = bounds.second.toFloat()
                val scale = if (imageHeavyMode) {
                    maxW / bw
                } else {
                    (maxW / bw).coerceAtMost(1f)
                }
                w = bw * scale
                h = bh * scale
            } else if (imageHeavyMode) {
                val screenH = height.takeIf { it > 0 }?.toFloat()
                    ?: resources.displayMetrics.heightPixels.toFloat()
                return screenH * 0.9f
            } else {
                h = (contentWidth * 0.55f).coerceIn(lineHeight * 3f, lineHeight * 12f)
                w = h!! * aspect
            }
        }
        var outW = w ?: maxW
        var outH = h ?: lineHeight * 4f
        if (outW > maxW) {
            val s = maxW / outW
            outW = maxW
            outH *= s
        }
        return outH + pad
    }

    // ─── 断行：getTextWidths 一次 + 累加（快） ──────────────

    private fun buildAndCache(index: Int): ParaLayout {
        layoutCache.get(index)?.let { cached ->
            // 命中缓存也要保证 heights 与实测一致（估算被 rebuild 覆盖时）
            val expect = cached.height + paraSpacingPx()
            if (index in heights.indices && abs(heights[index] - expect) > 0.5f) {
                applyMeasuredHeight(index, cached.height)
            }
            return cached
        }
        val layout = buildParaLayout(index)
        layoutCache.put(index, layout)
        // 实测高度写入，并平移后续 tops，避免后段叠到前段上
        applyMeasuredHeight(index, layout.height)
        return layout
    }

    /**
     * 将 index 段高度改为实测值，后续段 tops 整体平移。
     *
     * **仅当整段完全在视口上方时**才补偿 scrollY。
     * 旧逻辑用 `tops[i] < scrollYF`（段起点在视口上方就补），半屏可见的块图
     * 一测高就会把 scroll 整图高度跳掉，上下滑时像“跳过一段图高”。
     */
    private fun applyMeasuredHeight(index: Int, contentH: Float) {
        if (index !in heights.indices) return
        val newH = contentH + paraSpacingPx()
        val oldH = heights[index]
        val delta = newH - oldH
        if (abs(delta) <= 0.5f) {
            heights[index] = newH
            return
        }
        // 整段底边仍在视口顶之上 → 视口内容相对位置应保持
        val oldBottom = tops[index] + oldH
        if (oldBottom <= scrollYF + 0.5f) {
            scrollYF = (scrollYF + delta).coerceAtLeast(0f)
        }
        heights[index] = newH
        var i = index + 1
        val n = tops.size
        while (i < n) {
            tops[i] += delta
            i++
        }
        totalHeight += delta
        clampScroll()
    }

    private fun buildParaLayout(index: Int): ParaLayout {
        val para = paragraphs[index]
        if (para.isBlockImage) {
            return buildImageLayout(para.imagePath!!, para.imageDisplaySize)
        }
        val text = para.text
        val paint = paintForParagraph(para, chapterPaint, textPaint)
        paint.color = textColor
        val maxW = contentWidth.toFloat()
        val isCh = para.isChapter
        val padTop = if (isCh) chapterExtraPadPx() else 0f
        val padBottom = if (isCh) chapterExtraPadPx() else 0f
        if (text.isEmpty()) {
            return ParaLayout(EMPTY_LINES, lineHeight + padTop + padBottom, padTop = padTop)
        }

        val len = text.length
        if (charWidthsBuf.size < len) {
            charWidthsBuf = FloatArray(len + 64)
        }
        paint.getTextWidths(text, 0, len, charWidthsBuf)
        // 行内图：替换对应字符宽度，并记录高度
        val inlineH = FloatArray(len)
        if (para.hasInlineImages) {
            for (im in para.inlineImages) {
                val s = im.start.coerceIn(0, len - 1)
                val (iw, ih) = measureInlineImage(im.path, im.displaySize)
                charWidthsBuf[s] = iw
                inlineH[s] = ih
                // 多字符占位时其余宽度 0
                for (k in (s + 1) until min(im.end, len)) {
                    charWidthsBuf[k] = 0f
                    inlineH[k] = 0f
                }
            }
        }

        lineList.clear()
        var i = 0
        var lineTop = padTop
        val ascent = if (isCh) chapterAscent else fontAscent
        val baseLh = if (isCh) chapterLineHeight else lineHeight
        val widths = charWidthsBuf
        val pre = para.preformatted

        fun maxInlineH(from: Int, to: Int): Float {
            var m = 0f
            var k = from
            while (k < to) {
                if (inlineH[k] > m) m = inlineH[k]
                k++
            }
            return m
        }

        while (i < len) {
            if (text[i] == '\n') {
                // 此处的 \n 只能是：文首换行 / 连续空行。
                // 正文行尾的单个 \n 在下面「整行吃完」后被消费，不会进这里。
                lineList.add(LineRec(i, i + 1, 0f, lineTop, lineTop + ascent, baseLh))
                lineTop += baseLh
                i++
                continue
            }

            // 软换行后去掉行首空白（pre 保留缩进）
            if (!pre) {
                while (i < len && (text[i] == ' ' || text[i] == '\t')) i++
            }
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
                    // pre：尽量在空白处断；否则硬断
                    val end = when {
                        lastBreak > lineStart -> lastBreak
                        else -> i
                    }
                    val ww = when {
                        lastBreak > lineStart -> lastBreakWidth
                        else -> width
                    }
                    val ih = maxInlineH(lineStart, end)
                    val rowH = max(baseLh, ih)
                    lineList.add(
                        LineRec(lineStart, end, ww, lineTop, lineTop + ascent, rowH),
                    )
                    lineTop += rowH
                    i = end
                    broke = true
                    break
                }
                width += w
                i++
                val prev = text[i - 1]
                if (prev == ' ' || prev == '\t' || prev == HtmlRichParser.OBJ_CHAR ||
                    (!pre && isCjkBreak(prev))
                ) {
                    lastBreak = i
                    lastBreakWidth = width
                }
            }

            if (broke) continue

            // 整行吃到换行或文末
            if (i > lineStart) {
                val ih = maxInlineH(lineStart, i)
                val rowH = max(baseLh, ih)
                lineList.add(LineRec(lineStart, i, width, lineTop, lineTop + ascent, rowH))
                lineTop += rowH
                // 吃掉行尾 \n（不再为 \n 单独加一行）
                if (i < len && text[i] == '\n') i++
            } else if (i < len && text[i] != '\n') {
                // 单字超宽
                val ih = inlineH[i]
                val rowH = max(baseLh, ih)
                lineList.add(LineRec(i, i + 1, widths[i], lineTop, lineTop + ascent, rowH))
                lineTop += rowH
                i++
            }
        }

        if (lineList.isEmpty()) {
            lineList.add(LineRec(0, 0, 0f, padTop, padTop + ascent, baseLh))
            lineTop = padTop + baseLh
        }
        val contentH = lineTop + padBottom
        return ParaLayout(lineList.toTypedArray(), contentH, padTop = padTop)
    }

    /**
     * 按 [ImageDisplaySize] 与位图比例算绘制宽高。
     * @return Pair(w, h) 像素
     */
    private fun resolveImageDrawSize(
        path: String,
        displaySize: com.whj.reader.model.ImageDisplaySize?,
        maxW: Float,
        defaultAsInline: Boolean,
    ): Pair<Float, Float> {
        val fontPx = textPaint.textSize.coerceAtLeast(1f)
        val dens = density
        val bmp = decodeBitmapForWidth(path, maxW.toInt().coerceAtLeast(48))
        val aspect = if (bmp != null && bmp.height > 0) {
            bmp.width.toFloat() / bmp.height
        } else {
            1f
        }

        var w: Float? = null
        var h: Float? = null
        val ds = displaySize
        if (ds != null && ds.hasAny) {
            when {
                ds.widthEm != null -> w = ds.widthEm * fontPx
                ds.widthPercent != null -> w = maxW * (ds.widthPercent / 100f)
                ds.widthPx != null -> w = ds.widthPx * dens
            }
            when {
                ds.heightEm != null -> h = ds.heightEm * fontPx
                ds.heightPercent != null -> h = maxW * (ds.heightPercent / 100f) // 少见，按宽基准
                ds.heightPx != null -> h = ds.heightPx * dens
            }
            if (w != null && h == null) h = w / aspect
            if (h != null && w == null) w = h * aspect
        }
        if (w == null || h == null) {
            if (defaultAsInline) {
                // 无尺寸：外字默认约 1em（与正文字号一致），不再撑到 1.2 行高放大
                h = fontPx
                w = h * aspect
            } else {
                // 块图无尺寸：适配最大宽
                val scale = if (imageHeavyMode) {
                    maxW / (bmp?.width?.toFloat()?.coerceAtLeast(1f) ?: maxW)
                } else {
                    ((maxW / (bmp?.width?.toFloat()?.coerceAtLeast(1f) ?: maxW)).coerceAtMost(1f))
                }
                w = (bmp?.width?.toFloat() ?: maxW) * scale
                h = (bmp?.height?.toFloat() ?: maxW) * scale
            }
        }
        // 行内图限制：不超过正文 90% 宽，高度合理
        if (defaultAsInline) {
            val maxInlineW = maxW * 0.9f
            if (w > maxInlineW) {
                val s = maxInlineW / w
                w = maxInlineW
                h *= s
            }
        } else if (w > maxW) {
            val s = maxW / w
            w = maxW
            h *= s
        }
        return w.coerceAtLeast(1f) to h.coerceAtLeast(1f)
    }

    /** 行内图 */
    private fun measureInlineImage(
        path: String,
        displaySize: com.whj.reader.model.ImageDisplaySize? = null,
    ): Pair<Float, Float> =
        resolveImageDrawSize(path, displaySize, contentWidth.toFloat().coerceAtLeast(1f), defaultAsInline = true)

    private fun buildImageLayout(
        path: String,
        displaySize: com.whj.reader.model.ImageDisplaySize? = null,
    ): ParaLayout {
        val maxW = imageMaxWidthPx()
        val (dw, dh) = resolveImageDrawSize(path, displaySize, maxW, defaultAsInline = false)
        // 无 HTML 尺寸且漫画模式：已在 resolve 中满宽
        val blockH = dh + if (imageHeavyMode) 0f else lineHeight * 0.5f
        val step = pageContentHeight().coerceAtLeast(lineHeight * 8f)
        val pseudo = ArrayList<LineRec>(max(1, (blockH / step).toInt() + 1))
        var t = 0f
        while (t < blockH - 0.5f) {
            pseudo.add(LineRec(0, 0, dw, t, t + fontAscent))
            t += step
        }
        if (pseudo.isEmpty()) {
            pseudo.add(LineRec(0, 0, dw, 0f, fontAscent))
        }
        return ParaLayout(
            lines = pseudo.toTypedArray(),
            height = blockH,
            imagePath = path,
            imageDrawW = dw,
            imageDrawH = dh,
        )
    }

    private fun decodeBitmapForWidth(path: String, maxWidth: Int): Bitmap? {
        bitmapCache.get(path)?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            val target = maxWidth.coerceAtLeast(1)
            while (bounds.outWidth / sample > target * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
            bitmapCache.put(path, bmp)
            bmp
        }.getOrNull()
    }

    private fun drawImageParagraph(
        canvas: Canvas,
        layout: ParaLayout,
        y: Float,
        contentTop: Float,
        contentBottom: Float,
    ) {
        val path = layout.imagePath ?: return
        if (y + layout.height < contentTop || y > contentBottom) return
        val maxW = imageMaxWidthPx()
        val bmp = decodeBitmapForWidth(path, maxW.toInt()) ?: return
        val dw = layout.imageDrawW.takeIf { it > 0f } ?: maxW
        val dh = layout.imageDrawH.takeIf { it > 0f }
            ?: (dw * bmp.height / bmp.width.toFloat().coerceAtLeast(1f))
        // 漫画：相对 content 原点左移 padding，铺满整屏宽；普通：正文区居中
        val left = if (imageHeavyMode) {
            -contentPaddingH.toFloat()
        } else {
            ((contentWidth - dw) / 2f).coerceAtLeast(0f)
        }
        val topPad = if (imageHeavyMode) 0f else lineHeight * 0.2f
        val top = y + topPad
        val dest = RectF(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, dest, imagePaint)
    }

    /** 含行内图的一行：按字符绘制，FFFC 处画图 */
    private fun drawLineWithInlineImages(
        canvas: Canvas,
        para: Paragraph,
        line: LineRec,
        x: Float,
        baseline: Float,
        lineTop: Float,
        lineH: Float,
        basePaint: TextPaint,
    ) {
        val text = para.text
        if (line.start >= line.end) return
        val inlineByStart = para.inlineImages.associateBy { it.start }
        var i = line.start
        var xPos = x
        val spans = para.spans
        while (i < line.end && i < text.length) {
            val im = inlineByStart[i]
            if (im != null) {
                val (iw, ih) = measureInlineImage(im.path, im.displaySize)
                val bmp = decodeBitmapForWidth(im.path, iw.toInt().coerceAtLeast(1))
                if (bmp != null) {
                    // vertical-align: middle
                    // 文字贴行顶、行距空白在下方：纯 lineH 中点偏下，纯 ink 中点偏上。
                    // 取 ink 与 line 中点的加权（约 6:4），贴近光学中心。
                    val inkH = textInkHeight()
                    val top = when {
                        // 行被大图撑高：相对整行居中
                        lineH > inkH + 1f && ih >= inkH - 0.5f ->
                            lineTop + (lineH - ih) / 2f
                        // 小图：墨迹中线与行盒中线之间
                        else -> {
                            val inkMid = lineTop + inkH * 0.5f
                            val lineMid = lineTop + lineH * 0.5f
                            val midY = inkMid * 0.6f + lineMid * 0.4f
                            midY - ih * 0.5f
                        }
                    }
                    canvas.drawBitmap(bmp, null, RectF(xPos, top, xPos + iw, top + ih), imagePaint)
                }
                xPos += iw
                i = im.end.coerceAtLeast(i + 1)
                continue
            }
            // 合并连续非图字符
            var j = i + 1
            while (j < line.end && j < text.length && inlineByStart[j] == null) j++
            // 行被大图撑高时：文字在行盒内与图一起垂直居中
            val inkH = textInkHeight()
            val textBaseline = if (lineH > inkH + 1f) {
                lineTop + (lineH - inkH) / 2f + fontAscent
            } else {
                // 与排版一致：baseline = lineTop + fontAscent
                lineTop + fontAscent
            }
            if (spans.isNotEmpty()) {
                val xBefore = xPos
                drawStyledLine(
                    canvas, text, i, j, xPos, textBaseline, lineTop, spans, para.isChapter, basePaint,
                )
                xPos = xBefore + basePaint.measureText(text, i, j)
            } else {
                basePaint.color = textColor
                canvas.drawText(text, i, j, xPos, textBaseline, basePaint)
                xPos += basePaint.measureText(text, i, j)
            }
            i = j
        }
        basePaint.color = textColor
    }

    /**
     * 按 span 分段绘制一行：背景 → 文字 → 下划线。
     * 颜色/背景仅用区间自身样式（单层）。
     */
    private fun drawStyledLine(
        canvas: Canvas,
        text: String,
        lineStart: Int,
        lineEnd: Int,
        x: Float,
        baseline: Float,
        lineTop: Float,
        spans: List<TextSpanStyle>,
        isChapter: Boolean,
        basePaint: TextPaint,
    ) {
        if (lineStart >= lineEnd) return
        var i = lineStart
        var xPos = x
        while (i < lineEnd) {
            val style = styleAt(spans, i)
            var j = i + 1
            while (j < lineEnd && styleAt(spans, j) == style) j++
            applyRunPaint(runPaint, basePaint, style, isChapter)
            val w = runPaint.measureText(text, i, j)
            style?.backgroundColor?.let { bg ->
                spanBgPaint.color = bg
                canvas.drawRect(xPos, lineTop, xPos + w, lineTop + lineHeight, spanBgPaint)
            }
            canvas.drawText(text, i, j, xPos, baseline, runPaint)
            val href = style?.linkHref
            val needUl = style?.underline == true ||
                (!href.isNullOrBlank() && HtmlRichParser.isExternalLink(href))
            if (needUl) {
                underlinePaint.color = runPaint.color
                val uy = baseline + 2f * density
                canvas.drawLine(xPos, uy, xPos + w, uy, underlinePaint)
            }
            xPos += w
            i = j
        }
        // 恢复 base 字色
        basePaint.color = textColor
    }

    private fun styleAt(spans: List<TextSpanStyle>, index: Int): TextSpanStyle? {
        // 后写优先（更内层）
        var hit: TextSpanStyle? = null
        for (s in spans) {
            if (index >= s.start && index < s.end) hit = s
        }
        return hit
    }

    private fun paintForParagraph(
        para: com.whj.reader.model.Paragraph,
        chapter: TextPaint,
        body: TextPaint,
    ): TextPaint {
        val base = if (para.isChapter) chapter else body
        if (!para.preformatted) return base
        // pre：等宽，字号/颜色与正文一致
        monoPaint.set(base)
        monoPaint.typeface = android.graphics.Typeface.MONOSPACE
        monoPaint.isFakeBoldText = para.isChapter || base.isFakeBoldText
        return monoPaint
    }

    private fun lineStartX(
        lineWidth: Float,
        maxW: Float,
        align: com.whj.reader.model.TextAlign,
    ): Float {
        return when (align) {
            com.whj.reader.model.TextAlign.START -> 0f
            com.whj.reader.model.TextAlign.CENTER ->
                ((maxW - lineWidth) / 2f).coerceAtLeast(0f)
            com.whj.reader.model.TextAlign.END ->
                (maxW - lineWidth).coerceAtLeast(0f)
        }
    }

    private fun applyRunPaint(
        dest: TextPaint,
        base: TextPaint,
        style: TextSpanStyle?,
        isChapter: Boolean,
    ) {
        dest.set(base)
        val href = style?.linkHref
        val isExternal = !href.isNullOrBlank() && HtmlRichParser.isExternalLink(href)
        dest.color = style?.color
            ?: if (isExternal) HtmlRichParser.DEFAULT_LINK_COLOR else textColor
        val bold = isChapter || style?.bold == true
        val italic = style?.italic == true
        dest.isFakeBoldText = bold
        // base 可能已是 monoPaint（pre 段）
        if (base.typeface === android.graphics.Typeface.MONOSPACE) {
            dest.typeface = android.graphics.Typeface.MONOSPACE
        } else {
            dest.typeface = com.whj.reader.util.ReaderFonts.resolve(
                context,
                this.style.fontFamily,
                bold = bold,
            )
        }
        dest.textSkewX = if (italic) -0.25f else 0f
    }

    private fun linkHrefAt(hit: Hit): String? {
        val spans = paragraphs.getOrNull(hit.para)?.spans.orEmpty()
        if (spans.isEmpty()) return null
        var found: String? = null
        for (s in spans) {
            if (hit.offset >= s.start && hit.offset < s.end && !s.linkHref.isNullOrBlank()) {
                found = s.linkHref
            }
        }
        return found
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

        // 图片段 / 无文字行：只返回段落命中，offset=0
        if (paragraphs[target].isBlockImage ||
            layout.imagePath != null ||
            layout.lines.isEmpty() ||
            paragraphs[target].text.isEmpty()
        ) {
            return Hit(target, 0)
        }

        val localY = (contentY - paraTop).coerceAtLeast(0f)
        val line = lineAtY(layout, localY)
        if (line < 0 || line > layout.lines.lastIndex) return Hit(target, 0)
        val rec = layout.lines[line]
        val localX = (x - contentPaddingH).coerceAtLeast(0f)
        val paint = if (paragraphs[target].isChapter) chapterPaint else textPaint
        val text = paragraphs[target].text
        var off = rec.start
        var acc = 0f
        while (off < rec.end && off < text.length) {
            val w = paint.measureText(text, off, off + 1)
            if (acc + w * 0.5f >= localX) break
            acc += w
            off++
        }
        // 落到行尾时允许选到 end（exclusive）
        return Hit(target, off.coerceIn(rec.start, rec.end.coerceAtMost(text.length)))
    }

    private fun lineAtY(layout: ParaLayout, localY: Float): Int {
        val lines = layout.lines
        if (lines.isEmpty()) return -1
        for (i in lines.indices) {
            val bottom = if (i + 1 < lines.size) lines[i + 1].top else layout.height
            if (localY < bottom) return i
        }
        return lines.lastIndex
    }

    private fun lineForOffset(layout: ParaLayout, offset: Int): Int {
        val lines = layout.lines
        if (lines.isEmpty()) return -1
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
        draggingHandle = null
        selectionDragActive = false
        selectionEdgeScrollState.reset()
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
        drawCharRange(canvas, para, layout, paraTop, selStart, selEnd, selectionPaint)
    }

    /** TTS 当前句：按字符区间画高亮底（可跨多行） */
    private fun drawHighlightRange(
        canvas: Canvas,
        para: Int,
        layout: ParaLayout,
        paraTop: Float,
    ) {
        val textLen = paragraphs[para].text.length
        val a = highlightStart.coerceIn(0, textLen)
        val b = highlightEnd.coerceIn(0, textLen)
        if (a >= b) return
        highlightPaint.color = highlightColor
        drawCharRange(canvas, para, layout, paraTop, a, b, highlightPaint)
    }

    private fun drawCharRange(
        canvas: Canvas,
        para: Int,
        layout: ParaLayout,
        paraTop: Float,
        rangeStart: Int,
        rangeEnd: Int,
        paintBg: Paint,
    ) {
        val paint = if (paragraphs[para].isChapter) chapterPaint else textPaint
        val text = paragraphs[para].text
        for (line in layout.lines) {
            val a = maxOf(line.start, rangeStart)
            val b = minOf(line.end, rangeEnd)
            if (a >= b) continue
            val x0 = if (a == line.start) 0f else paint.measureText(text, line.start, a)
            val x1 = paint.measureText(text, line.start, b)
            val top = paraTop + line.top
            canvas.drawRect(x0 - 2f, top, x1 + 2f, top + lineHeight, paintBg)
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
                                invalidateSelectionActionMode()
                            }
                            return true
                        }
                        3 -> {
                            // 从选区起点字符读到文末
                            val norm = normalizedSelection()
                            val para = norm?.first?.para
                                ?: selStartPara.coerceAtLeast(0)
                            val off = norm?.first?.offset
                                ?: selStartOff.coerceAtLeast(0)
                            mode.finish()
                            clearSelection()
                            if (para in paragraphs.indices) {
                                onReadFromParagraph?.invoke(para, off)
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
