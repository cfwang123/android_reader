package com.whj.reader.util

import android.os.SystemClock
import android.util.Log

/**
 * 按模块开关 adb 调试日志（默认全关，避免 logcat 刷屏）。
 *
 * ```
 * # 只开 PDF 翻页
 * adb shell setprop debug.whj.reader.log pdf_page_turn
 *
 * # 开多个模块（逗号/分号/空格均可）
 * adb shell setprop debug.whj.reader.log pdf_page_turn,pdf_zoom,manga_zoom
 *
 * # 开整个 PDF 族（含翻页/缩放/方向/打开/菜单/OCR 调试）
 * adb shell setprop debug.whj.reader.log pdf
 *
 * # 全开
 * adb shell setprop debug.whj.reader.log all
 *
 * # 全关
 * adb shell setprop debug.whj.reader.log off
 *
 * adb logcat -s PdfPageTurn:I PdfPageTurn:W
 * ```
 *
 * 修改 setprop 后约 0.5s 内生效，无需重启 App。
 */
object ReaderLog {

    private const val PROP = "debug.whj.reader.log"
    private const val PROP_CACHE_MS = 500L

    enum class Module(
        /** [PROP] 配置名 */
        val id: String,
        /** logcat tag */
        val tag: String,
        /** 为 true 时，开启父模块 [id] 会连带开启本模块 */
        val underPdf: Boolean = false,
    ) {
        PDF("pdf", "PdfReading"),
        PDF_PAGE_TURN("pdf_page_turn", "PdfPageTurn", underPdf = true),
        PDF_ZOOM("pdf_zoom", "PdfZoom", underPdf = true),
        PDF_ORIENT("pdf_orient", "PdfOrient", underPdf = true),
        PDF_OPEN("pdf_open", "PdfOpen", underPdf = true),
        PDF_CHROME("pdf_chrome", "PdfChrome", underPdf = true),
        PDF_OCR("pdf_ocr", "PdfOcrDbg", underPdf = true),
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

    @Volatile
    private var cachedRaw: String? = null
    @Volatile
    private var cachedEnabled: Set<Module>? = null
    @Volatile
    private var cacheAtMs = 0L

    fun moduleForTag(tag: String): Module = tagToModule[tag] ?: Module.MISC

    fun isEnabled(module: Module): Boolean = enabledSet().let { on ->
        Module.ALL in on || module in on || (module.underPdf && Module.PDF in on)
    }

    fun isTagEnabled(tag: String): Boolean = isEnabled(moduleForTag(tag))

    fun enabledModuleIds(): List<String> {
        val on = enabledSet()
        if (Module.ALL in on) return listOf("all")
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
        val raw = readProp()
        Log.i(where, "log modules raw='$raw' on=${enabledModuleIds()}")
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

    private fun enabledSet(): Set<Module> {
        val raw = readProp()
        val now = SystemClock.uptimeMillis()
        if (cachedEnabled != null && raw == cachedRaw && now - cacheAtMs < PROP_CACHE_MS) {
            return cachedEnabled!!
        }
        cachedRaw = raw
        cacheAtMs = now
        val text = raw.trim().lowercase()
        cachedEnabled = when {
            text.isEmpty() || text == "off" || text == "none" || text == "0" -> emptySet()
            text == "all" || text == "*" || text == "1" -> setOf(Module.ALL)
            else -> {
                val ids = text.split(',', ';', ' ', '|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                buildSet {
                    ids.forEach { id ->
                        when (id) {
                            "all", "*" -> add(Module.ALL)
                            "pdf" -> add(Module.PDF)
                            else -> Module.entries.find { it.id == id }?.let { add(it) }
                        }
                    }
                }
            }
        }
        return cachedEnabled!!
    }

    private fun readProp(): String {
        readPropReflection()?.let { if (it.isNotEmpty()) return it }
        return readPropShell()
    }

    private fun readPropReflection(): String? {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java, String::class.java)
            (m.invoke(null, PROP, "") as? String).orEmpty()
        } catch (_: Throwable) {
            null
        }
    }

    /** 部分机型屏蔽 SystemProperties 反射，用 getprop 子进程读取 */
    private fun readPropShell(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", PROP))
            p.inputStream.bufferedReader().use { it.readText().trim() }
        } catch (_: Throwable) {
            ""
        }
    }
}
