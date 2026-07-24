package com.whj.reader.util

import android.util.Log

/**
 * 按模块开关的调试日志（默认全关，避免 logcat 刷屏）。
 *
 * ## 如何开关
 * 只改本文件底部的 [ENABLED_MODULES]：
 * - `emptySet()` / 不写任何项 → 全关
 * - `setOf(Module.ALL)` → 全开
 * - `setOf(Module.PDF)` → 整个 PDF 族（含 underPdf 子模块）
 * - `setOf(Module.PDF_PAGE_TURN, Module.MANGA_ZOOM)` → 指定模块
 *
 * 改完重新编译运行即可。查看 logcat：
 * ```
 * adb logcat -s PdfPageTurn:I PdfZoom:I MangaZoom:I
 * ```
 */
object ReaderLog {

    enum class Module(
        /** 配置/文档用短名 */
        val id: String,
        /** logcat tag */
        val tag: String,
        /** 为 true 时，开启 [PDF] 会连带开启本模块 */
        val underPdf: Boolean = false,
    ) {
        PDF("pdf", "PdfReading"),
        PDF_PAGE_TURN("pdf_page_turn", "PdfPageTurn", underPdf = true),
        PDF_ZOOM("pdf_zoom", "PdfZoom", underPdf = true),
        PDF_ORIENT("pdf_orient", "PdfOrient", underPdf = true),
        PDF_OPEN("pdf_open", "PdfOpen", underPdf = true),
        PDF_CHROME("pdf_chrome", "PdfChrome", underPdf = true),
        PDF_OCR("pdf_ocr", "PdfOcrDbg", underPdf = true),
        /** PDF 文字选区 / 跨页拖选 */
        PDF_SELECT("pdf_select", "PdfSelect", underPdf = true),
        MANGA_ZOOM("manga_zoom", "MangaZoom"),
        MOBI("mobi", "MobiLoader"),
        EPUB("epub", "EpubLoader"),
        TTS("tts", "WhjTts"),
        TTS_SVC("tts", "WhjTtsSvc"),
        TTS_EXPORT("tts", "TtsExport"),
        TTS_SYNTH("tts", "TtsSynth"),
        OCR("ocr", "TfliteOcr"),
        OCR_XIAOMI("ocr", "XiaomiOcr"),
        OUTLINE("outline", "PdfOutline"),
        ORIENT("orient", "OrientHelper"),
        MISC("misc", "Reader"),
        ALL("all", "*"),
    }

    /**
     * ★ 模块日志开关（全局唯一配置处）
     *
     * 示例：
     * ```
     * private val ENABLED_MODULES: Set<Module> = setOf(
     *     Module.PDF_PAGE_TURN,
     *     Module.PDF_ZOOM,
     *     Module.MANGA_ZOOM,
     * )
     * // 或 Module.PDF / Module.ALL
     * ```
     */
    private val ENABLED_MODULES: Set<Module> = emptySet()

    private val tagToModule: Map<String, Module> = buildMap {
        Module.entries.forEach { m ->
            if (m != Module.ALL) put(m.tag, m)
        }
        put("PdfReading", Module.PDF)
        put("PdfOutlineCache", Module.OUTLINE)
        put("PdfLinkIndex", Module.PDF)
        put("PdfTextExtractor", Module.PDF)
        put("PdfOcrCache", Module.PDF_OCR)
        put("OcrActivity", Module.OCR)
        put("OcrTest", Module.OCR)
        put("Mp3Encoder", Module.TTS)
        put("DataBackup", Module.MISC)
        put("BookLocalDataCleaner", Module.MISC)
        put("LinkedTreeCache", Module.MISC)
    }

    fun moduleForTag(tag: String): Module = tagToModule[tag] ?: Module.MISC

    fun isEnabled(module: Module): Boolean {
        val on = ENABLED_MODULES
        return Module.ALL in on ||
            module in on ||
            (module.underPdf && Module.PDF in on)
    }

    fun isTagEnabled(tag: String): Boolean = isEnabled(moduleForTag(tag))

    fun enabledModuleIds(): List<String> {
        if (Module.ALL in ENABLED_MODULES) return listOf("all")
        return Module.entries
            .filter { it != Module.ALL && isEnabled(it) }
            .map { it.id }
            .distinct()
            .sorted()
    }

    fun moduleHelp(): String = Module.entries
        .filter { it != Module.ALL }
        .joinToString("\n") { m ->
            val extra = if (m.underPdf) " (pdf)" else ""
            "  ${m.id}$extra → ${m.tag}"
        }

    fun dumpEnabled(where: String = "ReaderLog") {
        Log.i(where, "log modules on=${enabledModuleIds().ifEmpty { listOf("off") }}")
    }

    fun d(tag: String, msg: String) {
        if (!isTagEnabled(tag)) return
        Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (!isTagEnabled(tag)) return
        Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (!isTagEnabled(tag)) return
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (!isTagEnabled(tag)) return
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }

    fun d(module: Module, msg: String) = d(module.tag, msg)

    fun i(module: Module, msg: String) = i(module.tag, msg)

    fun w(module: Module, msg: String, tr: Throwable? = null) = w(module.tag, msg, tr)

    fun e(module: Module, msg: String, tr: Throwable? = null) = e(module.tag, msg, tr)
}
