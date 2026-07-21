package com.whj.reader.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import com.whj.reader.R
import com.whj.reader.data.AppSettings

/**
 * 应用界面颜色主题（书架 / 设置 / 工具栏等），对齐 music-player。
 * 阅读页正文底色/字色仍由 [com.whj.reader.model.ReadStyle] 独立控制。
 */
enum class AppThemeSkin(
    val key: String,
    @StyleRes val styleRes: Int,
    val labelRes: Int,
) {
    GREEN("green", R.style.Theme_WhjReader, R.string.ui_theme_green),
    BLUE("blue", R.style.Theme_WhjReader_Blue, R.string.ui_theme_blue),
    AMBER("amber", R.style.Theme_WhjReader_Amber, R.string.ui_theme_amber),
    TEAL("teal", R.style.Theme_WhjReader_Teal, R.string.ui_theme_teal),
    VIOLET("violet", R.style.Theme_WhjReader_Violet, R.string.ui_theme_violet),
    DARK("dark", R.style.Theme_WhjReader_Dark, R.string.ui_theme_dark),
    ROSE("rose", R.style.Theme_WhjReader_Rose, R.string.ui_theme_rose),
    INDIGO("indigo", R.style.Theme_WhjReader_Indigo, R.string.ui_theme_indigo),
    LIME("lime", R.style.Theme_WhjReader_Lime, R.string.ui_theme_lime),
    CORAL("coral", R.style.Theme_WhjReader_Coral, R.string.ui_theme_coral),
    PINK("pink", R.style.Theme_WhjReader_Pink, R.string.ui_theme_pink),
    CYAN("cyan", R.style.Theme_WhjReader_Cyan, R.string.ui_theme_cyan),
    FOREST("forest", R.style.Theme_WhjReader_Forest, R.string.ui_theme_forest),
    WINE("wine", R.style.Theme_WhjReader_Wine, R.string.ui_theme_wine),
    GOLD("gold", R.style.Theme_WhjReader_Gold, R.string.ui_theme_gold),
    SLATE("slate", R.style.Theme_WhjReader_Slate, R.string.ui_theme_slate),
    ;

    companion object {
        fun fromKey(key: String?): AppThemeSkin =
            entries.firstOrNull { it.key == key } ?: GREEN
    }
}

object AppTheme {
    /** 须在 [Activity.setContentView] 之前、[Activity.onCreate] 尽量靠前调用 */
    fun apply(activity: Activity) {
        val skin = AppThemeSkin.fromKey(AppSettings.uiThemeKey(activity))
        activity.setTheme(skin.styleRes)
    }

    fun resolveColor(context: Context, @AttrRes attr: Int, fallback: Int = 0): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                context.getColor(tv.resourceId)
            } else {
                tv.data
            }
        } else {
            fallback
        }
    }

    fun primary(context: Context): Int =
        resolveColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8C5A.toInt())

    fun accentSoft(context: Context): Int =
        resolveColor(context, R.attr.colorAccentSoft, 0xFFE3F0E2.toInt())

    fun toolbarAccent(context: Context): Int =
        resolveColor(context, R.attr.colorToolbarAccent, primary(context))
}
