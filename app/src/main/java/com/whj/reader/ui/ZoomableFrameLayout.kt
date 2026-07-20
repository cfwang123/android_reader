package com.whj.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max

/**
 * 双指捏合缩放（结束后保留）+ 缩放后单指平移/惯性 + 双击切换缩放。
 *
 * - 未缩放：单指交给子 View（连续滚动 / 点按翻页，自带惯性）
 * - 已缩放：单指平移；连续模式竖向交给 [onPanOverscroll] / [onFlingScroll]
 * - 多指始终由本层处理
 */
class ZoomableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var contentZoom: Float = 1f
        private set

    /** 默认 1；PDF 可读时设为 0.5 以支持缩小 */
    var minZoom: Float = 1f
    var maxZoom: Float = 3.5f

    var onZoomChanged: ((zoom: Float) -> Unit)? = null
    /** 缩放 / 平移变换更新后（含惯性滚动帧），用于刷新 TTS 高亮等叠加层 */
    var onTransformChanged: (() -> Unit)? = null
    /** 中部轻点（菜单）；侧边走 [onSideTapImmediate] 以免双击延迟 */
    var onSingleTap: ((x: Float, y: Float) -> Unit)? = null
    /** 左/右侧边轻点立即回调，zone: 0=左 2=右 */
    var onSideTapImmediate: ((zone: Int, x: Float, y: Float) -> Unit)? = null
    /**
     * 水平滑动翻页（未缩放时）。
     * [forward] true = 左滑（下一页），false = 右滑（上一页）。
     */
    var onHorizontalSwipe: ((forward: Boolean) -> Unit)? = null
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null
    var onSelectionDrag: ((x: Float, y: Float, ended: Boolean) -> Unit)? = null

    /**
     * 缩放后拖动时的位移（未消耗部分，或连续模式整段竖向）。
     * 坐标为本控件系，与手指同向。
     */
    var onPanOverscroll: ((overX: Float, overY: Float) -> Unit)? = null

    /**
     * 缩放后松手惯性：速度 px/s（屏幕坐标系，与 VelocityTracker 一致）。
     * 连续模式用 RecyclerView.fling；单页模式本层 pan 惯性另用 [OverScroller]。
     */
    var onFlingScroll: ((velocityX: Float, velocityY: Float) -> Unit)? = null

    /** 按下时停止外部惯性滚动（如 RV.stopScroll） */
    var onStopScroll: (() -> Unit)? = null

    /**
     * 连续模式：竖向交给列表，pan 只负责水平。
     */
    var continuousScrollWhenZoomed: Boolean = false

    var zoomTarget: View? = null
        set(value) {
            field = value
            applyTransform()
        }

    private var panX = 0f
    private var panY = 0f
    private var pinching = false
    private var panning = false
    private var selecting = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    /** 水平滑翻页：最小位移（约 48dp） */
    private val swipeMinDistance =
        (48f * resources.displayMetrics.density).toInt().coerceAtLeast(touchSlop * 2)
    /** 水平滑翻页：最小速度 px/s */
    private val swipeMinVelocity = minFlingVelocity.coerceAtLeast(400)

    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var flingingPan = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                selecting = false
                abortPanFling()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = detector.scaleFactor
                if (f.isNaN() || f.isInfinite() || f <= 0f) return false
                applyScale(f.coerceIn(0.85f, 1.15f), detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
                clampPan()
                applyTransform()
                onZoomChanged?.invoke(contentZoom)
            }
        },
    ).also { it.isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            // 不启用双击缩放
            override fun onDoubleTap(e: MotionEvent): Boolean = false

            // 中部立即响应（不等 double-tap 超时）；侧边在 dispatch UP 已处理
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (pinching || panning || selecting || flingingPan) return false
                // 有明显位移则是滑动，不走点按
                val moved = max(abs(e.x - downX), abs(e.y - downY))
                if (moved > touchSlop) return false
                val w = width.toFloat().coerceAtLeast(1f)
                if (e.x < w / 3f || e.x > w * 2f / 3f) return false
                onSingleTap?.invoke(e.x, e.y)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // 缩放平移时不抢 fling；未缩放水平 fling 在 UP 里统一处理
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 已在 onSingleTapUp 处理，避免再触发一次导致开关两次
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (pinching) return
                abortPanFling()
                selecting = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onLongPress?.invoke(e.x, e.y)
            }
        },
    )

    /** 上一次外侧底色，避免每帧 setBackgroundColor */
    private var lastExteriorBg: Int = 1 // 哨兵，强制首次写入

    init {
        clipChildren = true
        clipToPadding = true
        // 必须可点击：否则子 View（如单页 ImageView）不消费 DOWN 时，
        // dispatch 返回 false，后续 MOVE/UP 不再送达 → 侧边翻页/中部菜单全失效。
        // 连续模式靠 RecyclerView 消费触摸所以表现正常。
        isClickable = true
        isFocusable = false
    }

    /**
     * 缩小过程中外侧立刻变黑（不等 onScaleEnd）。
     * 回到 ≥100% 时不改色，交由 Activity 按日/夜恢复。
     */
    private fun syncExteriorBackground() {
        if (contentZoom < 0.99f) {
            val bg = 0xFF000000.toInt()
            if (lastExteriorBg != bg) {
                lastExteriorBg = bg
                setBackgroundColor(bg)
            }
        } else {
            lastExteriorBg = 1
        }
    }

    fun setContentZoom(zoom: Float, notify: Boolean = false) {
        val z = zoom.coerceIn(minZoom, maxZoom)
        // 回到约 100% 时清平移；缩小/放大都保留焦点平移
        val nearIdentity = abs(z - 1f) < 0.01f
        setTransform(z, if (nearIdentity) 0f else panX, if (nearIdentity) 0f else panY, notify)
    }

    fun getPanX(): Float = panX
    fun getPanY(): Float = panY

    /** 恢复缩放+平移（用于打开 PDF 时还原视图） */
    fun setTransform(zoom: Float, panX: Float, panY: Float, notify: Boolean = false) {
        abortPanFling()
        contentZoom = zoom.coerceIn(minZoom, maxZoom)
        this.panX = panX
        this.panY = panY
        // 仅在「约等于 1x」时归零；允许 minZoom~1 的缩小态
        if (abs(contentZoom - 1f) < 0.01f) {
            contentZoom = 1f
            this.panX = 0f
            this.panY = 0f
        }
        clampPan()
        applyTransform()
        if (notify) onZoomChanged?.invoke(contentZoom)
    }

    /** 是否放大（>1）：用于平移接管触摸。缩小（&lt;1）仍把竖滑交给列表。 */
    fun isZoomed(): Boolean = contentZoom > 1.01f

    /** 是否相对 100% 有缩放（含缩小） */
    fun isScaled(): Boolean = abs(contentZoom - 1f) > 0.01f

    fun mapToContent(x: Float, y: Float): PointF {
        return PointF(
            (x - panX) / contentZoom.coerceAtLeast(0.01f),
            (y - panY) / contentZoom.coerceAtLeast(0.01f),
        )
    }

    fun resetZoom(notify: Boolean = false) {
        setTransform(1f, 0f, 0f, notify)
    }

    fun resetVisualScale() {
        applyTransform()
    }

    private fun target(): View? = zoomTarget ?: getChildAt(0)

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        val old = contentZoom.coerceAtLeast(0.01f)
        val newZoom = (old * factor).coerceIn(minZoom, maxZoom)
        if (abs(newZoom - old) < 0.0001f) return
        val cx = (focusX - panX) / old
        val cy = (focusY - panY) / old
        contentZoom = newZoom
        if (abs(contentZoom - 1f) < 0.01f) {
            contentZoom = 1f
            panX = 0f
            panY = 0f
        } else {
            panX = focusX - cx * contentZoom
            panY = focusY - cy * contentZoom
        }
        clampPan()
        applyTransform()
    }

    private fun zoomTo(zoom: Float, focusX: Float, focusY: Float) {
        val old = contentZoom.coerceAtLeast(0.01f)
        val newZoom = zoom.coerceIn(minZoom, maxZoom)
        if (abs(newZoom - 1f) < 0.01f) {
            contentZoom = 1f
            panX = 0f
            panY = 0f
        } else {
            val cx = (focusX - panX) / old
            val cy = (focusY - panY) / old
            contentZoom = newZoom
            panX = focusX - cx * contentZoom
            panY = focusY - cy * contentZoom
            clampPan()
        }
        applyTransform()
    }

    private fun panBounds(): FloatArray {
        val t = target()
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val tw = (if (t != null && t.width > 0) t.width else width).toFloat().coerceAtLeast(1f)
        val th = (if (t != null && t.height > 0) t.height else height).toFloat().coerceAtLeast(1f)
        val cw = tw * contentZoom
        val ch = th * contentZoom
        val minX: Float
        val maxX: Float
        if (cw <= vw + 0.5f) {
            // 缩小后内容更窄：水平居中，两侧留给容器黑底
            minX = (vw - cw) / 2f
            maxX = minX
        } else {
            minX = vw - cw
            maxX = 0f
        }
        val minY: Float
        val maxY: Float
        // 连续模式缩小：布局已加高且 scale 后 ch≈vh，panY 锁 0 铺满。
        // 连续模式放大：竖向交给列表。
        if (continuousScrollWhenZoomed) {
            minY = 0f
            maxY = 0f
        } else if (ch <= vh + 0.5f) {
            minY = (vh - ch) / 2f
            maxY = minY
        } else {
            minY = vh - ch
            maxY = 0f
        }
        return floatArrayOf(minX, maxX, minY, maxY)
    }

    private fun clampPan() {
        if (abs(contentZoom - 1f) < 0.01f) {
            contentZoom = 1f
            panX = 0f
            panY = 0f
            return
        }
        val b = panBounds()
        panX = panX.coerceIn(b[0], b[1])
        panY = panY.coerceIn(b[2], b[3])
    }

    /**
     * 应用缩放：
     * - 放大（z>1）：match_parent + scale，可平移
     * - 缩小（z<1）且连续滚动：把内容区高度设为 vh/z 再 scale=z，
     *   视觉铺满高度且多露出 PDF 内容；宽度仍为屏宽，scale 后两侧露黑边
     * - 缩小且单页：match_parent + scale，居中，外侧黑底
     */
    private fun applyTransform() {
        val t = target() ?: return
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) {
            onTransformChanged?.invoke()
            return
        }
        val z = contentZoom.coerceAtLeast(0.01f)
        val lp = t.layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        if (z < 0.99f && continuousScrollWhenZoomed) {
            // 布局加高：缩放后高度铺满，列表可见范围变为原来的 1/z
            val layoutH = (vh / z).toInt().coerceAtLeast(vh)
            if (lp.width != LayoutParams.MATCH_PARENT || lp.height != layoutH) {
                lp.width = LayoutParams.MATCH_PARENT
                lp.height = layoutH
                t.layoutParams = lp
            }
        } else {
            if (lp.width != LayoutParams.MATCH_PARENT || lp.height != LayoutParams.MATCH_PARENT) {
                lp.width = LayoutParams.MATCH_PARENT
                lp.height = LayoutParams.MATCH_PARENT
                t.layoutParams = lp
            }
        }

        t.pivotX = 0f
        t.pivotY = 0f
        t.scaleX = z
        t.scaleY = z

        // 缩小时强制水平居中（两侧黑边）；连续缩小 panY=0
        if (z < 0.99f) {
            val layoutW = if (t.width > 0) t.width else vw
            val visualW = layoutW * z
            panX = (vw - visualW) / 2f
            if (continuousScrollWhenZoomed) {
                panY = 0f
            }
        }

        t.translationX = panX
        t.translationY = panY
        syncExteriorBackground()
        onTransformChanged?.invoke()
    }

    private fun abortPanFling() {
        if (!scroller.isFinished) {
            scroller.forceFinished(true)
        }
        flingingPan = false
    }

    private fun startPanFling(velocityX: Float, velocityY: Float) {
        val b = panBounds()
        val vx = velocityX.toInt().coerceIn(-maxFlingVelocity, maxFlingVelocity)
        val vy = if (continuousScrollWhenZoomed) {
            0
        } else {
            velocityY.toInt().coerceIn(-maxFlingVelocity, maxFlingVelocity)
        }
        // pan 惯性：手指方向与 pan 同向（手指右移 panX 增）
        // VelocityTracker：手指右移 vx>0
        if (abs(vx) < minFlingVelocity && abs(vy) < minFlingVelocity) return
        flingingPan = true
        scroller.fling(
            panX.toInt(),
            panY.toInt(),
            vx,
            vy,
            b[0].toInt(),
            b[1].toInt(),
            b[2].toInt(),
            b[3].toInt(),
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            clampPan()
            applyTransform()
            postInvalidateOnAnimation()
        } else if (flingingPan) {
            flingingPan = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
        applyTransform()
    }

    private fun obtainTracker(): VelocityTracker {
        val existing = velocityTracker
        if (existing != null) return existing
        val created = VelocityTracker.obtain()
        velocityTracker = created
        return created
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                abortPanFling()
                onStopScroll?.invoke()
                obtainTracker().clear()
            }
        }
        obtainTracker().addMovement(ev)

        gestureDetector.onTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)

        val multi = ev.pointerCount >= 2 || pinching || scaleDetector.isInProgress
        if (multi) {
            parent?.requestDisallowInterceptTouchEvent(true)
            panning = false
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pinching) {
                        pinching = false
                        clampPan()
                        applyTransform()
                        onZoomChanged?.invoke(contentZoom)
                    }
                    recycleTracker()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.pointerCount <= 2 && pinching) {
                        pinching = false
                        clampPan()
                        applyTransform()
                        onZoomChanged?.invoke(contentZoom)
                    }
                }
            }
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                panning = false
                selecting = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onSelectionDrag?.invoke(ev.x, ev.y, false)
                    lastX = ev.x
                    lastY = ev.y
                    return true
                }
                if (isZoomed()) {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    if (!panning) {
                        val total = max(abs(ev.x - downX), abs(ev.y - downY))
                        if (total > touchSlop) {
                            panning = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    if (panning) {
                        val oldX = panX
                        val oldY = panY
                        panX += dx
                        if (!continuousScrollWhenZoomed) {
                            panY += dy
                        }
                        clampPan()
                        applyTransform()
                        val usedX = panX - oldX
                        val usedY = panY - oldY
                        val overX = dx - usedX
                        val overY = if (continuousScrollWhenZoomed) dy else (dy - usedY)
                        if (abs(overX) > 0.5f || abs(overY) > 0.5f) {
                            onPanOverscroll?.invoke(overX, overY)
                        }
                        lastX = ev.x
                        lastY = ev.y
                        return true
                    }
                }
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selecting) {
                    onSelectionDrag?.invoke(ev.x, ev.y, true)
                    selecting = false
                    panning = false
                    recycleTracker()
                    return true
                }
                if (panning) {
                    panning = false
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        val vt = velocityTracker
                        if (vt != null) {
                            vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                            val vx = vt.xVelocity
                            val vy = vt.yVelocity
                            // 已缩放时：若水平顶到边缘再 fling，可翻页
                            if (isZoomed() &&
                                onHorizontalSwipe != null &&
                                trySwipePageTurn(vx, vy, ev.x - downX, ev.y - downY, edgeFling = true)
                            ) {
                                recycleTracker()
                                return true
                            }
                            // 水平 pan 惯性（及单页模式竖向 pan）
                            startPanFling(vx, vy)
                            // 连续模式竖向：交给 RecyclerView.fling 以获得惯性
                            if (continuousScrollWhenZoomed && abs(vy) >= minFlingVelocity) {
                                onFlingScroll?.invoke(vx, vy)
                            }
                        }
                    }
                    recycleTracker()
                    return true
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP && !pinching) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val total = max(abs(dx), abs(dy))
                    var vx = 0f
                    var vy = 0f
                    velocityTracker?.let { vt ->
                        vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                        vx = vt.xVelocity
                        vy = vt.yVelocity
                    }
                    // 1) 未缩放：水平滑翻页
                    if (!isZoomed() &&
                        onHorizontalSwipe != null &&
                        trySwipePageTurn(vx, vy, dx, dy, edgeFling = false)
                    ) {
                        recycleTracker()
                        return true
                    }
                    // 2) 侧边轻点：立即翻页
                    if (onSideTapImmediate != null && total <= touchSlop) {
                        val w = width.toFloat().coerceAtLeast(1f)
                        when {
                            ev.x < w / 3f -> {
                                onSideTapImmediate?.invoke(0, ev.x, ev.y)
                                recycleTracker()
                                return true
                            }
                            ev.x > w * 2f / 3f -> {
                                onSideTapImmediate?.invoke(2, ev.x, ev.y)
                                recycleTracker()
                                return true
                            }
                        }
                    }
                }
                recycleTracker()
            }
        }

        // 先交给子 View（连续模式 RV 滚动等）
        super.dispatchTouchEvent(ev)
        // 始终消费：保证单页 ImageView 场景下仍能收到完整手势序列
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 与 isClickable=true 配合，兜底消费未被分发逻辑提前 return 的事件
        return true
    }

    /**
     * @param edgeFling 缩放平移场景：仅当水平方向已顶边且继续甩时翻页
     * @return 是否已触发翻页
     */
    private fun trySwipePageTurn(
        velocityX: Float,
        velocityY: Float,
        dx: Float,
        dy: Float,
        edgeFling: Boolean,
    ): Boolean {
        val cb = onHorizontalSwipe ?: return false
        val absDx = abs(dx)
        val absDy = abs(dy)
        val absVx = abs(velocityX)
        val absVy = abs(velocityY)

        // 必须以水平为主
        val distanceOk = absDx >= swipeMinDistance && absDx > absDy * 1.2f
        val velocityOk = absVx >= swipeMinVelocity && absVx > absVy * 1.2f
        if (!distanceOk && !velocityOk) return false

        // 方向：优先位移，位移不够再用速度
        val goRight = if (absDx >= touchSlop) dx > 0f else velocityX > 0f
        // 右滑 → 上一页；左滑 → 下一页
        val forward = !goRight

        if (edgeFling) {
            val b = panBounds()
            val atLeftEdge = panX >= b[1] - 1.5f   // maxX
            val atRightEdge = panX <= b[0] + 1.5f  // minX
            // 继续往右甩且已在左缘 → 想看更左 → 上一页；往左甩且在右缘 → 下一页
            if (goRight && !atLeftEdge) return false
            if (!goRight && !atRightEdge) return false
        }

        cb.invoke(forward)
        return true
    }
}
