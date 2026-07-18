package com.whj.reader.data

import android.content.Context
import com.whj.reader.model.AppLanguage
import com.whj.reader.model.EdgeSwipeAction
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.ReadStyle
import com.whj.reader.model.ReadTheme

object AppSettings {
    private const val PREF = "reader_settings"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun loadStyle(ctx: Context): ReadStyle {
        val p = prefs(ctx)
        val theme = runCatching {
            ReadTheme.valueOf(p.getString("theme", ReadTheme.DEFAULT.name)!!)
        }.getOrDefault(ReadTheme.DEFAULT)
        return ReadStyle(
            theme = theme,
            fontSizeSp = p.getFloat("fontSize", 18f),
            lineSpacingMult = p.getFloat("lineSpacing", 1.4f),
            paraSpacingDp = p.getInt("paraSpacing", 8),
            letterSpacing = p.getFloat("letterSpacing", 0f),
        )
    }

    fun saveStyle(ctx: Context, style: ReadStyle) {
        prefs(ctx).edit()
            .putString("theme", style.theme.name)
            .putFloat("fontSize", style.fontSizeSp)
            .putFloat("lineSpacing", style.lineSpacingMult)
            .putInt("paraSpacing", style.paraSpacingDp)
            .putFloat("letterSpacing", style.letterSpacing)
            .apply()
    }

    fun keepScreenOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("keepScreenOn", true)

    fun setKeepScreenOn(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("keepScreenOn", value).apply()
    }

    fun autoScroll(ctx: Context): Boolean =
        prefs(ctx).getBoolean("autoScroll", true)

    fun setAutoScroll(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("autoScroll", value).apply()
    }

    fun ttsRate(ctx: Context): Float =
        prefs(ctx).getFloat("ttsRate", 1.0f)

    fun setTtsRate(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat("ttsRate", value).apply()
    }

    fun ttsPitch(ctx: Context): Float =
        prefs(ctx).getFloat("ttsPitch", 1.0f)

    fun setTtsPitch(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat("ttsPitch", value).apply()
    }

    fun voiceName(ctx: Context): String? =
        prefs(ctx).getString("voiceName", null)

    fun setVoiceName(ctx: Context, name: String?) {
        prefs(ctx).edit().putString("voiceName", name).apply()
    }

    fun progressFor(ctx: Context, fileKey: String): Int =
        prefs(ctx).getInt("progress_$fileKey", 0)

    fun saveProgress(ctx: Context, fileKey: String, paragraphIndex: Int) {
        prefs(ctx).edit().putInt("progress_$fileKey", paragraphIndex).apply()
    }

    /** 上次阅读的书籍 key（uri / asset://…） */
    fun lastBookUri(ctx: Context): String? =
        prefs(ctx).getString("lastBookUri", null)?.takeIf { it.isNotBlank() }

    fun lastBookTitle(ctx: Context): String =
        prefs(ctx).getString("lastBookTitle", "") ?: ""

    fun setLastBook(ctx: Context, uri: String, title: String) {
        prefs(ctx).edit()
            .putString("lastBookUri", uri)
            .putString("lastBookTitle", title)
            .apply()
    }

    /**
     * 阅读页空闲退出时间（分钟）。
     * 0 表示不自动退出；默认 30。
     */
    fun idleExitMinutes(ctx: Context): Int =
        prefs(ctx).getInt("idleExitMinutes", 30).coerceIn(0, 24 * 60)

    fun setIdleExitMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt("idleExitMinutes", minutes.coerceIn(0, 24 * 60)).apply()
    }

    fun orientationMode(ctx: Context): OrientationMode =
        runCatching {
            OrientationMode.valueOf(
                prefs(ctx).getString("orientationMode", OrientationMode.AUTO.name)!!,
            )
        }.getOrDefault(OrientationMode.AUTO)

    fun setOrientationMode(ctx: Context, mode: OrientationMode) {
        prefs(ctx).edit().putString("orientationMode", mode.name).apply()
    }

    /** 左边缘滑动：默认语速 */
    fun leftEdgeAction(ctx: Context): EdgeSwipeAction =
        runCatching {
            EdgeSwipeAction.valueOf(
                prefs(ctx).getString("leftEdgeAction", EdgeSwipeAction.RATE.name)!!,
            )
        }.getOrDefault(EdgeSwipeAction.RATE)

    fun setLeftEdgeAction(ctx: Context, action: EdgeSwipeAction) {
        prefs(ctx).edit().putString("leftEdgeAction", action.name).apply()
    }

    /** 右边缘滑动：默认字号 */
    fun rightEdgeAction(ctx: Context): EdgeSwipeAction =
        runCatching {
            EdgeSwipeAction.valueOf(
                prefs(ctx).getString("rightEdgeAction", EdgeSwipeAction.FONT.name)!!,
            )
        }.getOrDefault(EdgeSwipeAction.FONT)

    fun setRightEdgeAction(ctx: Context, action: EdgeSwipeAction) {
        prefs(ctx).edit().putString("rightEdgeAction", action.name).apply()
    }

    /** 界面语言，默认中文 */
    fun appLanguage(ctx: Context): AppLanguage =
        runCatching {
            AppLanguage.valueOf(
                prefs(ctx).getString("appLanguage", AppLanguage.ZH.name)!!,
            )
        }.getOrDefault(AppLanguage.ZH)

    fun setAppLanguage(ctx: Context, language: AppLanguage) {
        prefs(ctx).edit().putString("appLanguage", language.name).apply()
    }
}
