package com.whj.reader.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 按 PDF 文件持久化 OCR 结果（每页字符 + 坐标）。
 * 目录：files/pdf_ocr/{sha1(fileKey)}/page_{n}.json + index.json
 */
object PdfOcrCacheStore {

    private const val TAG = "PdfOcrCache"
    private const val DIR = "pdf_ocr"
    private const val INDEX = "index.json"
    private const val VERSION = 1

    data class PageIndex(
        val pageIndex: Int,
        val charCount: Int,
        val updatedAt: Long,
    )

    private fun rootDir(ctx: Context): File =
        File(ctx.filesDir, DIR).also { it.mkdirs() }

    private fun bookDir(ctx: Context, fileKey: String): File {
        val hash = sha1(fileKey).take(24)
        return File(rootDir(ctx), hash).also { it.mkdirs() }
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    fun pageFile(ctx: Context, fileKey: String, pageIndex: Int): File =
        File(bookDir(ctx, fileKey), "page_$pageIndex.json")

    fun listRecognized(ctx: Context, fileKey: String): Set<Int> {
        return loadIndex(ctx, fileKey).keys
    }

    /**
     * 可跳过的「已识别」页：必须有实际文字。
     * chars=0 的空结果（如长图旧版 OCR 失败）不算完成，允许重新识别。
     */
    fun listRecognizedWithText(ctx: Context, fileKey: String): Set<Int> {
        return loadIndex(ctx, fileKey)
            .filterValues { it.charCount > 0 }
            .keys
    }

    fun loadIndex(ctx: Context, fileKey: String): Map<Int, PageIndex> {
        val f = File(bookDir(ctx, fileKey), INDEX)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("pages") ?: return emptyMap()
            buildMap {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val idx = p.getInt("page")
                    put(
                        idx,
                        PageIndex(
                            pageIndex = idx,
                            charCount = p.optInt("chars", 0),
                            updatedAt = p.optLong("ts", 0L),
                        ),
                    )
                }
            }
        }.getOrElse {
            Log.w(TAG, "loadIndex fail", it)
            emptyMap()
        }
    }

    private fun saveIndex(ctx: Context, fileKey: String, index: Map<Int, PageIndex>) {
        val arr = JSONArray()
        index.values.sortedBy { it.pageIndex }.forEach { pi ->
            arr.put(
                JSONObject()
                    .put("page", pi.pageIndex)
                    .put("chars", pi.charCount)
                    .put("ts", pi.updatedAt),
            )
        }
        val o = JSONObject()
            .put("v", VERSION)
            .put("fileKey", fileKey)
            .put("pages", arr)
        File(bookDir(ctx, fileKey), INDEX).writeText(o.toString(), Charsets.UTF_8)
    }

    fun savePage(
        ctx: Context,
        fileKey: String,
        pageIndex: Int,
        chars: List<PdfTextExtractor.PdfChar>,
        pageWidth: Float = 0f,
        pageHeight: Float = 0f,
    ) {
        val arr = JSONArray()
        var pw = pageWidth.coerceAtLeast(0f)
        var ph = pageHeight.coerceAtLeast(0f)
        for (c in chars) {
            if (pw < 1f) pw = c.pageWidth
            if (ph < 1f) ph = c.pageHeight
            arr.put(
                JSONObject()
                    .put("c", c.char.toString())
                    .put("l", c.left.toDouble())
                    .put("t", c.top.toDouble())
                    .put("r", c.right.toDouble())
                    .put("b", c.bottom.toDouble()),
            )
        }
        if (pw < 1f) pw = 1f
        if (ph < 1f) ph = 1f
        val o = JSONObject()
            .put("v", VERSION)
            .put("page", pageIndex)
            .put("pw", pw.toDouble())
            .put("ph", ph.toDouble())
            .put("chars", arr)
        pageFile(ctx, fileKey, pageIndex).writeText(o.toString(), Charsets.UTF_8)

        val idx = loadIndex(ctx, fileKey).toMutableMap()
        idx[pageIndex] = PageIndex(pageIndex, chars.size, System.currentTimeMillis())
        saveIndex(ctx, fileKey, idx)
    }

    fun loadPage(ctx: Context, fileKey: String, pageIndex: Int): List<PdfTextExtractor.PdfChar>? {
        val f = pageFile(ctx, fileKey, pageIndex)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val pw = o.optDouble("pw", 1.0).toFloat().coerceAtLeast(1f)
            val ph = o.optDouble("ph", 1.0).toFloat().coerceAtLeast(1f)
            val arr = o.getJSONArray("chars")
            buildList {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val ch = c.getString("c").firstOrNull() ?: continue
                    add(
                        PdfTextExtractor.PdfChar(
                            pageIndex = pageIndex,
                            indexOnPage = size,
                            char = ch,
                            left = c.getDouble("l").toFloat(),
                            top = c.getDouble("t").toFloat(),
                            right = c.getDouble("r").toFloat(),
                            bottom = c.getDouble("b").toFloat(),
                            pageWidth = pw,
                            pageHeight = ph,
                        ),
                    )
                }
            }
        }.getOrElse {
            Log.w(TAG, "loadPage $pageIndex fail", it)
            null
        }
    }

    /** 加载该书全部已 OCR 页 */
    fun loadAllPages(ctx: Context, fileKey: String): Map<Int, List<PdfTextExtractor.PdfChar>> {
        val index = loadIndex(ctx, fileKey)
        if (index.isEmpty()) return emptyMap()
        return buildMap {
            for (p in index.keys) {
                val chars = loadPage(ctx, fileKey, p) ?: continue
                put(p, chars)
            }
        }
    }

    /** 清除某页 OCR 缓存（含空结果） */
    fun removePage(ctx: Context, fileKey: String, pageIndex: Int) {
        if (fileKey.isBlank()) return
        runCatching { pageFile(ctx, fileKey, pageIndex).delete() }
        val idx = loadIndex(ctx, fileKey).toMutableMap()
        if (idx.remove(pageIndex) != null) {
            saveIndex(ctx, fileKey, idx)
        }
    }

    /** 删除某 PDF 的全部 OCR 缓存目录 */
    fun removeBook(ctx: Context, fileKey: String) {
        if (fileKey.isBlank()) return
        val hash = sha1(fileKey).take(24)
        val dir = File(rootDir(ctx), hash)
        runCatching {
            if (dir.isDirectory) dir.deleteRecursively()
        }.onFailure { Log.w(TAG, "removeBook $hash: ${it.message}") }
    }
}
