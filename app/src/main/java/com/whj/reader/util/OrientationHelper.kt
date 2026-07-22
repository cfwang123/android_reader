package com.whj.reader.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.View
import com.whj.reader.R
import com.whj.reader.model.OrientationMode

/**
 * 阅读页方向：仅「竖屏 / 横屏」两档，**一律锁定系统方向并占满窗口**。
 *
 * - 不再做大屏 FULL_SENSOR「只改布局」——那会让横竖模式都停在横持窗口，
 *   竖屏再套中间竖栏，看起来「两种都是横屏、竖屏只用中间一条」。
 * - 不再做 portraitColumnPadding 收栏。
 * - 已废弃 AUTO，映射为竖屏。
 */
object OrientationHelper {

    private const val TAG = "OrientHelper"

    fun isLargeScreen(activity: Activity): Boolean =
        activity.resources.configuration.smallestScreenWidthDp >= 600

    /**
     * @return true 若改写了 [Activity.requestedOrientation]
     */
    fun apply(
        activity: Activity,
        mode: OrientationMode,
        allowSensor: Boolean = false,
        force: Boolean = false,
    ): Boolean {
        val fixed = if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
        val target = resolveSystemOrientation(fixed)
        val prev = activity.requestedOrientation
        Log.i(TAG, "apply mode=$fixed prev=$prev target=$target force=$force")
        if (prev == target) {
            Log.i(TAG, "apply skip same")
            return false
        }
        // 直接设目标，禁止 UNSPECIFIED 中转（避免连闪）
        activity.requestedOrientation = target
        Log.i(TAG, "apply done after=${activity.requestedOrientation}")
        return true
    }

    fun resolveSystemOrientation(mode: OrientationMode): Int {
        return when (mode) {
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationMode.PORTRAIT,
            OrientationMode.AUTO,
            -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** 布局是否按横屏：跟用户选择一致 */
    fun isEffectiveLandscape(
        activity: Activity,
        mode: OrientationMode,
        root: View? = null,
    ): Boolean {
        return when (mode) {
            OrientationMode.LANDSCAPE -> true
            OrientationMode.PORTRAIT -> false
            OrientationMode.AUTO -> isWindowLandscape(activity, root)
        }
    }

    fun isWindowLandscape(activity: Activity, root: View? = null): Boolean {
        if (root != null) {
            val w = root.width
            val h = root.height
            if (w > 0 && h > 0) return w > h
        }
        val cfg = activity.resources.configuration
        if (cfg.screenWidthDp > 0 && cfg.screenHeightDp > 0) {
            return cfg.screenWidthDp > cfg.screenHeightDp
        }
        val dm = activity.resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }

    /** 已废弃：始终 0，内容占满窗口 */
    @Suppress("UNUSED_PARAMETER")
    fun portraitColumnPadding(
        activity: Activity,
        mode: OrientationMode,
        rootW: Int,
        rootH: Int,
    ): Pair<Int, Int> = 0 to 0

    fun menuIconRes(mode: OrientationMode): Int = when (mode) {
        OrientationMode.LANDSCAPE -> R.drawable.ic_menu_orient_landscape
        else -> R.drawable.ic_menu_orient_portrait
    }

    fun isDisplayLandscape(activity: Activity): Boolean {
        val rot = if (Build.VERSION.SDK_INT >= 30) {
            activity.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        } ?: Surface.ROTATION_0
        return rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
    }
}
