package com.whj.reader.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.whj.reader.R
import com.whj.reader.model.OrientationMode

/**
 * 阅读页屏幕方向：
 * - 竖屏 / 横屏：锁定，不启用方向传感器
 * - 自动：仅在前台时使用传感器；进入后台时锁在当前方向以停止监听
 */
object OrientationHelper {

    /**
     * @param allowSensor 是否允许传感器（前台且用户选了「自动」时为 true）
     */
    fun apply(activity: Activity, mode: OrientationMode, allowSensor: Boolean) {
        activity.requestedOrientation = when (mode) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationMode.AUTO -> {
                if (allowSensor) {
                    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                } else {
                    // 后台：不监听传感器，锁在当前方向
                    lockToCurrentOrientation(activity)
                }
            }
        }
    }

    /** 底部菜单「视角」图标：竖屏 / 横屏 / 自动 */
    fun menuIconRes(mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> R.drawable.ic_menu_orient_portrait
        OrientationMode.LANDSCAPE -> R.drawable.ic_menu_orient_landscape
        OrientationMode.AUTO -> R.drawable.ic_menu_orient_auto
    }

    private fun lockToCurrentOrientation(activity: Activity): Int {
        val orient = activity.resources.configuration.orientation
        return if (orient == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

