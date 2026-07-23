package com.whj.reader.ui

import com.whj.reader.util.ReaderLog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    /** 横竖屏重铺前由外部 [scheduleContinuousTransformRestore] 注入，onSizeChanged 后恢复 */
    private var pendingContinuousRestore: ContinuousTransformSnapshot? = null

    /** 连续流：记录当前 zoom 与水平 pan 在可滑区间内的比例 */
    data class ContinuousTransformSnapshot(
        val zoom: Float,
        val panRatioX: Float,
    )

    /**
     * 连续流横竖屏切换前快照：保持 zoom（相对屏宽比例）与 panX 比例。
     * 未缩放且水平未偏时返回 null。
     */
    fun snapshotContinuousTransform(): ContinuousTransformSnapshot? {
        if (!continuousScrollWhenZoomed || width <= 0) return null
        val z = contentZoom.coerceIn(minZoom, maxZoom)
        if (!isScaled() && abs(panX) < 0.5f) return null
        val b = panBounds()
        val span = b[1] - b[0]
        val ratio = if (span < 0.5f) 0f else ((panX - b[0]) / span).coerceIn(0f, 1f)
        return ContinuousTransformSnapshot(z, ratio)
    }

    /** 在 layout 变更前调用；尺寸变化后自动 [restoreContinuousTransform] */
    fun scheduleContinuousTransformRestore(snap: ContinuousTransformSnapshot?) {
        pendingContinuousRestore = snap
    }

    /** 按快照恢复 zoom / 水平 pan（竖向仍交给列表滚动） */
    fun restoreContinuousTransform(snap: ContinuousTransformSnapshot) {
        if (!continuousScrollWhenZoomed) return
        contentZoom = snap.zoom.coerceIn(minZoom, maxZoom)
        val b = panBounds()
        val span = b[1] - b[0]
        panX = if (span < 0.5f) b[0] else b[0] + snap.panRatioX * span
        panY = 0f
        clampPan()
        applyTransform()
        onZoomChanged?.invoke(contentZoom)
    }

    var contentZoom: Float = 1f
        private set

    /** 默认 1；漫画/PDF 可读时设为 0.25~0.5 以支持缩小 */
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

    /**
     * 单页横屏长页：允许 [zoomTarget] 高度大于视口（由外部设置 layout height），
     * 且 [applyTransform] 不强制改回 MATCH_PARENT。
     */
    var allowTallZoomTarget: Boolean = false

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
    /** 选区手柄拖动中：禁止本层 pan / 把竖滑交给 overlay */
    var handleDragActive = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /**
     * 双指起点状态：绝对 span 算法（与 ZoomableImageView 一致），
     * 放大时用 startSpan 重算，避免 scaleFactor 累乘 + 抬指 span 突变跳动。
     */
    private var pinchStartZoom = 1f
    private var pinchStartPanX = 0f
    private var pinchStartPanY = 0f
    private var pinchStartFocusX = 0f
    private var pinchStartFocusY = 0f
    private var pinchStartSpan = 1f
    private var pinchFrozen = false
    /** 已越过 100%、进入自由平移阶段 */
    private var pinchFreeMode = false
    /**
     * 双指结束后到下一次 ACTION_DOWN：禁止剩余单指 pan / fling。
     * （与 ZoomableImageView 相同：scaleEnd 后最后一指 MOVE 会拖飞画面）
     */
    private var blockPanAfterPinch = false
    /** 连续模式：上一帧焦点 y，用于竖向 overscroll 增量 */
    private var pinchLastFocusY = 0f
    private var pinchLastFocusYValid = false
    /** 本手势是否已超过点按阈值（连续模式用） */
    private var fingerMoved = false
    /** 本手势已处理过中部/侧边点按，防止 GestureDetector 再触发一次（开关两次=菜单不亮） */
    private var tapConsumed = false
    /** 同一 DOWN 序列只触发一次侧边翻页（防 dispatch + intercept 双发） */
    private var sideTapFiredDownTime = -1L
    /** 最近一次成功触发的侧边点按 DOWN 时间（供 Activity 去重） */
    val sideTapGestureDownTime: Long
        get() = sideTapFiredDownTime
    /**
     * 双指缩放 / 多指手势期间禁止边缘翻页与水平滑翻页。
     * 锁到整段手势结束；下一次单指 DOWN 时按是否放大重新设定。
     */
    private var pageTurnLocked = false
    /** 连续模式：侧区按下，UP 时若未滑则拦截并翻页（避免 RV 吞掉轻点） */
    private var sideTapTrack = false
    private var sideTapDownX = 0f
    private var sideTapDownY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    /** 点按判定略宽于系统 slop，避免轻微抖动被当成滑动 */
    private val tapSlop =
        (12f * resources.displayMetrics.density).toInt().coerceAtLeast(touchSlop)
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
                pageTurnLocked = true
                selecting = false
                pinchFrozen = false
                blockPanAfterPinch = true
                pinchFreeMode = !contentFitsAt(contentZoom)
                capturePinchStart(detector)
                abortPanFling()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (pinchFrozen) return true
                if (detector.currentSpan < 1f) return true
                applyPinchAbsolute(detector.focusX, detector.focusY, detector.currentSpan)
                pageTurnLocked = true
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val beforeZ = contentZoom
                val beforeX = panX
                val beforeY = panY
                pinching = false
                pageTurnLocked = true
                pinchFrozen = false
                blockPanAfterPinch = true
                abortPanFling()
                settleAfterPinch()
                // 同步 last，避免后续误 pan 用旧坐标
                lastX = detector.focusX
                lastY = detector.focusY
                val dPan = max(abs(panX - beforeX), abs(panY - beforeY))
                ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
                    "FrameScaleEnd z ${"%.3f".format(beforeZ)}→${"%.3f".format(contentZoom)} " +
                        "pan (${"%.1f".format(beforeX)},${"%.1f".format(beforeY)})→" +
                        "(${"%.1f".format(panX)},${"%.1f".format(panY)}) " +
                        "dPan=${"%.1f".format(dPan)} blockMoveAndFling=true",
                )
                onZoomChanged?.invoke(contentZoom)
            }
        },
    ).also { it.isQuickScaleEnabled = false }

    private fun capturePinchStart(detector: ScaleGestureDetector) {
        capturePinchStartAt(
            contentZoom.coerceAtLeast(0.01f),
            panX,
            panY,
            detector.focusX,
            detector.focusY,
            detector.currentSpan.coerceAtLeast(1f),
        )
        pinchLastFocusY = detector.focusY
        pinchLastFocusYValid = false
    }

    private fun capturePinchStartAt(
        zoom: Float,
        px: Float,
        py: Float,
        focusX: Float,
        focusY: Float,
        span: Float,
    ) {
        pinchStartZoom = zoom.coerceAtLeast(0.01f)
        pinchStartPanX = px
        pinchStartPanY = py
        pinchStartFocusX = focusX
        pinchStartFocusY = focusY
        pinchStartSpan = span.coerceAtLeast(1f)
    }

    /** 布局/测量尺寸；单页长页用 bitmap×matrix 视觉尺寸算 pan 边界，避免 layout 残留加高 */
    private fun targetContentSize(t: View?): Pair<Float, Float> {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        if (t == null) return vw to vh
        if (t is ImageView && t.drawable != null && !continuousScrollWhenZoomed) {
            val d = t.drawable!!
            val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
            val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
            var tw = dw
            var th = dh
            if (t.scaleType == ImageView.ScaleType.MATRIX) {
                val vals = FloatArray(9)
                t.imageMatrix.getValues(vals)
                tw = dw * abs(vals[Matrix.MSCALE_X]).coerceAtLeast(0.001f)
                th = dh * abs(vals[Matrix.MSCALE_Y]).coerceAtLeast(0.001f)
            }
            if (allowTallZoomTarget && th > vh + 1f) {
                return tw.coerceAtLeast(1f) to th.coerceAtLeast(1f)
            }
        }
        var tw = if (t.width > 0) t.width.toFloat() else vw
        var th = if (t.height > 0) t.height.toFloat() else vh
        if (allowTallZoomTarget) {
            val lp = t.layoutParams
            val lpW = lp?.width ?: 0
            val lpH = lp?.height ?: 0
            if (lpW > vw && lpW != LayoutParams.MATCH_PARENT) tw = lpW.toFloat()
            if (lpH > vh && lpH != LayoutParams.MATCH_PARENT) th = lpH.toFloat()
        }
        return tw.coerceAtLeast(1f) to th.coerceAtLeast(1f)
    }

    /** 内容在给定 zoom 下是否仍应居中（≤100%） */
    private fun contentFitsAt(zoom: Float): Boolean {
        val t = target()
        val vw = width.toFloat().coerceAtLeast(1f)
        val (tw, _) = targetContentSize(t)
        val z = zoom.coerceAtLeast(0.01f)
        // 与 UI「100%」一致：z≤1 且水平未超出
        return z <= 1.001f && tw * z <= vw + 0.5f
    }

    private fun centeredPanAt(zoom: Float): Pair<Float, Float> {
        val t = target()
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val (tw, th) = targetContentSize(t)
        val z = zoom.coerceAtLeast(0.01f)
        val cx = (vw - tw * z) / 2f
        val cy = if (continuousScrollWhenZoomed) 0f else (vh - th * z) / 2f
        return cx to cy
    }

    /**
     * 捏合策略：
     * - **≤100%**：强制居中 + 限制每帧步进（快速捏合不会单帧从 50% 蹦到 150%）
     * - **越过 100%**：本帧最多落到略大于 100%，居中并重锚定
     * - **>100%**：自由平移，缩放仍向绝对 span 目标平滑靠拢
     */
    private fun applyPinchAbsolute(focusX: Float, focusY: Float, span: Float) {
        val startSpan = pinchStartSpan.coerceAtLeast(1f)
        val s = span.coerceAtLeast(1f)
        val ratio = (s / startSpan).coerceIn(0.2f, 5f)
        val target = (pinchStartZoom * ratio).coerceIn(minZoom, maxZoom)
        val prevZ = contentZoom.coerceAtLeast(0.01f)

        // 快速捏合：限制每帧 zoom 变化，避免单帧跨越过大导致 pan 居中公式剧变
        val next = smoothZoomStep(prevZ, target)

        if (!pinchFreeMode && contentFitsAt(next)) {
            // 仍 ≤100%：强制居中
            contentZoom = next
            val (cx, cy) = centeredPanAt(next)
            panX = cx
            panY = cy
            applyTransform()
        } else if (!pinchFreeMode && !contentFitsAt(next)) {
            // 本帧将越过 100%：只走到略超 100%，居中后重锚定（禁止一次跳到 1.5x）
            val crossZ = min(next, max(1.02f, prevZ * 1.12f)).coerceIn(1.001f, maxZoom)
            contentZoom = crossZ
            val (cx, cy) = centeredPanAt(crossZ)
            panX = cx
            panY = cy
            capturePinchStartAt(crossZ, cx, cy, focusX, focusY, s)
            pinchFreeMode = true
            clampPanSoft()
            applyTransform()
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
                "pinchCrossUp z $prevZ→$crossZ (target=$target) reAnchor pan=($panX,$panY)",
            )
        } else {
            // 自由平移阶段
            val contentX = (pinchStartFocusX - pinchStartPanX) / pinchStartZoom
            val contentY = (pinchStartFocusY - pinchStartPanY) / pinchStartZoom
            contentZoom = next
            panX = focusX - contentX * next
            if (continuousScrollWhenZoomed) {
                panY = 0f
            } else {
                panY = focusY - contentY * next
            }
            clampPanSoft()
            applyTransform()
        }

        if (continuousScrollWhenZoomed && pinchLastFocusYValid && pinchFreeMode) {
            val dy = focusY - pinchLastFocusY
            if (abs(dy) > 0.5f && abs(dy) < maxPinchPanPerFrameSafe()) {
                onPanOverscroll?.invoke(0f, dy)
            }
        }
        pinchLastFocusY = focusY
        pinchLastFocusYValid = true
    }

    /**
     * 向 [target] 靠拢，限制单帧相对/绝对变化。
     * 慢速捏合几乎跟手；快速时分多帧追上，避免跳变。
     */
    private fun smoothZoomStep(prev: Float, target: Float): Float {
        val p = prev.coerceAtLeast(0.01f)
        val t = target.coerceIn(minZoom, maxZoom)
        // 单帧最多 ×1.12 或 ÷1.12，且绝对步进不超过 0.12
        val maxRel = 1.12f
        val maxAbs = 0.12f
        var lo = p / maxRel
        var hi = p * maxRel
        lo = max(lo, p - maxAbs)
        hi = min(hi, p + maxAbs)
        return t.coerceIn(lo, hi).coerceIn(minZoom, maxZoom)
    }

    private fun maxPinchPanPerFrameSafe(): Float =
        (48f * resources.displayMetrics.density).coerceAtLeast(32f)

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            // 不启用双击缩放
            override fun onDoubleTap(e: MotionEvent): Boolean = false

            // 中部立即响应（含放大态开菜单）；侧边在 dispatch UP 已处理
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (pinching || selecting || flingingPan) return false
                // 连续未缩放路径已在 dispatch UP 处理点按，此处再触发会 toggle 两次
                if (tapConsumed) return true
                if (continuousScrollWhenZoomed && !isZoomed() && !isScaled()) return false
                // 有明显位移则是滑动，不走点按（放大平移时允许微抖仍算点）
                val moved = max(abs(e.x - downX), abs(e.y - downY))
                val slop = if (isZoomed() || isScaled()) tapSlop * 1.5f else tapSlop.toFloat()
                if (moved > slop) return false
                val w = width.toFloat().coerceAtLeast(1f)
                // 放大态：中部开菜单；侧边由 onSideTapImmediate 处理，避免双翻页
                if (isZoomed() || isScaled()) {
                    if (e.x < w / 3f || e.x > w * 2f / 3f) return false
                    onSingleTap?.invoke(e.x, e.y)
                    return true
                }
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
        isClickable = true
        isFocusable = false
    }

    private fun isSideZoneX(x: Float): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        return x < w / 3f || x > w * 2f / 3f
    }

    private fun sideTapSlop(): Float =
        if (isZoomed() || isScaled()) tapSlop * 1.5f else tapSlop.toFloat()

    /** @return 是否已触发（含重复 UP 被吞掉） */
    private fun fireSideTapOnce(ev: MotionEvent, x: Float, y: Float): Boolean {
        val cb = onSideTapImmediate ?: return false
        if (pinching || selecting || handleDragActive) return false
        if (sideTapFiredDownTime == ev.downTime) return true
        val w = width.toFloat().coerceAtLeast(1f)
        val zone = when {
            x < w / 3f -> 0
            x > w * 2f / 3f -> 2
            else -> return false
        }
        sideTapFiredDownTime = ev.downTime
        onStopScroll?.invoke()
        cb.invoke(zone, x, y)
        tapConsumed = true
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (onSideTapImmediate == null) return false
        if (pinching || ev.pointerCount > 1 || scaleDetector.isInProgress) {
            sideTapTrack = false
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isSideZoneX(ev.x)) {
                    sideTapTrack = true
                    sideTapDownX = ev.x
                    sideTapDownY = ev.y
                } else {
                    sideTapTrack = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (sideTapTrack) {
                    val dist = max(abs(ev.x - sideTapDownX), abs(ev.y - sideTapDownY))
                    if (dist > sideTapSlop()) sideTapTrack = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tapConsumed) {
                    sideTapTrack = false
                    return false
                }
                if (sideTapTrack) {
                    val dist = max(abs(ev.x - sideTapDownX), abs(ev.y - sideTapDownY))
                    if (dist <= sideTapSlop()) {
                        fireSideTapOnce(ev, ev.x, ev.y)
                        sideTapTrack = false
                        return true
                    }
                }
                sideTapTrack = false
            }
            MotionEvent.ACTION_CANCEL -> sideTapTrack = false
        }
        return false
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
        // 约 100% 时仍走 setTransform/clampPan（长页可保留 panY）
        setTransform(z, panX, panY, notify)
    }

    fun getPanX(): Float = panX
    fun getPanY(): Float = panY

    /** 竖向 pan 范围：first=minY（看底部），second=maxY（看顶部，通常 0） */
    fun verticalPanLimits(): Pair<Float, Float> {
        val b = panBounds()
        return b[2] to b[3]
    }

    /**
     * 视口像素平移；[clampPan] 后应用。
     * @return 实际位移 (dx, dy)
     */
    fun panContentBy(dx: Float, dy: Float): Pair<Float, Float> {
        abortPanFling()
        val ox = panX
        val oy = panY
        panX += dx
        panY += dy
        clampPan()
        applyTransform()
        return (panX - ox) to (panY - oy)
    }

    /** 恢复缩放+平移（用于打开 PDF 时还原视图） */
    fun setTransform(zoom: Float, panX: Float, panY: Float, notify: Boolean = false) {
        abortPanFling()
        contentZoom = zoom.coerceIn(minZoom, maxZoom)
        this.panX = panX
        this.panY = panY
        // 约 1x：不要无脑清 pan——横屏 fit-width 时内容可能高于视口，需 panY 看全页
        if (abs(contentZoom - 1f) < 0.01f) {
            contentZoom = 1f
        }
        clampPan()
        applyTransform()
        if (notify) onZoomChanged?.invoke(contentZoom)
    }

    /** 是否放大（>1）：用于平移接管触摸。缩小（&lt;1）仍把竖滑交给列表。 */
    fun isPinching(): Boolean = pinching

    fun isZoomed(): Boolean = contentZoom > 1.01f

    /** 内容是否超出视口（1x 横屏长页也需要 pan） */
    fun canPanContent(): Boolean {
        if (width <= 0 || height <= 0) return false
        val b = panBounds()
        return abs(b[0] - b[1]) > 1f || abs(b[2] - b[3]) > 1f
    }

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

    /**
     * 重新应用当前 transform（不改 zoom/pan）。
     * 若需恢复 1x，请用 [resetZoom]。
     */
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
        panX = focusX - cx * contentZoom
        panY = focusY - cy * contentZoom
        clampPanSoft()
        applyTransform()
    }

    /**
     * 轻量夹紧：1x 时也按 panBounds 居中/夹边，不在缩放过程中突然清零 pan。
     * （内容铺满时 bounds 本身就是 0，结果仍是居中。）
     */
    private fun clampPanSoft() {
        val b = panBounds()
        panX = panX.coerceIn(b[0], b[1])
        panY = panY.coerceIn(b[2], b[3])
    }

    /**
     * 松手 settle：≤100% 捏合中已居中，此处只吸附比例 + 夹边，pan 不再大跳。
     */
    private fun settleAfterPinch() {
        val beforeZ = contentZoom
        val beforePan = panX to panY
        if (abs(contentZoom - 1f) < 0.012f) {
            contentZoom = 1f
        }
        if (contentFitsAt(contentZoom)) {
            val (cx, cy) = centeredPanAt(contentZoom)
            panX = cx
            panY = cy
        } else {
            clampPanSoft()
        }
        applyTransform()
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
            "settle z $beforeZ→$contentZoom pan $beforePan→($panX,$panY)",
        )
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
        val (tw, th) = targetContentSize(t)
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
        }
        val b = panBounds()
        // 1x 且内容不超出：居中（通常 0）；超出（横屏长页）允许在 bounds 内滑
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

        // 连续模式缩小：始终加高 RV（含捏合中），避免松手 tallRv ↔ 捏合 resetRv 来回切导致第二页黑屏
        if (z < 0.99f && continuousScrollWhenZoomed) {
            // 布局加高：缩放后高度铺满，列表可见范围变为原来的 1/z
            val layoutH = (vh / z).toInt().coerceAtLeast(vh)
            if (lp.width != LayoutParams.MATCH_PARENT || lp.height != layoutH) {
                lp.width = LayoutParams.MATCH_PARENT
                lp.height = layoutH
                t.layoutParams = lp
                ReaderLog.i(ReaderLog.Module.PDF_ZOOM,
                    "applyTransform tallRv z=$z pinching=$pinching layoutH=$layoutH vh=$vh " +
                        "targetWas=${t.height}",
                )
            }
        } else {
            val keepTall = allowTallZoomTarget &&
                lp.height > vh &&
                lp.height != LayoutParams.MATCH_PARENT
            val wantH = if (keepTall) lp.height else LayoutParams.MATCH_PARENT
            if (lp.width != LayoutParams.MATCH_PARENT || lp.height != wantH) {
                val prevH = lp.height
                lp.width = LayoutParams.MATCH_PARENT
                lp.height = wantH
                t.layoutParams = lp
                ReaderLog.i(ReaderLog.Module.PDF_ZOOM,
                    "applyTransform resetRv z=$z pinching=$pinching wantH=$wantH prevH=$prevH vh=$vh",
                )
            }
        }

        t.pivotX = 0f
        t.pivotY = 0f
        t.scaleX = z
        t.scaleY = z

        // ≤100% 强制居中（含捏合中）：松手不必再跳居中
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
        if (blockPanAfterPinch) {
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM, "Frame startPanFling blocked after pinch")
            return
        }
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
        val pending = pendingContinuousRestore
        if (pending != null && continuousScrollWhenZoomed && w > 0) {
            pendingContinuousRestore = null
            post { restoreContinuousTransform(pending) }
            return
        }
        // 旋转后旧的 continuous 加高 layout 可能残留，先清再按新 vh 应用
        if (abs(contentZoom - 1f) < 0.01f) {
            contentZoom = 1f
            panX = 0f
            panY = 0f
            val t = target()
            if (t != null) {
                val lp = t.layoutParams
                if (lp != null &&
                    (lp.width != LayoutParams.MATCH_PARENT || lp.height != LayoutParams.MATCH_PARENT)
                ) {
                    lp.width = LayoutParams.MATCH_PARENT
                    lp.height = LayoutParams.MATCH_PARENT
                    t.layoutParams = lp
                }
                t.scaleX = 1f
                t.scaleY = 1f
                t.translationX = 0f
                t.translationY = 0f
            }
        }
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
                // 新手势：解除「双指结束后禁 pan」——否则 multi 一直为 true，吞掉点按菜单
                blockPanAfterPinch = false
                // 连续未缩放：不要 stopScroll，否则快速连滑会掐断惯性并顿一下
                if (!(continuousScrollWhenZoomed && !isZoomed())) {
                    onStopScroll?.invoke()
                }
                obtainTracker().clear()
                // 尽早记下 down，供 GestureDetector 点按判定（须在 scale 之后、但 multi 之前不够早）
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                panning = false
                selecting = false
                fingerMoved = false
                tapConsumed = false
                sideTapFiredDownTime = -1L
                pageTurnLocked = isZoomed()
            }
        }
        obtainTracker().addMovement(ev)

        // 抬指/第三指：先冻结再喂 detector，避免本帧 span 突变改姿态
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinching && ev.pointerCount <= 2) {
                    pinchFrozen = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount > 2 && pinching) {
                    pinchFrozen = true
                }
            }
        }

        // 捏合必须先喂 scaleDetector，否则第二指落下时丢失 DOWN 序列
        scaleDetector.onTouchEvent(ev)
        // blockPanAfterPinch 仅用于「本段双指尚未全部抬起」：勿在新 DOWN 后仍为 true
        val multi = ev.pointerCount >= 2 ||
            pinching ||
            scaleDetector.isInProgress ||
            (blockPanAfterPinch && ev.actionMasked != MotionEvent.ACTION_DOWN)
        if (multi || pinching) {
            pageTurnLocked = true
            fingerMoved = true
        }

        // 连续模式未缩放：单指路径尽量短，把滚动交给 RecyclerView（对齐 Office 丝滑）
        // 本手势若曾双指缩放，pageTurnLocked 期间不走「侧边翻页」捷径
        if (continuousScrollWhenZoomed && !isZoomed() && !multi && !selecting && !handleDragActive && !pageTurnLocked) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    lastX = ev.x
                    lastY = ev.y
                    panning = false
                    fingerMoved = false
                    tapConsumed = false
                    sideTapFiredDownTime = -1L
                    pageTurnLocked = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dist = max(abs(ev.x - downX), abs(ev.y - downY))
                    if (dist > tapSlop) fingerMoved = true
                    lastX = ev.x
                    lastY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !fingerMoved && !selecting) {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        val total = max(abs(dx), abs(dy))
                        // 再保险：UP 时位移仍在阈值内
                        if (total <= tapSlop) {
                            var vx = 0f
                            var vy = 0f
                            velocityTracker?.let { vt ->
                                vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                                vx = vt.xVelocity
                                vy = vt.yVelocity
                            }
                            // 水平滑优先于点按
                            if (onHorizontalSwipe != null &&
                                trySwipePageTurn(vx, vy, dx, dy, edgeFling = false)
                            ) {
                                tapConsumed = true
                                recycleTracker()
                                // 仍把 UP 交给子 View，避免 RV 状态机卡住
                                gestureDetector.onTouchEvent(ev)
                                super.dispatchTouchEvent(ev)
                                return true
                            }
                            val w = width.toFloat().coerceAtLeast(1f)
                            when {
                                onSideTapImmediate != null && ev.x < w / 3f -> {
                                    if (fireSideTapOnce(ev, ev.x, ev.y)) {
                                        recycleTracker()
                                        gestureDetector.onTouchEvent(ev)
                                        return true
                                    }
                                }
                                onSideTapImmediate != null && ev.x > w * 2f / 3f -> {
                                    if (fireSideTapOnce(ev, ev.x, ev.y)) {
                                        recycleTracker()
                                        gestureDetector.onTouchEvent(ev)
                                        return true
                                    }
                                }
                                ev.x >= w / 3f && ev.x <= w * 2f / 3f -> {
                                    // 中部：只在这里触发一次菜单
                                    onSingleTap?.invoke(ev.x, ev.y)
                                    tapConsumed = true
                                }
                            }
                        }
                    }
                    recycleTracker()
                }
            }
            // long-press 仍要 GestureDetector；onSingleTapUp 见 tapConsumed 防双开
            gestureDetector.onTouchEvent(ev)
            super.dispatchTouchEvent(ev)
            return true
        }

        gestureDetector.onTouchEvent(ev)

        if (multi) {
            parent?.requestDisallowInterceptTouchEvent(true)
            panning = false
            pageTurnLocked = true
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_UP -> {
                    pageTurnLocked = true
                }
                MotionEvent.ACTION_MOVE -> {
                    // block 期：只更新 last，不改 pan
                    lastX = ev.x
                    lastY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pinching) {
                        pinching = false
                        pinchFrozen = false
                        blockPanAfterPinch = true
                        settleAfterPinch()
                        onZoomChanged?.invoke(contentZoom)
                    }
                    if (blockPanAfterPinch) {
                        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
                            "Frame releaseNoFling afterPinch z=$contentZoom " +
                                "pan=($panX,$panY)",
                        )
                    }
                    // 多指手势整段结束：禁止本 UP 再走侧边翻页；锁保留到下次 DOWN
                    recycleTracker()
                }
            }
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // down/坐标/block 已在分发入口处理
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onSelectionDrag?.invoke(ev.x, ev.y, false)
                    lastX = ev.x
                    lastY = ev.y
                    return true
                }
                if (handleDragActive) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = ev.x
                    lastY = ev.y
                } else if (blockPanAfterPinch) {
                    lastX = ev.x
                    lastY = ev.y
                    return true
                } else if (isZoomed() || canPanContent()) {
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
                            // 已缩放且非双指手势：水平顶边 fling 可翻页
                            if (isZoomed() &&
                                !pageTurnLocked &&
                                !blockPanAfterPinch &&
                                onHorizontalSwipe != null &&
                                trySwipePageTurn(vx, vy, ev.x - downX, ev.y - downY, edgeFling = true)
                            ) {
                                recycleTracker()
                                return true
                            }
                            // 水平 pan 惯性（及单页模式竖向 pan）
                            if (!blockPanAfterPinch) {
                                startPanFling(vx, vy)
                                // 连续模式竖向：交给 RecyclerView.fling 以获得惯性
                                if (continuousScrollWhenZoomed && abs(vy) >= minFlingVelocity) {
                                    onFlingScroll?.invoke(vx, vy)
                                }
                            }
                        }
                    }
                    recycleTracker()
                    return true
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP && !pinching && !pageTurnLocked) {
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
                    // 2) 侧边轻点：立即翻页（双指缩放手势中禁止；放大态也允许）
                    if (!tapConsumed && onSideTapImmediate != null && total <= touchSlop) {
                        if (fireSideTapOnce(ev, ev.x, ev.y)) {
                            recycleTracker()
                            return true
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

        // 全程位移以竖直为主时绝不翻页：竖滑看 PDF 时松手瞬间常带水平速度，
        // 若只看 velocity 会把「已滑到上一页」再 pageTurn 弹回（如 113→112 又跳回 113）
        if (absDy > absDx) return false
        if (absDy >= swipeMinDistance && absDy >= absDx * 0.85f) return false

        // 必须以水平为主（位移或速度），且两者都不能明显竖向
        val distanceOk = absDx >= swipeMinDistance && absDx > absDy * 1.2f
        val velocityOk = absVx >= swipeMinVelocity &&
            absVx > absVy * 1.2f &&
            absDx >= absDy // 总位移也不能更偏竖
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
