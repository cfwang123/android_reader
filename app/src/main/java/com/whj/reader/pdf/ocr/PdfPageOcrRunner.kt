package com.whj.reader.pdf.ocr
import com.whj.reader.PdfReadingActivity
import com.whj.reader.pdf.render.PdfLayoutMetrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import com.whj.reader.data.PdfOcrCacheStore
import com.whj.reader.data.PdfOcrConverter
import com.whj.reader.ocr.OcrTileHelper
import com.whj.reader.ocr.TfliteOcrEngine
import com.whj.reader.pdf.render.PdfRenderConfig
import com.whj.reader.util.ReaderLog
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 单页 OCR 流水线：渲染 → 条带识别 → 合并 → 落盘缓存。
 * UI 进度对话框 / 任务编排仍由宿主 Activity 负责。
 */
class PdfPageOcrRunner(
    private val activity: PdfReadingActivity,
) {

    companion object {
        /** 连续页合并为区间，如 `1~100, 151~299` */
        fun formatPageList(pages1Based: List<Int>, andMoreLabel: (Int) -> String): String {
            if (pages1Based.isEmpty()) return ""
            val sorted = pages1Based.distinct().sorted()
            val ranges = ArrayList<String>()
            var start = sorted[0]
            var prev = sorted[0]
            for (i in 1 until sorted.size) {
                val p = sorted[i]
                if (p == prev + 1) {
                    prev = p
                } else {
                    ranges.add(if (start == prev) "$start" else "$start~$prev")
                    start = p
                    prev = p
                }
            }
            ranges.add(if (start == prev) "$start" else "$start~$prev")
            // 区间过多时截断，避免对话框被撑爆
            if (ranges.size <= 24) return ranges.joinToString(", ")
            return ranges.take(20).joinToString(", ") +
                andMoreLabel(sorted.size)
        }
    }

    fun ocrOnePage(pageIndex: Int, engine: TfliteOcrEngine): Boolean {
        val r = activity.renderer ?: return false
        if (pageIndex !in 0 until r.pageCount) return false
        val dbg = StringBuilder()
        fun log(msg: String) {
            dbg.append(msg).append('\n')
            ReaderLog.i(ReaderLog.Module.PDF_OCR, msg)
        }

        val margins = activity.cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]

        val screenH = activity.resources.displayMetrics.heightPixels.coerceAtLeast(800)
        val ocrTargetW = activity.pdfMaxRenderWidth()
        // 条带不宜太高：det 输入约 0.3× 缩放时字过小；控制在 ~900px 提高检出率
        val tileHPx = (screenH * 0.55f).toInt().coerceIn(520, 960)
        val overlapPx = (tileHPx * 0.22f).toInt().coerceIn(80, 220)

        val (pageW, pageH) = synchronized(activity.renderLock) {
            activity.currentPage?.close()
            activity.currentPage = null
            val page = r.openPage(pageIndex)
            activity.currentPage = page
            try {
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                activity.rendererPageSize[pageIndex] = pw to ph
                page.close()
                activity.currentPage = null
                pw to ph
            } catch (t: Throwable) {
                runCatching { page.close() }
                activity.currentPage = null
                throw t
            }
        }

        val contentW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val contentH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        log(
            "=== ocr page=$pageIndex === screen=${activity.pdfViewportWidth()}x${screenH} " +
                "pagePt=${pageW}x${pageH} crop=[$cl,$ct,$cr,$cb] " +
                "contentPt=${contentW}x${contentH} aspect=${contentH / contentW.coerceAtLeast(1f)}",
        )

        // 先估 scale（与 strip 渲染一致），判断是否分块
        val displayH = PdfLayoutMetrics.logicalDisplayHeight(pageW, pageH, margins, ocrTargetW)
        var probeScale = (ocrTargetW / contentW).coerceIn(0.05f, 1.25f)
        val probeStripH = min(contentH, tileHPx / probeScale)
        val areaBefore = contentW * probeStripH * probeScale * probeScale
        if (areaBefore > PdfRenderConfig.TILE_MAX_PIXELS) {
            probeScale = sqrt(PdfRenderConfig.TILE_MAX_PIXELS / (contentW * probeStripH.coerceAtLeast(1f)))
        }
        probeScale = probeScale.coerceAtLeast(0.05f)
        val estFullW = max(1, (contentW * probeScale).toInt())
        val estFullH = max(1, (contentH * probeScale).toInt())
        val useTilesByHelper = OcrTileHelper.shouldTile(displayH, tileHPx, ocrTargetW)
        // PDF 竖长页强制分块（旧逻辑用 estFullH 可能因 scale 过小漏判）
        val useTilesByAspect = PdfOcrCacheStore.isTallPage(pageW, pageH) && displayH > 500
        val useTiles = useTilesByHelper || useTilesByAspect
        log(
            "probeScale=$probeScale tileHPx=$tileHPx ovPx=$overlapPx " +
                "displayH=$displayH estPx=${estFullW}x${estFullH} areaStrip=$areaBefore " +
                "useTiles=$useTiles (helper=$useTilesByHelper aspect=$useTilesByAspect)",
        )

        val lines: List<TfliteOcrEngine.LineResult>
        val mapBmpW: Int
        val mapBmpH: Int

        if (!useTiles) {
            // 矮页：整页一次，用实际位图尺寸映射
            val bmp = renderOcrPageBitmap(r, pageIndex, ocrTargetW)
            try {
                log(
                    "single-shot bmp=${bmp.width}x${bmp.height} bytes=${bmp.byteCount} " +
                        "backend=${engine.backendName}",
                )
                val safe = ensureSoftwareArgb(bmp)
                val result = engine.recognize(safe, autoInvert = true)
                if (safe !== bmp && !safe.isRecycled) safe.recycle()
                lines = result.lines
                mapBmpW = bmp.width
                mapBmpH = bmp.height
                log(
                    "single-shot done lines=${lines.size} detMs=${result.detMs} " +
                        "recMs=${result.recMs} backend=${result.backend}",
                )
                log(lineYSpanText("single", lines))
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } else {
            // 长页/竖长截图：按内容高度切条带，块高对应 tileHPx 像素
            val stripContentH = (tileHPx / probeScale).coerceIn(8f, contentH)
            val overlapContent = (overlapPx / probeScale).coerceIn(0f, stripContentH * 0.45f)
            val ranges = verticalContentRanges(contentH, stripContentH, overlapContent)
            log(
                "tile mode strips=${ranges.size} stripHPt=$stripContentH ovPt=$overlapContent " +
                    "cover=${ranges.firstOrNull()?.first}->${ranges.lastOrNull()?.second} / $contentH",
            )
            ranges.forEachIndexed { i, (a, b) ->
                log("  range[$i]=$a..$b h=${b - a}")
            }

            val parts = ArrayList<List<TfliteOcrEngine.LineResult>>(ranges.size)
            var unifiedW = 0
            var unifiedScale = probeScale
            var totalLocalLines = 0
            var gpuEmptyStreak = 0
            log("tile engine backend=${engine.backendName}")

            for ((ti, range) in ranges.withIndex()) {
                if (Thread.interrupted() || !activity.isOcrJobActive()) {
                    throw kotlinx.coroutines.CancellationException("ocr cancelled")
                }
                val (srcY0, srcY1) = range
                val strip = renderOcrStripBitmap(r, pageIndex, ocrTargetW, srcY0, srcY1)
                try {
                    if (unifiedW <= 0) {
                        unifiedW = strip.width.coerceAtLeast(1)
                        unifiedScale = unifiedW / contentW
                        log("unifiedW=$unifiedW unifiedScale=$unifiedScale")
                    }
                    val ink = sampleInkRatio(strip)
                    val inkF = ink.toFloatOrNull() ?: 0f
                    // GPU 输入用独立软件位图，避免连续条带复用/硬件缓冲导致 det 哑火
                    val safeBmp = ensureSoftwareArgb(strip)
                    var result = engine.recognize(safeBmp, autoInvert = true)
                    var usedBackend = result.backend
                    // 主路径含 GPU 时：有“文字感”墨量却 0 行 → 本条纯 CPU 回退
                    val mainIsGpu = engine.backendName.contains("GPU", ignoreCase = true)
                    if (result.lines.isEmpty() && inkLooksLikeText(inkF) && mainIsGpu) {
                        gpuEmptyStreak++
                        log(
                            "  strip$ti GPU empty streak=$gpuEmptyStreak ink=$ink " +
                                "backend=${engine.backendName} → try CPU",
                        )
                        val cpu = ensureOcrCpuFallback()
                        val r2 = cpu.recognize(safeBmp, autoInvert = true)
                        if (r2.lines.isNotEmpty()) {
                            result = r2
                            usedBackend = "CPU-fallback"
                            log("  strip$ti CPU-fallback ok lines=${r2.lines.size}")
                            gpuEmptyStreak = 0
                        } else {
                            log("  strip$ti CPU-fallback still empty")
                        }
                    } else if (result.lines.isNotEmpty()) {
                        gpuEmptyStreak = 0
                    } else {
                        // 高 ink 多半是插图区，或不含 GPU 无需回退
                        log("  strip$ti empty skip-fallback ink=$ink backend=${engine.backendName}")
                    }
                    if (safeBmp !== strip && !safeBmp.isRecycled) safeBmp.recycle()

                    val local = result.lines
                    totalLocalLines += local.size
                    // 条带像素 → 整页内容像素：x 按宽比，y = 顶偏移 + 局部 y 按高比
                    val xScale = if (strip.width > 0) unifiedW / strip.width.toFloat() else 1f
                    val stripContentSpan = (srcY1 - srcY0).coerceAtLeast(0.5f)
                    val topPx = srcY0 * unifiedScale
                    val spanPx = stripContentSpan * unifiedScale
                    val yScale = if (strip.height > 0) spanPx / strip.height else 1f
                    val mapH = max(1, (contentH * unifiedScale).toInt()).toFloat()
                    val mapped = local.map { line ->
                        val box = line.box
                        if (box == null || box.size < 8) {
                            line
                        } else {
                            val nb = FloatArray(8) { i ->
                                if (i % 2 == 0) {
                                    (box[i] * xScale).coerceIn(0f, unifiedW.toFloat())
                                } else {
                                    (box[i] * yScale + topPx).coerceIn(0f, mapH)
                                }
                            }
                            line.copy(box = nb)
                        }
                    }
                    parts += mapped
                    val sample = local.take(2).joinToString(" | ") { it.text.take(24) }
                    if (result.log.contains("invert") || result.log.contains("lowThr")) {
                        log("  engine: ${result.log.trim().replace("\n", " | ")}")
                    }
                    log(
                        "strip $ti/${ranges.size} yPt=$srcY0..$srcY1 " +
                            "bmp=${strip.width}x${strip.height} ink=$ink " +
                            "topPx=$topPx yScale=$yScale lines=${local.size} " +
                            "detMs=${result.detMs} recMs=${result.recMs} " +
                            "via=$usedBackend sample=[$sample]",
                    )
                    log(lineYSpanText("strip$ti-local", local))
                    log(lineYSpanText("strip$ti-mapped", mapped))
                } finally {
                    if (!strip.isRecycled) strip.recycle()
                }
            }

            val beforeMerge = parts.sumOf { it.size }
            lines = OcrTileHelper.mergeLines(parts)
            mapBmpW = unifiedW.coerceAtLeast(1)
            mapBmpH = max(1, (contentH * unifiedScale).toInt())
            log(
                "merge: parts=${parts.size} linesBefore=$beforeMerge " +
                    "localSum=$totalLocalLines after=${lines.size} " +
                    "mapBmp=${mapBmpW}x${mapBmpH}",
            )
            log(lineYSpanText("merged", lines))
        }

        val chars = PdfOcrConverter.linesToPdfChars(
            pageIndex = pageIndex,
            lines = lines,
            bmpW = mapBmpW,
            bmpH = mapBmpH,
            pageW = pageW,
            pageH = pageH,
            cropL = cl,
            cropT = ct,
            cropR = cr,
            cropB = cb,
        )
        // 字符在页坐标中的纵向覆盖比例（用于判断是否只识别了上部）
        if (chars.isNotEmpty()) {
            val yMin = chars.minOf { it.top }
            val yMax = chars.maxOf { it.bottom }
            val cover = (yMax - yMin) / pageH.coerceAtLeast(1f)
            log(
                "chars=${chars.size} pageY=$yMin..$yMax " +
                    "coverFrac=$cover map=${mapBmpW}x${mapBmpH} page=${pageW}x${pageH}",
            )
        } else {
            log("chars=0 (empty OCR)")
        }
        writeOcrDebugFile(pageIndex, dbg.toString())
        PdfOcrCacheStore.savePage(
            activity,
            activity.fileKey,
            pageIndex,
            chars,
            pageWidth = pageW,
            pageHeight = pageH,
        )
        return true
    }

    private fun lineYSpanText(tag: String, lines: List<TfliteOcrEngine.LineResult>): String {
        if (lines.isEmpty()) return "  $tag: empty"
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (line in lines) {
            val box = line.box ?: continue
            if (box.size < 8) continue
            val ys = floatArrayOf(box[1], box[3], box[5], box[7])
            minY = min(minY, ys.min())
            maxY = max(maxY, ys.max())
        }
        return if (minY == Float.MAX_VALUE) {
            "  $tag: ${lines.size} lines (no boxes)"
        } else {
            "  $tag: ${lines.size} lines y=$minY..$maxY " +
                "first='${lines.first().text.take(20)}' last='${lines.last().text.take(20)}'"
        }
    }

    /** 白底正文常见 ink 区间；过高多为插图/大色块，不必 CPU 回退 */
    private fun inkLooksLikeText(ink: Float): Boolean = ink in 0.04f..0.62f

    private fun ensureSoftwareArgb(src: android.graphics.Bitmap): android.graphics.Bitmap {
        if (src.config == android.graphics.Bitmap.Config.ARGB_8888 && !src.isMutable) {
            // 仍 copy 一份，切断与 PdfRenderer 缓冲/上一帧 GPU 输入的关联
            return src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: src
        }
        return src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: src
    }

    private fun ensureOcrCpuFallback(): TfliteOcrEngine {
        activity.ocrCpuFallback?.let { return it }
        return TfliteOcrEngine(activity, TfliteOcrEngine.Backend.CPU).also {
            activity.ocrCpuFallback = it
            ReaderLog.i(ReaderLog.Module.PDF_OCR, "cpu fallback engine opened backend=${it.backendName}")
        }
    }

    /** 抽样非白像素比例，用于判断条带渲染是否空白 */
    private fun sampleInkRatio(bmp: android.graphics.Bitmap): String {
        if (bmp.isRecycled || bmp.width < 2 || bmp.height < 2) return "n/a"
        val w = bmp.width
        val h = bmp.height
        val stepX = max(1, w / 40)
        val stepY = max(1, h / 40)
        var dark = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 相对白底有墨
                if (r < 245 || g < 245 || b < 245) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        val ratio = if (total > 0) dark.toFloat() / total else 0f
        return String.format(java.util.Locale.US, "%.3f", ratio)
    }

    private fun writeOcrDebugFile(pageIndex: Int, text: String) {
        runCatching {
            val dir = java.io.File(activity.filesDir, "pdf_ocr_debug").also { it.mkdirs() }
            java.io.File(dir, "page_$pageIndex.txt").writeText(text, Charsets.UTF_8)
            // 整文件覆盖会话汇总，避免无限增长
            java.io.File(dir, "last_session.txt").writeText(
                "----- page $pageIndex ${System.currentTimeMillis()} -----\n$text",
                Charsets.UTF_8,
            )
        }.onFailure {
            ReaderLog.w(ReaderLog.Module.PDF_OCR, "write debug fail", it)
        }
    }

    /** 内容坐标系下的纵向条带 [y0, y1)（已扣 crop 的内容高） */
    private fun verticalContentRanges(
        contentH: Float,
        stripH: Float,
        overlap: Float,
    ): List<Pair<Float, Float>> {
        val h = contentH.coerceAtLeast(1f)
        val th = stripH.coerceIn(1f, h)
        val ov = overlap.coerceIn(0f, th * 0.45f)
        // 与 OcrTileHelper.shouldTile 一致：略超块高也分块
        if (h <= th * 1.05f) return listOf(0f to h)
        val step = (th - ov).coerceAtLeast(1f)
        val out = ArrayList<Pair<Float, Float>>()
        var top = 0f
        while (true) {
            val bottom = min(h, top + th)
            out += top to bottom
            if (bottom >= h - 0.01f) break
            val next = top + step
            if (next + th >= h) {
                // 末块贴底，高度不超过 th（不把末段拉成超高条）
                val lastTop = max(0f, h - th)
                if (lastTop > top + ov * 0.5f) {
                    out += lastTop to h
                } else {
                    out[out.lastIndex] = top to h
                }
                break
            }
            top = next
        }
        return out
    }

    private fun renderOcrPageBitmap(
        r: PdfRenderer,
        pageIndex: Int,
        targetWidth: Int,
    ): Bitmap = synchronized(activity.renderLock) {
        activity.currentPage?.close()
        activity.currentPage = null
        val page = r.openPage(pageIndex)
        activity.currentPage = page
        try {
            val b = activity.renderPageBitmap(
                page = page,
                targetWidth = targetWidth,
                pageIndexForMirror = pageIndex,
            )
            page.close()
            activity.currentPage = null
            b
        } catch (t: Throwable) {
            runCatching { page.close() }
            activity.currentPage = null
            throw t
        }
    }

    private fun renderOcrStripBitmap(
        r: PdfRenderer,
        pageIndex: Int,
        targetWidth: Int,
        srcY0: Float,
        srcY1: Float,
    ): Bitmap = synchronized(activity.renderLock) {
        activity.currentPage?.close()
        activity.currentPage = null
        val page = r.openPage(pageIndex)
        activity.currentPage = page
        try {
            val b = activity.renderPageStripBitmap(
                page = page,
                targetWidth = targetWidth,
                srcY0 = srcY0,
                srcY1 = srcY1,
                pageIndexForMirror = pageIndex,
            )
            page.close()
            activity.currentPage = null
            b
        } catch (t: Throwable) {
            runCatching { page.close() }
            activity.currentPage = null
            throw t
        }
    }

}
