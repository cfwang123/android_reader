package com.whj.reader.data

import android.content.Context
import com.whj.reader.model.AppLanguage
import com.whj.reader.model.EdgeSwipeAction
import com.whj.reader.model.KeepScreenMode
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.PdfPageMode
import com.whj.reader.model.ReadStyle
import com.whj.reader.model.ReadTheme
import com.whj.reader.model.ShelfSort

object AppSettings {
    /** TXT 阅读设置 */
    private const val PREF = "reader_settings"
    /** PDF 阅读设置（与 TXT 隔离） */
    private const val PREF_PDF = "reader_pdf_settings"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun pdfPrefs(ctx: Context) =
        ctx.getSharedPreferences(PREF_PDF, Context.MODE_PRIVATE)

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
            fontFamily = p.getString("fontFamily", "default") ?: "default",
            customBgColor = p.getInt("customBgColor", 0xFFFFFFFF.toInt()),
        )
    }

    fun saveStyle(ctx: Context, style: ReadStyle) {
        prefs(ctx).edit()
            .putString("theme", style.theme.name)
            .putFloat("fontSize", style.fontSizeSp)
            .putFloat("lineSpacing", style.lineSpacingMult)
            .putInt("paraSpacing", style.paraSpacingDp)
            .putFloat("letterSpacing", style.letterSpacing)
            .putString("fontFamily", style.fontFamily)
            .putInt("customBgColor", style.customBgColor)
            .apply()
    }

    /**
     * 屏幕常亮模式。默认 [KeepScreenMode.OFF]（省电）。
     * 兼容旧键 keepScreenOn：仅在尚未写入 keepScreenMode 时迁移一次。
     */
    fun keepScreenMode(ctx: Context): KeepScreenMode {
        val p = prefs(ctx)
        val raw = p.getString("keepScreenMode", null)
        if (raw != null) {
            return runCatching { KeepScreenMode.valueOf(raw) }.getOrDefault(KeepScreenMode.OFF)
        }
        // 旧版布尔：true→始终常亮，false→关闭；新装无键则默认关闭
        return if (p.contains("keepScreenOn") && p.getBoolean("keepScreenOn", false)) {
            KeepScreenMode.ALWAYS
        } else {
            KeepScreenMode.OFF
        }
    }

    fun setKeepScreenMode(ctx: Context, mode: KeepScreenMode) {
        prefs(ctx).edit()
            .putString("keepScreenMode", mode.name)
            // 同步旧键，避免其它逻辑读到过期值
            .putBoolean("keepScreenOn", mode == KeepScreenMode.ALWAYS)
            .apply()
    }

    /** @deprecated 使用 [keepScreenMode] */
    fun keepScreenOn(ctx: Context): Boolean =
        keepScreenMode(ctx) == KeepScreenMode.ALWAYS

    /** @deprecated 使用 [setKeepScreenMode] */
    fun setKeepScreenOn(ctx: Context, value: Boolean) {
        setKeepScreenMode(ctx, if (value) KeepScreenMode.ALWAYS else KeepScreenMode.OFF)
    }

    /**
     * 空闲熄屏时间（分钟）：[KeepScreenMode.ALWAYS] 下无操作超过该时间后取消常亮，允许系统锁屏。
     * 0 = 禁用（不因空闲取消常亮）；默认 5 分钟。
     */
    fun idleScreenOffMinutes(ctx: Context): Int =
        prefs(ctx).getInt("idleScreenOffMinutes", 5).coerceIn(0, 120)

    fun setIdleScreenOffMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt("idleScreenOffMinutes", minutes.coerceIn(0, 120)).apply()
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

    /** 合成导出 AAC 码率（kbps），默认 64 */
    fun ttsExportBitrateKbps(ctx: Context): Int =
        prefs(ctx).getInt("ttsExportBitrateKbps", 64).coerceIn(16, 320)

    fun setTtsExportBitrateKbps(ctx: Context, kbps: Int) {
        prefs(ctx).edit().putInt("ttsExportBitrateKbps", kbps.coerceIn(16, 320)).apply()
    }

    fun voiceName(ctx: Context): String? =
        prefs(ctx).getString("voiceName", null)?.takeIf { it.isNotBlank() }

    fun setVoiceName(ctx: Context, name: String?) {
        // commit：引擎/发音人偏好需跨进程杀死可靠落盘
        val ed = prefs(ctx).edit()
        if (name.isNullOrBlank()) ed.remove("voiceName") else ed.putString("voiceName", name)
        ed.commit()
    }

    /** 用户选择的 TTS 引擎包名；null/空 = 自动选择 */
    fun ttsEnginePackage(ctx: Context): String? =
        prefs(ctx).getString("ttsEnginePackage", null)?.takeIf { it.isNotBlank() }

    fun setTtsEnginePackage(ctx: Context, packageName: String?) {
        val ed = prefs(ctx).edit()
        if (packageName.isNullOrBlank()) {
            ed.remove("ttsEnginePackage")
        } else {
            ed.putString("ttsEnginePackage", packageName)
        }
        ed.commit()
    }

    /**
     * 上次成功绑定的引擎包名（含「自动」模式下实际生效的引擎）。
     * 启动时若未指定偏好，优先尝试此引擎。
     */
    fun ttsLastEnginePackage(ctx: Context): String? =
        prefs(ctx).getString("ttsLastEnginePackage", null)?.takeIf { it.isNotBlank() }

    fun setTtsLastEnginePackage(ctx: Context, packageName: String?) {
        val ed = prefs(ctx).edit()
        if (packageName.isNullOrBlank()) {
            ed.remove("ttsLastEnginePackage")
        } else {
            ed.putString("ttsLastEnginePackage", packageName)
        }
        ed.commit()
    }

    /** 上次选的语言 key，如 zh_CN */
    fun ttsLanguageKey(ctx: Context): String? =
        prefs(ctx).getString("ttsLanguageKey", null)?.takeIf { it.isNotBlank() }

    fun setTtsLanguageKey(ctx: Context, key: String?) {
        val ed = prefs(ctx).edit()
        if (key.isNullOrBlank()) ed.remove("ttsLanguageKey") else ed.putString("ttsLanguageKey", key)
        ed.commit()
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
            .putLong("lastBookAt", System.currentTimeMillis())
            .apply()
    }

    fun lastBookAt(ctx: Context): Long =
        prefs(ctx).getLong("lastBookAt", 0L)

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
                prefs(ctx).getString("orientationMode", OrientationMode.PORTRAIT.name)!!,
            )
        }.getOrDefault(OrientationMode.PORTRAIT)

    fun setOrientationMode(ctx: Context, mode: OrientationMode) {
        prefs(ctx).edit().putString("orientationMode", mode.name).apply()
    }

    /** 左边缘滑动：默认关闭（无） */
    fun leftEdgeAction(ctx: Context): EdgeSwipeAction =
        runCatching {
            EdgeSwipeAction.valueOf(
                prefs(ctx).getString("leftEdgeAction", EdgeSwipeAction.NONE.name)!!,
            )
        }.getOrDefault(EdgeSwipeAction.NONE)

    fun setLeftEdgeAction(ctx: Context, action: EdgeSwipeAction) {
        prefs(ctx).edit().putString("leftEdgeAction", action.name).apply()
    }

    /** 右边缘滑动：默认字号（下滑加大、上滑减小） */
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

    /** 书架排序，默认按上次阅读时间 */
    fun shelfSort(ctx: Context): ShelfSort =
        runCatching {
            ShelfSort.valueOf(
                prefs(ctx).getString("shelfSort", ShelfSort.LAST_OPENED.name)!!,
            )
        }.getOrDefault(ShelfSort.LAST_OPENED)

    fun setShelfSort(ctx: Context, sort: ShelfSort) {
        prefs(ctx).edit().putString("shelfSort", sort.name).apply()
    }

    // ─── 书架定位：退出阅读后回到书所在位置 ─────────

    data class ShelfFocus(
        val uri: String,
        val folderId: String?,
        /** 绑定文件夹内路径：documentId\tname，多段用 \n */
        val linkedPathRaw: String,
    )

    fun saveShelfFocus(
        ctx: Context,
        uri: String,
        folderId: String?,
        linkedDocIds: List<String>,
        linkedNames: List<String>,
    ) {
        val path = linkedDocIds.zip(linkedNames)
            .joinToString("\n") { (id, name) -> "$id\t$name" }
        prefs(ctx).edit()
            .putString("shelfFocusUri", uri)
            .putString("shelfFocusFolderId", folderId)
            .putString("shelfFocusLinkedPath", path)
            .apply()
    }

    fun lastShelfFocus(ctx: Context): ShelfFocus? {
        val uri = prefs(ctx).getString("shelfFocusUri", null)?.takeIf { it.isNotBlank() }
            ?: return null
        val folderId = prefs(ctx).getString("shelfFocusFolderId", null)
            ?.takeIf { it.isNotBlank() && it != "null" }
        val path = prefs(ctx).getString("shelfFocusLinkedPath", "") ?: ""
        return ShelfFocus(uri = uri, folderId = folderId, linkedPathRaw = path)
    }

    fun parseLinkedPath(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val t = line.indexOf('\t')
            if (t <= 0) null
            else line.substring(0, t) to line.substring(t + 1)
        }.toList()
    }

    // ─── PDF 专用（不与 TXT 进度/上次书籍互相覆盖） ─────────

    fun pdfProgressFor(ctx: Context, fileKey: String): Int =
        pdfPrefs(ctx).getInt("pdf_progress_$fileKey", 0)

    fun savePdfProgress(ctx: Context, fileKey: String, pageIndex: Int) {
        pdfPrefs(ctx).edit().putInt("pdf_progress_$fileKey", pageIndex.coerceAtLeast(0)).apply()
    }

    /** 单本 PDF 的视图状态：页码 + 缩放 + 平移 + 连续模式滚动 */
    data class PdfViewState(
        val page: Int = 0,
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val scrollY: Int = 0,
    )

    fun savePdfViewState(ctx: Context, fileKey: String, state: PdfViewState) {
        val k = fileKey.hashCode().toString()
        pdfPrefs(ctx).edit()
            .putInt("pdf_progress_$fileKey", state.page.coerceAtLeast(0))
            .putFloat("pdf_zoom_$k", state.zoom.coerceIn(1f, 3.5f))
            .putFloat("pdf_panX_$k", state.panX)
            .putFloat("pdf_panY_$k", state.panY)
            .putInt("pdf_scrollY_$k", state.scrollY.coerceAtLeast(0))
            .apply()
    }

    fun loadPdfViewState(ctx: Context, fileKey: String): PdfViewState {
        val k = fileKey.hashCode().toString()
        val p = pdfPrefs(ctx)
        return PdfViewState(
            page = p.getInt("pdf_progress_$fileKey", 0).coerceAtLeast(0),
            zoom = p.getFloat("pdf_zoom_$k", 1f).coerceIn(1f, 3.5f),
            panX = p.getFloat("pdf_panX_$k", 0f),
            panY = p.getFloat("pdf_panY_$k", 0f),
            scrollY = p.getInt("pdf_scrollY_$k", 0).coerceAtLeast(0),
        )
    }

    /** 清除单本 PDF 的进度/缩放等视图状态 */
    fun clearPdfViewState(ctx: Context, fileKey: String) {
        if (fileKey.isBlank()) return
        val k = fileKey.hashCode().toString()
        pdfPrefs(ctx).edit()
            .remove("pdf_progress_$fileKey")
            .remove("pdf_zoom_$k")
            .remove("pdf_panX_$k")
            .remove("pdf_panY_$k")
            .remove("pdf_scrollY_$k")
            .apply()
        if (lastPdfUri(ctx) == fileKey) {
            pdfPrefs(ctx).edit()
                .remove("lastPdfUri")
                .remove("lastPdfTitle")
                .remove("lastPdfAt")
                .apply()
        }
    }

    fun lastPdfUri(ctx: Context): String? =
        pdfPrefs(ctx).getString("lastPdfUri", null)?.takeIf { it.isNotBlank() }

    fun lastPdfTitle(ctx: Context): String =
        pdfPrefs(ctx).getString("lastPdfTitle", "") ?: ""

    fun setLastPdfBook(ctx: Context, uri: String, title: String) {
        pdfPrefs(ctx).edit()
            .putString("lastPdfUri", uri)
            .putString("lastPdfTitle", title)
            .putLong("lastPdfAt", System.currentTimeMillis())
            .apply()
    }

    fun lastPdfAt(ctx: Context): Long =
        pdfPrefs(ctx).getLong("lastPdfAt", 0L)

    /** PDF 页面模式，默认连续滚动 */
    fun pdfPageMode(ctx: Context): PdfPageMode =
        runCatching {
            PdfPageMode.valueOf(
                pdfPrefs(ctx).getString("pdfPageMode", PdfPageMode.CONTINUOUS.name)!!,
            )
        }.getOrDefault(PdfPageMode.CONTINUOUS)

    fun setPdfPageMode(ctx: Context, mode: PdfPageMode) {
        pdfPrefs(ctx).edit().putString("pdfPageMode", mode.name).apply()
    }

    fun pdfNight(ctx: Context): Boolean =
        pdfPrefs(ctx).getBoolean("pdfNight", false)

    fun setPdfNight(ctx: Context, night: Boolean) {
        pdfPrefs(ctx).edit().putBoolean("pdfNight", night).apply()
    }

    /** PDF 视角（与 TXT 的 orientationMode 分离） */
    fun pdfOrientationMode(ctx: Context): OrientationMode =
        runCatching {
            OrientationMode.valueOf(
                pdfPrefs(ctx).getString("pdfOrientation", OrientationMode.PORTRAIT.name)!!,
            )
        }.getOrDefault(OrientationMode.PORTRAIT)

    fun setPdfOrientationMode(ctx: Context, mode: OrientationMode) {
        pdfPrefs(ctx).edit().putString("pdfOrientation", mode.name).apply()
    }

    /**
     * PDF 四边切边比例 0~0.30（左、上、右、下）。
     * 兼容旧版单值 pdfCrop：若无新键则四边同值。
     */
    fun pdfCropMargins(ctx: Context): FloatArray {
        val p = pdfPrefs(ctx)
        val legacy = p.getFloat("pdfCrop", 0f).coerceIn(0f, 0.30f)
        return floatArrayOf(
            p.getFloat("pdfCropL", legacy).coerceIn(0f, 0.30f),
            p.getFloat("pdfCropT", legacy).coerceIn(0f, 0.30f),
            p.getFloat("pdfCropR", legacy).coerceIn(0f, 0.30f),
            p.getFloat("pdfCropB", legacy).coerceIn(0f, 0.30f),
        )
    }

    fun setPdfCropMargins(ctx: Context, left: Float, top: Float, right: Float, bottom: Float) {
        fun c(v: Float) = v.coerceIn(0f, 0.30f)
        pdfPrefs(ctx).edit()
            .putFloat("pdfCropL", c(left))
            .putFloat("pdfCropT", c(top))
            .putFloat("pdfCropR", c(right))
            .putFloat("pdfCropB", c(bottom))
            .putFloat("pdfCrop", c(maxOf(left, top, right, bottom)))
            .apply()
    }

    /** 奇偶页左右边距镜像（扫描书常用） */
    fun pdfCropMirrorOddEven(ctx: Context): Boolean =
        pdfPrefs(ctx).getBoolean("pdfCropMirror", false)

    fun setPdfCropMirrorOddEven(ctx: Context, enabled: Boolean) {
        pdfPrefs(ctx).edit().putBoolean("pdfCropMirror", enabled).apply()
    }

    /**
     * 按页号取实际裁边：开启奇偶对称时，偶数页（index 奇数）左右互换。
     */
    fun pdfCropMarginsForPage(ctx: Context, pageIndex: Int): FloatArray {
        val m = pdfCropMargins(ctx)
        if (!pdfCropMirrorOddEven(ctx) || pageIndex % 2 == 0) return m
        return floatArrayOf(m[2], m[1], m[0], m[3])
    }

    @Deprecated("使用 pdfCropMargins")
    fun pdfCropFraction(ctx: Context): Float {
        val m = pdfCropMargins(ctx)
        return maxOf(m[0], m[1], m[2], m[3])
    }

    @Deprecated("使用 setPdfCropMargins")
    fun setPdfCropFraction(ctx: Context, fraction: Float) {
        val f = fraction.coerceIn(0f, 0.30f)
        setPdfCropMargins(ctx, f, f, f, f)
    }
}
