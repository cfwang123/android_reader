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
import com.whj.reader.util.ReaderFonts

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
        // 旧版只有 theme：迁移为纯色底 + 对应字色
        val hasTextColor = p.contains("textColor")
        val hasSolidBg = p.contains("customBgColor")
        val migratedBg = if (hasSolidBg) {
            p.getInt("customBgColor", 0xFFF7F4ED.toInt())
        } else {
            com.whj.reader.ui.ParagraphAdapter.backgroundColor(theme)
        }
        val migratedText = if (hasTextColor) {
            p.getInt("textColor", 0xFF2C2C2C.toInt())
        } else {
            com.whj.reader.ui.ParagraphAdapter.themeColors(theme, migratedBg).first
        }
        return ReadStyle(
            theme = theme,
            fontSizeSp = p.getFloat("fontSize", 18f),
            lineSpacingMult = p.getFloat("lineSpacing", 1.4f),
            paraSpacingDp = p.getInt("paraSpacing", 8),
            letterSpacing = p.getFloat("letterSpacing", 0f),
            // 旧版内置方正 id 归一为系统默认
            fontFamily = ReaderFonts.normalizeId(
                p.getString("fontFamily", ReaderFonts.ID_DEFAULT) ?: ReaderFonts.ID_DEFAULT,
            ),
            customBgColor = migratedBg,
            bgTextureId = p.getString("bgTextureId", "") ?: "",
            textColor = migratedText,
            customBgImageFile = p.getString("customBgImageFile", "") ?: "",
        )
    }

    fun saveStyle(ctx: Context, style: ReadStyle) {
        prefs(ctx).edit()
            .putString("theme", style.theme.name)
            .putFloat("fontSize", style.fontSizeSp)
            .putFloat("lineSpacing", style.lineSpacingMult)
            .putInt("paraSpacing", style.paraSpacingDp)
            .putFloat("letterSpacing", style.letterSpacing)
            .putString("fontFamily", ReaderFonts.normalizeId(style.fontFamily))
            .putInt("customBgColor", style.customBgColor)
            .putString("bgTextureId", style.bgTextureId)
            .putInt("textColor", style.textColor)
            .putString("customBgImageFile", style.customBgImageFile)
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

    fun clearTxtProgress(ctx: Context, fileKey: String) {
        if (fileKey.isBlank()) return
        prefs(ctx).edit().remove("progress_$fileKey").apply()
        if (lastBookUri(ctx) == fileKey) {
            prefs(ctx).edit()
                .remove("lastBookUri")
                .remove("lastBookTitle")
                .remove("lastBookAt")
                .apply()
        }
        clearMangaViewState(ctx, fileKey)
    }

    /**
     * MOBI 漫画视图：图片索引 + 缩放/平移 + 连续图竖直滚动。
     * 与正文段落进度分开存，避免文字/漫画模式互相覆盖索引语义。
     */
    data class MangaViewState(
        val index: Int = 0,
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
        /**
         * 连续图：首可见项顶边相对 RecyclerView 顶的偏移（[LinearLayoutManager.scrollToPositionWithOffset]）。
         */
        val itemOffset: Int = 0,
        /** 连续图：整表竖直 scroll 像素（辅助恢复） */
        val scrollY: Int = 0,
    )

    fun saveMangaViewState(ctx: Context, fileKey: String, state: MangaViewState) {
        if (fileKey.isBlank()) return
        val k = mangaStateKey(fileKey)
        val ok = prefs(ctx).edit()
            .putInt("manga_idx_$k", state.index.coerceAtLeast(0))
            .putFloat("manga_zoom_$k", state.zoom.coerceIn(0.25f, 5f))
            .putFloat("manga_panX_$k", state.panX)
            .putFloat("manga_panY_$k", state.panY)
            .putInt("manga_itemOff_$k", state.itemOffset)
            .putInt("manga_scrollY_$k", state.scrollY.coerceAtLeast(0))
            // 调试：便于 adb 对照同一本书
            .putString("manga_uri_$k", fileKey.take(240))
            .commit()
        android.util.Log.i(
            "MangaZoom",
            "SAVE ok=$ok key=$k idx=${state.index} zoom=${state.zoom} " +
                "pan=(${state.panX},${state.panY}) itemOff=${state.itemOffset} " +
                "scrollY=${state.scrollY} uri=${fileKey.take(120)}",
        )
    }

    fun loadMangaViewState(ctx: Context, fileKey: String): MangaViewState {
        if (fileKey.isBlank()) {
            android.util.Log.w("MangaZoom", "LOAD skip blank fileKey")
            return MangaViewState()
        }
        val p = prefs(ctx)
        val keys = listOf(mangaStateKey(fileKey), fileKey.hashCode().toString()).distinct()
        for (k in keys) {
            if (!p.contains("manga_zoom_$k") && !p.contains("manga_idx_$k") &&
                !p.contains("manga_scrollY_$k")
            ) {
                continue
            }
            val state = MangaViewState(
                index = p.getInt("manga_idx_$k", 0).coerceAtLeast(0),
                zoom = p.getFloat("manga_zoom_$k", 1f).coerceIn(0.25f, 5f),
                panX = p.getFloat("manga_panX_$k", 0f),
                panY = p.getFloat("manga_panY_$k", 0f),
                itemOffset = p.getInt("manga_itemOff_$k", 0),
                scrollY = p.getInt("manga_scrollY_$k", 0).coerceAtLeast(0),
            )
            android.util.Log.i(
                "MangaZoom",
                "LOAD hit key=$k idx=${state.index} zoom=${state.zoom} " +
                    "pan=(${state.panX},${state.panY}) itemOff=${state.itemOffset} " +
                    "scrollY=${state.scrollY} savedUri=${p.getString("manga_uri_$k", "")} " +
                    "reqUri=${fileKey.take(120)}",
            )
            // 命中旧 hash 键时迁移到 MD5 键
            if (k == fileKey.hashCode().toString() && k != mangaStateKey(fileKey)) {
                saveMangaViewState(ctx, fileKey, state)
            }
            return state
        }
        android.util.Log.i(
            "MangaZoom",
            "LOAD miss keys=$keys uri=${fileKey.take(120)}",
        )
        return MangaViewState()
    }

    fun clearMangaViewState(ctx: Context, fileKey: String) {
        if (fileKey.isBlank()) return
        val k = mangaStateKey(fileKey)
        prefs(ctx).edit()
            .remove("manga_idx_$k")
            .remove("manga_zoom_$k")
            .remove("manga_panX_$k")
            .remove("manga_panY_$k")
            .remove("manga_itemOff_$k")
            .remove("manga_scrollY_$k")
            .remove("manga_uri_$k")
            .apply()
        android.util.Log.i("MangaZoom", "CLEAR key=$k uri=${fileKey.take(120)}")
    }

    /** 稳定短键：避免仅用 hashCode 难排查；仍兼容旧 hash 键 */
    private fun mangaStateKey(fileKey: String): String {
        // 优先用完整 uri 的稳定短摘要
        val digest = runCatching {
            val md = java.security.MessageDigest.getInstance("MD5")
            md.digest(fileKey.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
        }.getOrNull()
        return digest ?: fileKey.hashCode().toString()
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

    fun orientationMode(ctx: Context): OrientationMode {
        val p = prefs(ctx)
        // 去掉自动旋转：旧 AUTO 一次性迁到竖屏
        if (!p.getBoolean("orientMigratedNoAuto", false)) {
            val raw = p.getString("orientationMode", OrientationMode.PORTRAIT.name)
            val fixed = when (raw) {
                OrientationMode.LANDSCAPE.name -> OrientationMode.LANDSCAPE
                else -> OrientationMode.PORTRAIT
            }
            p.edit()
                .putBoolean("orientMigratedNoAuto", true)
                .putBoolean("orientMigratedAuto", true)
                .putString("orientationMode", fixed.name)
                .apply()
            return fixed
        }
        val mode = runCatching {
            OrientationMode.valueOf(
                p.getString("orientationMode", OrientationMode.PORTRAIT.name)!!,
            )
        }.getOrDefault(OrientationMode.PORTRAIT)
        // 运行时若仍读到 AUTO，落到竖屏
        return if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
    }

    fun setOrientationMode(ctx: Context, mode: OrientationMode) {
        val fixed = if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
        prefs(ctx).edit()
            .putString("orientationMode", fixed.name)
            .putBoolean("orientMigratedAuto", true)
            .putBoolean("orientMigratedNoAuto", true)
            .apply()
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

    /** 音量键翻页，默认开启 */
    fun volumeKeyPageTurn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("volumeKeyPageTurn", true)

    fun setVolumeKeyPageTurn(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("volumeKeyPageTurn", enabled).apply()
    }

    /**
     * 界面颜色主题 key（书架/设置等），默认 green（原品牌翠绿）。
     * 与阅读页 [ReadStyle.theme] 正文底色无关。
     */
    fun uiThemeKey(ctx: Context): String =
        prefs(ctx).getString("uiThemeKey", "green") ?: "green"

    fun setUiThemeKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString("uiThemeKey", key).apply()
    }

    /**
     * MOBI 浏览模式：正文 / 单图漫画 / 连续图。
     * 兼容旧键 [mobiMangaMode]：true → MANGA。
     */
    enum class MobiViewMode {
        /** 正文段落 */
        TEXT,
        /** 一次一张图，侧点/滑动翻页（横竖屏均可） */
        MANGA,
        /** 连续上下滚图（横竖屏均可） */
        CONTINUOUS,
    }

    fun mobiViewMode(ctx: Context): MobiViewMode {
        val p = prefs(ctx)
        val raw = p.getString("mobiViewMode", null)
        if (!raw.isNullOrBlank()) {
            return runCatching { MobiViewMode.valueOf(raw) }.getOrElse {
                // 旧拼写兼容
                when (raw.uppercase()) {
                    "SINGLE", "MANGA_SINGLE" -> MobiViewMode.MANGA
                    "CONT", "CONTINUOUS_SCROLL" -> MobiViewMode.CONTINUOUS
                    else -> MobiViewMode.TEXT
                }
            }
        }
        // 迁移旧布尔：漫画开 → 单图漫画
        return if (p.getBoolean("mobiMangaMode", false)) MobiViewMode.MANGA else MobiViewMode.TEXT
    }

    fun setMobiViewMode(ctx: Context, mode: MobiViewMode) {
        prefs(ctx).edit()
            .putString("mobiViewMode", mode.name)
            // 同步旧键，避免其它处只读布尔时不一致
            .putBoolean("mobiMangaMode", mode != MobiViewMode.TEXT)
            .apply()
    }

    /** @deprecated 用 [mobiViewMode]；保留给旧调用 */
    fun mobiMangaMode(ctx: Context): Boolean =
        mobiViewMode(ctx) != MobiViewMode.TEXT

    fun setMobiMangaMode(ctx: Context, enabled: Boolean) {
        setMobiViewMode(ctx, if (enabled) MobiViewMode.MANGA else MobiViewMode.TEXT)
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
        // commit：退出阅读立刻能 restore 到历史/文件夹
        prefs(ctx).edit()
            .putString("shelfFocusUri", uri)
            .putString("shelfFocusFolderId", folderId ?: "")
            .putString("shelfFocusLinkedPath", path)
            .putBoolean("shelfFocusNeedRestore", true)
            .commit()
    }

    /** 打开阅读时记下「返回要 restore」；onResume 消费一次 */
    fun consumeShelfFocusNeedRestore(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean("shelfFocusNeedRestore", false)) return false
        p.edit().putBoolean("shelfFocusNeedRestore", false).commit()
        return true
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
        if (fileKey.isBlank()) return
        val k = fileKey.hashCode().toString()
        // commit：缩放/页码写入文件级记录，退出后立刻可恢复
        pdfPrefs(ctx).edit()
            .putInt("pdf_progress_$fileKey", state.page.coerceAtLeast(0))
            .putFloat("pdf_zoom_$k", state.zoom.coerceIn(0.25f, 3.5f))
            .putFloat("pdf_panX_$k", state.panX)
            .putFloat("pdf_panY_$k", state.panY)
            .putInt("pdf_scrollY_$k", state.scrollY.coerceAtLeast(0))
            .commit()
    }

    fun loadPdfViewState(ctx: Context, fileKey: String): PdfViewState {
        val k = fileKey.hashCode().toString()
        val p = pdfPrefs(ctx)
        return PdfViewState(
            page = p.getInt("pdf_progress_$fileKey", 0).coerceAtLeast(0),
            zoom = p.getFloat("pdf_zoom_$k", 1f).coerceIn(0.25f, 3.5f),
            panX = p.getFloat("pdf_panX_$k", 0f),
            panY = p.getFloat("pdf_panY_$k", 0f),
            scrollY = p.getInt("pdf_scrollY_$k", 0).coerceAtLeast(0),
        )
    }

    /** 清除单本 PDF 的进度/缩放/切边等视图状态 */
    fun clearPdfViewState(ctx: Context, fileKey: String) {
        if (fileKey.isBlank()) return
        val k = fileKey.hashCode().toString()
        pdfPrefs(ctx).edit()
            .remove("pdf_progress_$fileKey")
            .remove("pdf_zoom_$k")
            .remove("pdf_panX_$k")
            .remove("pdf_panY_$k")
            .remove("pdf_scrollY_$k")
            .remove("pdf_cropL_$k")
            .remove("pdf_cropT_$k")
            .remove("pdf_cropR_$k")
            .remove("pdf_cropB_$k")
            .remove("pdf_cropMirror_$k")
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
    fun pdfOrientationMode(ctx: Context): OrientationMode {
        val p = pdfPrefs(ctx)
        // 去掉自动旋转：旧 AUTO 一次性迁到竖屏
        if (!p.getBoolean("pdfOrientMigratedNoAuto", false)) {
            val raw = p.getString("pdfOrientation", OrientationMode.PORTRAIT.name)
            val fixed = when (raw) {
                OrientationMode.LANDSCAPE.name -> OrientationMode.LANDSCAPE
                else -> OrientationMode.PORTRAIT
            }
            p.edit()
                .putBoolean("pdfOrientMigratedNoAuto", true)
                .putBoolean("pdfOrientMigratedAuto", true)
                .putString("pdfOrientation", fixed.name)
                .apply()
            return fixed
        }
        val mode = runCatching {
            OrientationMode.valueOf(
                p.getString("pdfOrientation", OrientationMode.PORTRAIT.name)!!,
            )
        }.getOrDefault(OrientationMode.PORTRAIT)
        return if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
    }

    fun setPdfOrientationMode(ctx: Context, mode: OrientationMode) {
        val fixed = if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
        pdfPrefs(ctx).edit()
            .putString("pdfOrientation", fixed.name)
            .putBoolean("pdfOrientMigratedAuto", true)
            .putBoolean("pdfOrientMigratedNoAuto", true)
            .apply()
    }

    /**
     * PDF 四边切边比例 0~0.30（左、上、右、下）。
     * **按文件独立保存**，互不影响；未设置过则为 0。
     */
    fun pdfCropMargins(ctx: Context, fileKey: String): FloatArray {
        if (fileKey.isBlank()) return floatArrayOf(0f, 0f, 0f, 0f)
        val k = fileKey.hashCode().toString()
        val p = pdfPrefs(ctx)
        return floatArrayOf(
            p.getFloat("pdf_cropL_$k", 0f).coerceIn(0f, 0.30f),
            p.getFloat("pdf_cropT_$k", 0f).coerceIn(0f, 0.30f),
            p.getFloat("pdf_cropR_$k", 0f).coerceIn(0f, 0.30f),
            p.getFloat("pdf_cropB_$k", 0f).coerceIn(0f, 0.30f),
        )
    }

    fun setPdfCropMargins(
        ctx: Context,
        fileKey: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        if (fileKey.isBlank()) return
        fun c(v: Float) = v.coerceIn(0f, 0.30f)
        val k = fileKey.hashCode().toString()
        pdfPrefs(ctx).edit()
            .putFloat("pdf_cropL_$k", c(left))
            .putFloat("pdf_cropT_$k", c(top))
            .putFloat("pdf_cropR_$k", c(right))
            .putFloat("pdf_cropB_$k", c(bottom))
            .apply()
    }

    /** 奇偶页左右边距镜像（扫描书常用）；按文件独立 */
    fun pdfCropMirrorOddEven(ctx: Context, fileKey: String): Boolean {
        if (fileKey.isBlank()) return false
        val k = fileKey.hashCode().toString()
        return pdfPrefs(ctx).getBoolean("pdf_cropMirror_$k", false)
    }

    fun setPdfCropMirrorOddEven(ctx: Context, fileKey: String, enabled: Boolean) {
        if (fileKey.isBlank()) return
        val k = fileKey.hashCode().toString()
        pdfPrefs(ctx).edit().putBoolean("pdf_cropMirror_$k", enabled).apply()
    }

    /**
     * 按页号取实际裁边：开启奇偶对称时，偶数页（index 奇数）左右互换。
     */
    fun pdfCropMarginsForPage(ctx: Context, fileKey: String, pageIndex: Int): FloatArray {
        val m = pdfCropMargins(ctx, fileKey)
        if (!pdfCropMirrorOddEven(ctx, fileKey) || pageIndex % 2 == 0) return m
        return floatArrayOf(m[2], m[1], m[0], m[3])
    }

    /** URI 变更时迁移切边（新 URI 尚无记录才写入） */
    fun migratePdfCrop(ctx: Context, oldUri: String, newUri: String) {
        if (oldUri.isBlank() || newUri.isBlank() || oldUri == newUri) return
        val oldK = oldUri.hashCode().toString()
        val newK = newUri.hashCode().toString()
        val p = pdfPrefs(ctx)
        // 新 key 已有任一边记录则不覆盖
        if (p.contains("pdf_cropL_$newK") || p.contains("pdf_cropMirror_$newK")) return
        val hasOld = p.contains("pdf_cropL_$oldK") || p.contains("pdf_cropMirror_$oldK")
        if (!hasOld) return
        val ed = p.edit()
        if (p.contains("pdf_cropL_$oldK")) {
            ed.putFloat("pdf_cropL_$newK", p.getFloat("pdf_cropL_$oldK", 0f).coerceIn(0f, 0.30f))
            ed.putFloat("pdf_cropT_$newK", p.getFloat("pdf_cropT_$oldK", 0f).coerceIn(0f, 0.30f))
            ed.putFloat("pdf_cropR_$newK", p.getFloat("pdf_cropR_$oldK", 0f).coerceIn(0f, 0.30f))
            ed.putFloat("pdf_cropB_$newK", p.getFloat("pdf_cropB_$oldK", 0f).coerceIn(0f, 0.30f))
        }
        if (p.contains("pdf_cropMirror_$oldK")) {
            ed.putBoolean("pdf_cropMirror_$newK", p.getBoolean("pdf_cropMirror_$oldK", false))
        }
        ed.apply()
    }

}
