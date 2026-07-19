package com.whj.reader.data

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 书内链接（GoTo）与 URI 链接。
 * 矩形坐标与 [PdfTextExtractor.PdfChar] 一致：左上原点、Y 向下。
 */
object PdfLinkIndex {
    private const val TAG = "PdfLinkIndex"

    data class Link(
        val pageIndex: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** 书内目标页（0-based）；外部 URI 时为 null */
        val targetPage: Int?,
        val uri: String?,
    ) {
        fun contains(pageX: Float, pageY: Float, pad: Float = 2f): Boolean {
            return pageX >= left - pad && pageX <= right + pad &&
                pageY >= top - pad && pageY <= bottom + pad
        }
    }

    /**
     * 从已打开的 [PDDocument] 提取全部页链接。
     * 失败返回空 map，不抛异常。
     */
    fun extractAll(doc: PDDocument): Map<Int, List<Link>> {
        val out = HashMap<Int, MutableList<Link>>()
        val n = doc.numberOfPages
        for (i in 0 until n) {
            val page = try {
                doc.getPage(i)
            } catch (_: Throwable) {
                continue
            }
            val annos = try {
                page.annotations
            } catch (_: Throwable) {
                null
            } ?: continue
            val box = try {
                page.cropBox ?: page.mediaBox
            } catch (_: Throwable) {
                null
            } ?: continue
            val pageH = box.height
            val pageW = box.width
            if (pageH <= 0f || pageW <= 0f) continue
            // cropBox 原点偏移（PDF 用户空间）
            val ox = box.lowerLeftX
            val oy = box.lowerLeftY
            for (anno in annos) {
                if (anno !is PDAnnotationLink) continue
                if (anno.isHidden || anno.isNoView) continue
                val rect = try {
                    anno.rectangle
                } catch (_: Throwable) {
                    null
                } ?: continue
                // PDF 用户空间 → 以 cropBox 为参考的页内坐标，再翻转为左上原点 Y 向下
                val l = rect.lowerLeftX - ox
                val r = rect.upperRightX - ox
                val pdfBottom = rect.lowerLeftY - oy
                val pdfTop = rect.upperRightY - oy
                val left = min(l, r)
                val right = max(l, r)
                val top = pageH - max(pdfBottom, pdfTop)
                val bottom = pageH - min(pdfBottom, pdfTop)
                if (right - left < 1f || bottom - top < 1f) continue

                var targetPage: Int? = null
                var uri: String? = null
                try {
                    val action = anno.action
                    when (action) {
                        is PDActionGoTo -> {
                            targetPage = resolveDestination(action.destination, doc)
                        }
                        is PDActionURI -> {
                            uri = action.uri?.takeIf { it.isNotBlank() }
                        }
                        else -> {
                            // 部分链接只写 destination、无 action
                            val dest = try {
                                anno.destination
                            } catch (_: Throwable) {
                                null
                            }
                            if (dest != null) {
                                targetPage = resolveDestination(dest, doc)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "link action page=$i", t)
                }
                if (targetPage == null && uri.isNullOrBlank()) continue
                // 目标页非法则丢弃
                if (targetPage != null && (targetPage < 0 || targetPage >= n)) continue
                out.getOrPut(i) { ArrayList() }.add(
                    Link(
                        pageIndex = i,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        targetPage = targetPage,
                        uri = uri,
                    ),
                )
            }
        }
        val total = out.values.sumOf { it.size }
        Log.i(TAG, "links extracted pages=${out.size} total=$total")
        return out
    }

    private fun resolveDestination(
        dest: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination?,
        doc: PDDocument,
    ): Int {
        if (dest == null) return -1
        return try {
            when (dest) {
                is PDPageDestination -> {
                    try {
                        val page = dest.page
                        if (page != null) {
                            val idx = doc.pages.indexOf(page)
                            if (idx >= 0) return idx
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        val n = dest.pageNumber
                        if (n >= 0) return n.coerceAtMost(doc.numberOfPages - 1)
                    } catch (_: Throwable) {
                    }
                    -1
                }
                is PDNamedDestination -> {
                    try {
                        val name = dest.namedDestination ?: return -1
                        val names = doc.documentCatalog?.names?.dests ?: return -1
                        val resolved = names.getValue(name)
                        if (resolved is PDPageDestination) {
                            return resolveDestination(resolved, doc)
                        }
                    } catch (_: Throwable) {
                    }
                    -1
                }
                else -> -1
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolveDestination", t)
            -1
        }
    }
}
