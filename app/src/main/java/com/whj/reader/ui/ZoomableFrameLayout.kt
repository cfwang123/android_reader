package com.whj.reader.ui

import com.whj.reader.util.ReaderLog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.hypot
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
    /**
     * 长按即将触发时询问：无文字层 / 点在空白处应返回 false，避免空长按后再拖卡顿。
     * null 视为允许。
     */
    var onLongPressEligible: ((x: Float, y: Float) -> Boolean)? = null
    /** DOWN 时预热（如静默抽字），不弹 UI */
    var onLongPressPrepare: ((x: Float, y: Float) -> Unit)? = null
    var onSelectionDrag: ((x: Float, y: Float, ended: Boolean) -> Unit)? = null
    /**
     * 是否已有真实文字选区（长按已落字）。
     * 未就绪时若手指滑出 touchSlop，取消选字手势改走 pan，避免「按住后既不能拖又变成选字」。
     */
    var isSelectionLive: (() -> Boolean)? = null
    /** 选字手势被本层取消（滑动改 pan / 显式 cancel）时回调，用于作废异步抽字 */
    var onSelectionGestureCancel: (() -> Unit)? = null

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
    /**
     * 自有长按计时：未触发前任何移动都取消长按并走 pan。
     * 不用 GestureDetector.onLongPress（其与 pan 抢手势，易「先滑再变成选字」）。
     */
    private val longPressHandler = Handler(Looper.getMainLooper())
    /** PDF 等选字：静止长按 1 秒（不用系统默认 ~400ms，减少与 pan 误触） */
    private val longPressTimeoutMs = 1_000L
    private var longPressPending = false
    private val longPressRunnable = Runnable { fireLongPressIfEligible() }
    /** ACTION_DOWN 时刻（elapsedRealtime），用于「按住再拖」立即 pan */
    private var downElapsedMs = 0L
    /** MotionEvent.downTime，供 HOLD_ARM 时合成 CANCEL */
    private var gestureDownTime = 0L
    /** 本手下首次 MOVE 时刻；用于量 lagFromFirstMove */
    private var firstMoveElapsedMs = 0L
    /** 本手下是否已打过 FIRST_MOVE log */
    private var loggedFirstMove = false
    /** 连续直接滚动起始时刻 */
    private var contDirectStartMs = 0L
    /** 本手下 SCROLL_APPLY 打点次数（限流） */
    private var scrollApplyLogCount = 0
    /**
     * 按下静止超过该时间：取消子 View 系统长按，缩小本层开滑判定 slop。
     * 取 ~50ms。**不要**在此时重启 RV 触摸——快速连滑时 DOWN→MOVE 常超过 50ms，
     * 每次 CANCEL+新 DOWN 会造成「略停再拖就卡一下」。
     */
    private val holdThenPanMs = 50L
    /**
     * 仅当按下静止达到该时长（接近系统长按）再开拖时，才重启 RV 触摸序列。
     * 短暂停顿 / 普通滑动不重启，避免快速随机 pan 卡顿。
     */
    private val rvRestartHoldMs = 320L
    private val holdArmRunnable = Runnable { armHoldThenPan() }
    /** 本手下已因移动取消过长按（仅日志/选字；滚动仍走 RV） */
    private var panArmedLogged = false
    /**
     * 按住后再拖：已对 RV 做过手势重启；后续 MOVE/UP 用 [rvRestartDownTime] 改写后再派给子 View。
     */
    private var rvTouchRestarted = false
    private var rvRestartDownTime = 0L
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

    /**
     * 系统 ScaleGestureDetector 默认 mMinSpan（约 27mm）+ mSpanSlop（2×touchSlop），
     * 双指按下后要捏合一大段才 onScaleBegin，体感「前一段没反应」。
     * PDF 捏合改由本层在第二指落下时立即锚定 span，并在 MOVE 上直接 apply。
     * 仍保留 detector（关掉 quick scale）供兼容，但不再驱动缩放。
     */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = false
            override fun onScale(detector: ScaleGestureDetector): Boolean = false
        },
    ).also {
        it.isQuickScaleEnabled = false
        relaxScaleDetectorThresholds(it)
    }

    /** 尽量取消系统捏合死区（字段名随 API 可能不存在，失败则忽略）。 */
    private fun relaxScaleDetectorThresholds(detector: ScaleGestureDetector) {
        try {
            val cls = ScaleGestureDetector::class.java
            cls.getDeclaredField("mMinSpan").apply {
                isAccessible = true
                setInt(detector, 0)
            }
            cls.getDeclaredField("mSpanSlop").apply {
                isAccessible = true
                setInt(detector, 0)
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * 两指焦点与间距（pointer 0/1）。第二指 [ACTION_POINTER_DOWN] 时即可用，
     * 无需等系统 ScaleGestureDetector 越过 minSpan。
     */
    private fun twoFingerFocusSpan(ev: MotionEvent): FloatArray? {
        if (ev.pointerCount < 2) return null
        val x0 = ev.getX(0)
        val y0 = ev.getY(0)
        val x1 = ev.getX(1)
        val y1 = ev.getY(1)
        val span = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat().coerceAtLeast(1f)
        return floatArrayOf((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, span)
    }

    private fun beginPinchFromTouch(ev: MotionEvent) {
        val fs = twoFingerFocusSpan(ev) ?: return
        pinching = true
        pageTurnLocked = true
        selecting = false
        pinchFrozen = false
        blockPanAfterPinch = true
        pinchFreeMode = !contentFitsAt(contentZoom)
        capturePinchStartAt(
            contentZoom.coerceAtLeast(0.01f),
            panX,
            panY,
            fs[0],
            fs[1],
            fs[2],
        )
        pinchLastFocusY = fs[1]
        pinchLastFocusYValid = false
        lastX = fs[0]
        lastY = fs[1]
        abortPanFling()
        parent?.requestDisallowInterceptTouchEvent(true)
        ReaderLog.i(
            ReaderLog.Module.MANGA_ZOOM,
            "FramePinchBegin z=$contentZoom span=${"%.1f".format(fs[2])} " +
                "focus=(${"%.0f".format(fs[0])},${"%.0f".format(fs[1])})",
        )
    }

    private fun updatePinchFromTouch(ev: MotionEvent) {
        if (!pinching || pinchFrozen) return
        val fs = twoFingerFocusSpan(ev) ?: return
        applyPinchAbsolute(fs[0], fs[1], fs[2])
        pageTurnLocked = true
        lastX = fs[0]
        lastY = fs[1]
    }

    private fun endPinchFromTouch(focusX: Float, focusY: Float) {
        if (!pinching) return
        val beforeZ = contentZoom
        val beforeX = panX
        val beforeY = panY
        pinching = false
        pageTurnLocked = true
        pinchFrozen = false
        blockPanAfterPinch = true
        abortPanFling()
        settleAfterPinch()
        lastX = focusX
        lastY = focusY
        val dPan = max(abs(panX - beforeX), abs(panY - beforeY))
        ReaderLog.i(
            ReaderLog.Module.MANGA_ZOOM,
            "FramePinchEnd z ${"%.3f".format(beforeZ)}→${"%.3f".format(contentZoom)} " +
                "pan (${"%.1f".format(beforeX)},${"%.1f".format(beforeY)})→" +
                "(${"%.1f".format(panX)},${"%.1f".format(panY)}) " +
                "dPan=${"%.1f".format(dPan)} blockMoveAndFling=true",
        )
        onZoomChanged?.invoke(contentZoom)
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
                // 选区取消：中部/侧边均可点按触发 onSingleTap（由 Activity 清选区）
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

            // 长按改由 [scheduleLongPress] 自管，避免与 pan 冲突
            override fun onLongPress(e: MotionEvent) = Unit
        },
    ).also {
        // 关闭 GD 长按，统一用自有计时（未触发前移动 = pan）
        it.setIsLongpressEnabled(false)
    }

    /** 诊断「按住再 pan」：随 MangaZoom 模块开关（ReaderLog） */
    private fun zLog(msg: String) {
        if (!ReaderLog.isEnabled(ReaderLog.Module.MANGA_ZOOM)) return
        Log.i("ZFrame", msg)
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM, "ZFrame $msg")
    }

    private fun heldMsNow(): Long =
        if (downElapsedMs <= 0L) -1L else SystemClock.elapsedRealtime() - downElapsedMs

    private fun lagFromFirstMoveMs(): Long =
        if (firstMoveElapsedMs <= 0L) -1L else SystemClock.elapsedRealtime() - firstMoveElapsedMs

    private fun noteFirstMoveIfNeeded(ev: MotionEvent, path: String) {
        if (loggedFirstMove) return
        loggedFirstMove = true
        firstMoveElapsedMs = SystemClock.elapsedRealtime()
        val dist = max(abs(ev.x - downX), abs(ev.y - downY))
        zLog(
            "FIRST_MOVE held=${heldMsNow()}ms path=$path " +
                "dist=${"%.1f".format(dist)} slop=${panActivationSlop()} " +
                "sysLpTimeout=${ViewConfiguration.getLongPressTimeout()} " +
                "cont=$continuousScrollWhenZoomed zoomed=${isZoomed()} " +
                "lpPending=$longPressPending selecting=$selecting",
        )
    }

    private fun scheduleLongPress() {
        cancelLongPressSchedule()
        cancelHoldArm()
        if (onLongPress == null) {
            // 无选字回调时仍要 arm，取消 RV 系统长按
            longPressHandler.postDelayed(holdArmRunnable, holdThenPanMs)
            return
        }
        longPressPending = true
        longPressHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
        longPressHandler.postDelayed(holdArmRunnable, holdThenPanMs)
        // 静默预热文字缓存，便于 1s 到期时判定「有无字 / 是否点在字上」
        onLongPressPrepare?.invoke(downX, downY)
    }

    private fun cancelLongPressSchedule() {
        if (longPressPending) {
            zLog("cancelLongPressSchedule held=${heldMsNow()}ms")
        }
        longPressPending = false
        longPressHandler.removeCallbacks(longPressRunnable)
    }

    private fun cancelHoldArm() {
        longPressHandler.removeCallbacks(holdArmRunnable)
    }

    /**
     * 按住片刻：只取消系统长按 / pressed，**不** CANCEL 触摸序列、不接管 scrollBy。
     * 连续滚动继续由 RecyclerView 原生跟手；抢手势用 scrollBy 会造成「比手慢」。
     */
    private fun armHoldThenPan() {
        if (fingerMoved || panning || pinching || selecting) {
            zLog(
                "HOLD_ARM skip held=${heldMsNow()}ms moved=$fingerMoved pan=$panning sel=$selecting",
            )
            return
        }
        cancelChildLongPress()
        clearChildPressedState()
        parent?.requestDisallowInterceptTouchEvent(true)
        // 已能判定无字 / 空白：取消待选字，避免 1s 后空触发再拖卡顿
        if (longPressPending && onLongPressEligible?.invoke(downX, downY) == false) {
            cancelLongPressSchedule()
            zLog("HOLD_ARM cancel LP: not eligible at down (no text / blank)")
        }
        zLog(
            "HOLD_ARM ok held=${heldMsNow()}ms holdThenPanMs=$holdThenPanMs " +
                "sysLpTimeout=${ViewConfiguration.getLongPressTimeout()} " +
                "cont=$continuousScrollWhenZoomed zoomed=${isZoomed()} (native RV scroll)",
        )
    }

    /** 清除子 View pressed，减轻系统长按后的拖动手势顿挫（不中断触摸序列） */
    private fun clearChildPressedState() {
        fun clear(v: View?) {
            if (v == null) return
            v.isPressed = false
            v.cancelLongPress()
        }
        clear(this)
        clear(target())
        continuousScrollTarget()?.let { rv ->
            clear(rv)
            for (i in 0 until rv.childCount) clear(rv.getChildAt(i))
        }
    }

    /** 结束子 View 当前触摸序列（合成 CANCEL），不阻止后续再派发新 DOWN */
    private fun endChildTouchSequence(reason: String) {
        val downT = if (gestureDownTime > 0L) gestureDownTime else SystemClock.uptimeMillis()
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(downT, now, MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        zLog("RV_END_TOUCH reason=$reason held=${heldMsNow()}ms")
    }

    /** 用指定 downTime 把单指事件派给子 View（用于按住后再拖重启 RV 手势） */
    private fun dispatchChildrenRewritten(ev: MotionEvent, downTime: Long): Boolean {
        val rewritten = MotionEvent.obtain(
            downTime,
            ev.eventTime,
            ev.actionMasked,
            ev.x,
            ev.y,
            ev.metaState,
        )
        return try {
            super.dispatchTouchEvent(rewritten)
        } finally {
            rewritten.recycle()
        }
    }

    /**
     * 按住后再开滑：结束 RV 旧触摸（可能已进系统长按态），再发新 DOWN，
     * 之后 MOVE 走原生滚动，避免 scrollBy 跟手滞后。
     *
     * DOWN 不能落在 [lastX]/[lastY]：RV 自己还有 touchSlop≈30px，慢拖时要滑很久才过死区
     * （体感开头空滑约 0.2s）。把 DOWN 沿开滑方向 **反向** 垫出 slop，使本帧 MOVE 立刻进入拖动。
     */
    private fun restartChildTouchForPan(ev: MotionEvent) {
        if (rvTouchRestarted) return
        endChildTouchSequence("hold_then_pan")
        val now = SystemClock.uptimeMillis()
        rvRestartDownTime = now
        rvTouchRestarted = true
        val dx = ev.x - lastX
        val dy = ev.y - lastY
        val dist = hypot(dx, dy)
        val pad = (touchSlop + 2).toFloat()
        val downX: Float
        val downY: Float
        if (dist >= 1f) {
            val s = pad / dist
            downX = lastX - dx * s
            downY = lastY - dy * s
        } else {
            // 尚无明确方向：默认向上开滑常见，向下垫 DOWN
            downX = lastX
            downY = lastY + pad
        }
        val down = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_DOWN,
            downX,
            downY,
            ev.metaState,
        )
        try {
            super.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }
        val toMove = hypot(ev.x - downX, ev.y - downY)
        val sy = continuousScrollTarget()?.computeVerticalScrollOffset() ?: -1
        zLog(
            "RV_RESTART held=${heldMsNow()}ms lagFromFirstMove=${lagFromFirstMoveMs()}ms " +
                "down=(${"%.0f".format(downX)},${"%.0f".format(downY)}) " +
                "move=(${"%.0f".format(ev.x)},${"%.0f".format(ev.y)}) " +
                "toMove=${"%.1f".format(toMove)} pad=$pad touchSlop=$touchSlop scrollY=$sy",
        )
    }

    private fun cancelChildLongPress() {
        cancelLongPress()
        target()?.cancelLongPress()
        continuousScrollTarget()?.cancelLongPress()
        // 子 item / surface 也可能挂了长按
        continuousScrollTarget()?.let { rv ->
            for (i in 0 until rv.childCount) {
                rv.getChildAt(i)?.cancelLongPress()
            }
        }
    }

    /**
     * 静止按住超时 → 尝试选字。
     * 注意：此处**不**置 [selecting]=true；只有真正落字后由 [enterSelectingMode] 进入，
     * 否则抽字/无字期间会挡住 continuous pan，出现「按住再拖卡一会」。
     */
    private fun fireLongPressIfEligible() {
        if (!longPressPending) return
        longPressPending = false
        if (pinching || panning || fingerMoved || selecting || handleDragActive) {
            zLog(
                "LONG_PRESS abort held=${heldMsNow()}ms moved=$fingerMoved pan=$panning sel=$selecting",
            )
            return
        }
        val moved = max(abs(lastX - downX), abs(lastY - downY))
        if (moved > touchSlop) {
            zLog("LONG_PRESS abort moved=${"%.1f".format(moved)} > slop=$touchSlop")
            return
        }
        abortPanFling()
        cancelChildLongPress()
        if (onLongPressEligible?.invoke(lastX, lastY) == false) {
            zLog(
                "LONG_PRESS skip held=${heldMsNow()}ms xy=(${"%.0f".format(lastX)},${"%.0f".format(lastY)}) " +
                    "(no text / not on glyph)",
            )
            return
        }
        zLog("LONG_PRESS fire held=${heldMsNow()}ms xy=(${"%.0f".format(lastX)},${"%.0f".format(lastY)})")
        onLongPress?.invoke(lastX, lastY)
    }

    /**
     * 调试：应用内精确注入「按住 holdMs 再竖向 pan」。
     * 避开 adb motionevent 百毫秒级延迟，用于量 CONT_DIRECT / SCROLL lag。
     *
     * adb: am broadcast -a com.whj.reader.DEBUG_PDF_HOLD_PAN -p com.whj.reader
     *      --ei hold_ms 500 --ei dy -400
     */
    fun debugSimulateHoldThenPan(
        holdMs: Long = 500L,
        dyPx: Float = -400f,
        steps: Int = 16,
        stepMs: Long = 8L,
        xRatio: Float = 0.5f,
        yRatio: Float = 0.5f,
    ) {
        val w = width.takeIf { it > 0 } ?: return
        val h = height.takeIf { it > 0 } ?: return
        val x = w * xRatio
        val y0 = h * yRatio
        val downTime = SystemClock.uptimeMillis()
        zLog(
            "SIM_START holdMs=$holdMs dy=$dyPx steps=$steps stepMs=$stepMs " +
                "xy=(${"%.0f".format(x)},${"%.0f".format(y0)}) " +
                "cont=$continuousScrollWhenZoomed zoomed=${isZoomed()}",
        )
        fun inject(action: Int, y: Float, eventTime: Long) {
            val ev = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                0,
            )
            dispatchTouchEvent(ev)
            ev.recycle()
        }
        // DOWN 立即
        inject(MotionEvent.ACTION_DOWN, y0, downTime)
        // hold 后再分步 MOVE
        longPressHandler.postDelayed({
            val moveStart = SystemClock.uptimeMillis()
            zLog(
                "SIM_MOVE_BEGIN wallHold=${moveStart - downTime}ms targetHold=$holdMs",
            )
            for (i in 1..steps) {
                val yi = y0 + dyPx * i / steps
                val et = moveStart + stepMs * i
                longPressHandler.postDelayed({
                    inject(MotionEvent.ACTION_MOVE, yi, et)
                    if (i == steps) {
                        longPressHandler.postDelayed({
                            val upTime = SystemClock.uptimeMillis()
                            inject(MotionEvent.ACTION_UP, y0 + dyPx, upTime)
                            zLog(
                                "SIM_END wallTotal=${upTime - downTime}ms " +
                                    "movePhase=${upTime - moveStart}ms",
                            )
                        }, stepMs)
                    }
                }, stepMs * i)
            }
        }, holdMs)
    }

    /** 落字成功后进入选区拖动手势（由选字逻辑调用） */
    fun enterSelectingMode() {
        selecting = true
        cancelLongPressSchedule()
        cancelHoldArm()
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    /** 外部取消选字手势（抽字失败 / 无文字等），恢复 pan */
    fun cancelSelectingGesture() {
        cancelLongPressSchedule()
        cancelHoldArm()
        if (!selecting) return
        selecting = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** 本手势已出现移动：取消待长按/待抽字，后续只 pan（直至抬手） */
    private fun markFingerMovedAndCancelLongPress() {
        if (!fingerMoved) fingerMoved = true
        if (longPressPending) cancelLongPressSchedule()
        cancelHoldArm()
        // 长按已触发但尚未落字：移动作废异步抽字，立刻允许 pan
        if (!selecting) {
            onSelectionGestureCancel?.invoke()
        }
    }

    private fun heldBeforeMove(): Boolean =
        downElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - downElapsedMs >= holdThenPanMs

    /** 按住足够久（近系统长按）再开拖：才需要重启 RV，避开长按态空滑 */
    private fun shouldRestartRvAfterHold(): Boolean =
        downElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - downElapsedMs >= rvRestartHoldMs

    /** 按住后再拖：更早取消选字；立即滑动仍用系统 touchSlop 防误触 */
    private fun panActivationSlop(): Float =
        if (heldBeforeMove() || panning) 2f else touchSlop.toFloat()

    /** 开始拖动：取消子 View 系统长按 / pressed（不中断 RV 触摸序列） */
    private fun onPanGestureStarted() {
        cancelLongPressSchedule()
        cancelHoldArm()
        cancelChildLongPress()
        clearChildPressedState()
        parent?.requestDisallowInterceptTouchEvent(true)
        if (!selecting) {
            onSelectionGestureCancel?.invoke()
        }
    }

    private fun continuousScrollTarget(): RecyclerView? =
        (zoomTarget as? RecyclerView)
            ?: (getChildAt(0) as? RecyclerView)

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

    override fun onDetachedFromWindow() {
        cancelLongPressSchedule()
        cancelHoldArm()
        super.onDetachedFromWindow()
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

    /**
     * 容器坐标 → zoomTarget 内容坐标（与 [applyTransform] 互逆，含 target 的 layout 偏移）。
     */
    fun mapToContent(x: Float, y: Float): PointF {
        val t = target()
        val tl = t?.left?.toFloat() ?: 0f
        val tt = t?.top?.toFloat() ?: 0f
        val z = contentZoom.coerceAtLeast(0.01f)
        return PointF(
            (x - panX - tl) / z,
            (y - panY - tt) / z,
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
                panArmedLogged = false
                rvTouchRestarted = false
                rvRestartDownTime = 0L
                downElapsedMs = SystemClock.elapsedRealtime()
                gestureDownTime = ev.downTime
                firstMoveElapsedMs = 0L
                loggedFirstMove = false
                contDirectStartMs = 0L
                scrollApplyLogCount = 0
                tapConsumed = false
                sideTapFiredDownTime = -1L
                // 仅双指/多指期间锁翻页；放大态仍允许侧点翻页（pageTurnLocked≠isZoomed）
                pageTurnLocked = false
                // 静止长按选字；片刻后取消子 View 系统长按；移动则 cancel（见 MOVE）
                scheduleLongPress()
                zLog(
                    "DOWN xy=(${"%.0f".format(ev.x)},${"%.0f".format(ev.y)}) " +
                        "cont=$continuousScrollWhenZoomed zoomed=${isZoomed()} " +
                        "sysLpTimeout=${ViewConfiguration.getLongPressTimeout()} " +
                        "touchSlop=$touchSlop holdThenPanMs=$holdThenPanMs lpTimeout=$longPressTimeoutMs",
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                zLog(
                    "UP/CANCEL action=${ev.actionMasked} held=${heldMsNow()}ms " +
                        "moved=$fingerMoved pan=$panning " +
                        "selecting=$selecting lagFromFirstMove=${lagFromFirstMoveMs()}ms",
                )
                // 抬手未到时长：不算长按
                if (!selecting) {
                    cancelLongPressSchedule()
                    cancelHoldArm()
                }
            }
        }
        obtainTracker().addMovement(ev)

        // 本层立即捏合：第二指落下锚定 span（绕过系统 minSpan 死区）
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    // 第二指落下：立刻开始缩放，取消长按选字
                    markFingerMovedAndCancelLongPress()
                    onSelectionGestureCancel?.invoke()
                    beginPinchFromTouch(ev)
                } else if (ev.pointerCount > 2 && pinching) {
                    // 第三指：冻结，避免 span 跳变
                    pinchFrozen = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && !pinchFrozen && ev.pointerCount >= 2) {
                    updatePinchFromTouch(ev)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // pointerCount 含即将抬起的指；两指变一指时结束捏合
                if (pinching && ev.pointerCount == 2) {
                    pinchFrozen = true
                    val fs = twoFingerFocusSpan(ev)
                    endPinchFromTouch(fs?.get(0) ?: ev.x, fs?.get(1) ?: ev.y)
                } else if (pinching && ev.pointerCount > 2) {
                    // 多指减少但仍 ≥2：保持冻结直到全部抬起再 settle（已 end 过则 no-op）
                    pinchFrozen = true
                }
            }
        }

        // 仍喂 detector（已 no-op），避免个别 ROM 手势状态异常
        scaleDetector.onTouchEvent(ev)
        // blockPanAfterPinch 仅用于「本段双指尚未全部抬起」：勿在新 DOWN 后仍为 true
        val multi = ev.pointerCount >= 2 ||
            pinching ||
            scaleDetector.isInProgress ||
            (blockPanAfterPinch && ev.actionMasked != MotionEvent.ACTION_DOWN)
        if (multi || pinching) {
            pageTurnLocked = true
            markFingerMovedAndCancelLongPress()
        }

        // 连续模式未缩放：滚动一律交给 RecyclerView 原生（跟手）；本层只处理点按/长按选字
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
                    panArmedLogged = false
                    // rvTouchRestarted 已在分发入口 DOWN 复位
                    tapConsumed = false
                    sideTapFiredDownTime = -1L
                    pageTurnLocked = false
                }
                MotionEvent.ACTION_MOVE -> {
                    noteFirstMoveIfNeeded(ev, "cont")
                    val dist = max(abs(ev.x - downX), abs(ev.y - downY))
                    val slop = panActivationSlop()
                    val dy = ev.y - lastY
                    if (dist > slop) {
                        markFingerMovedAndCancelLongPress()
                        if (!panArmedLogged) {
                            panArmedLogged = true
                            contDirectStartMs = SystemClock.elapsedRealtime()
                            onPanGestureStarted()
                            // 仅长按住（≥ rvRestartHoldMs）再拖才重启；短暂停顿不重启以免连滑卡顿
                            val doRestart = shouldRestartRvAfterHold()
                            if (doRestart) {
                                restartChildTouchForPan(ev)
                            }
                            val rv = continuousScrollTarget()
                            val sy = rv?.computeVerticalScrollOffset() ?: -1
                            zLog(
                                "CONT_NATIVE_ARM held=${heldMsNow()}ms " +
                                    "lagFromFirstMove=${lagFromFirstMoveMs()}ms " +
                                    "dist=${"%.1f".format(dist)} slop=$slop dy=${"%.1f".format(dy)} " +
                                    "scrollY=$sy doRestart=$doRestart restarted=$rvTouchRestarted",
                            )
                        }
                    }
                    if (fingerMoved && scrollApplyLogCount < 8) {
                        val rv = continuousScrollTarget()
                        val sy0 = rv?.computeVerticalScrollOffset() ?: -1
                        if (scrollApplyLogCount == 0 || abs(dy) > 0.5f) {
                            scrollApplyLogCount++
                            zLog(
                                "CONT_MOVE #$scrollApplyLogCount held=${heldMsNow()}ms " +
                                    "lagFromFirstMove=${lagFromFirstMoveMs()}ms " +
                                    "fingerDy=${"%.1f".format(dy)} scrollY=$sy0 " +
                                    "restarted=$rvTouchRestarted",
                            )
                        }
                    }
                    lastX = ev.x
                    lastY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!selecting) cancelLongPressSchedule()
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !fingerMoved && !selecting) {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        val total = max(abs(dx), abs(dy))
                        if (total <= tapSlop) {
                            var vx = 0f
                            var vy = 0f
                            velocityTracker?.let { vt ->
                                vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                                vx = vt.xVelocity
                                vy = vt.yVelocity
                            }
                            if (onHorizontalSwipe != null &&
                                trySwipePageTurn(vx, vy, dx, dy, edgeFling = false)
                            ) {
                                tapConsumed = true
                                recycleTracker()
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
                                    onSingleTap?.invoke(ev.x, ev.y)
                                    tapConsumed = true
                                }
                            }
                        }
                    }
                    recycleTracker()
                }
            }
            gestureDetector.onTouchEvent(ev)
            // 重启后的 MOVE/UP：用新 downTime 派给 RV，保证原生跟手
            if (rvTouchRestarted &&
                (ev.actionMasked == MotionEvent.ACTION_MOVE ||
                    ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL)
            ) {
                dispatchChildrenRewritten(ev, rvRestartDownTime)
            } else if (!(rvTouchRestarted && ev.actionMasked == MotionEvent.ACTION_DOWN)) {
                // 重启时 DOWN 已单独发过；其余（含未重启）原样交给 RV
                super.dispatchTouchEvent(ev)
            }
            if (ev.actionMasked == MotionEvent.ACTION_MOVE &&
                panArmedLogged &&
                scrollApplyLogCount in 1..8
            ) {
                val rv = continuousScrollTarget()
                val sy1 = rv?.computeVerticalScrollOffset() ?: -1
                zLog(
                    "CONT_AFTER_RV #$scrollApplyLogCount scrollY=$sy1 " +
                        "lagFromFirstMove=${lagFromFirstMoveMs()}ms restarted=$rvTouchRestarted",
                )
            }
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
                noteFirstMoveIfNeeded(ev, "default")
                val totalFromDown = max(abs(ev.x - downX), abs(ev.y - downY))
                val panSlop = panActivationSlop()
                // 规则：长按未触发前，移动一律 pan，并永久取消本手下的长按
                if (!selecting && totalFromDown > panSlop) {
                    markFingerMovedAndCancelLongPress()
                }

                if (selecting) {
                    val live = isSelectionLive?.invoke() == true
                    if (!live && totalFromDown > panSlop) {
                        // 长按已触发但文字未就绪：滑动则放弃选字，改 pan
                        selecting = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        onSelectionGestureCancel?.invoke()
                        // 继续走下方 pan / 列表滚动
                    } else {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // 仅在已有选区时扩展；未就绪且几乎未动则按住等待抽字
                        if (live) {
                            onSelectionDrag?.invoke(ev.x, ev.y, false)
                        }
                        lastX = ev.x
                        lastY = ev.y
                        return true
                    }
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
                        if (totalFromDown > panSlop) {
                            panning = true
                            onPanGestureStarted()
                            zLog(
                                "PAN_START held=${heldMsNow()}ms lagFromFirstMove=${lagFromFirstMoveMs()}ms " +
                                    "total=${"%.1f".format(totalFromDown)} slop=$panSlop " +
                                    "zoomed=${isZoomed()} cont=$continuousScrollWhenZoomed",
                            )
                        }
                    }
                    if (panning) {
                        noteFirstMoveIfNeeded(ev, "pan")
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
                        if (scrollApplyLogCount < 4) {
                            scrollApplyLogCount++
                            zLog(
                                "PAN_APPLY #$scrollApplyLogCount held=${heldMsNow()}ms " +
                                    "lagFromFirstMove=${lagFromFirstMoveMs()}ms " +
                                    "d=(${"%.1f".format(dx)},${"%.1f".format(dy)}) " +
                                    "pan=($panX,$panY)",
                            )
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
                if (!selecting) cancelLongPressSchedule()
                if (selecting) {
                    val live = isSelectionLive?.invoke() == true
                    if (live) {
                        onSelectionDrag?.invoke(ev.x, ev.y, true)
                    } else {
                        // 长按未落字就抬手：不进入选区
                        onSelectionGestureCancel?.invoke()
                    }
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
