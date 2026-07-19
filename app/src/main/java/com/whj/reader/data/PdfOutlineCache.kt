package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * PDF 大纲缓存：内存 + 磁盘，避免每次打开目录都重新 PDFBox 解析（常需 1–2s）。
 */
object PdfOutlineCache {
    private const val TAG = "PdfOutlineCache"
    private const val DIR = "pdf_outline_cache"

    private val memory = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val roots: List<PdfOutlineLoader.Node>,
        val stamp: String,
    )

    /** 文件指纹：uri + size + lastModified（能取到时） */
    fun stamp(context: Context, uri: Uri): String {
        val u = uri.toString()
        val meta = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                "$size"
            }
        }.getOrNull() ?: "-"
        return "${u.hashCode().toUInt().toString(16)}_$meta"
    }

    fun get(context: Context, uri: Uri): List<PdfOutlineLoader.Node>? {
        val key = uri.toString()
        val st = stamp(context, uri)
        memory[key]?.let { if (it.stamp == st) return it.roots }
        val disk = loadDisk(context, key, st) ?: return null
        memory[key] = CacheEntry(disk, st)
        return disk
    }

    fun put(context: Context, uri: Uri, roots: List<PdfOutlineLoader.Node>) {
        val key = uri.toString()
        val st = stamp(context, uri)
        memory[key] = CacheEntry(roots, st)
        saveDisk(context, key, st, roots)
    }

    fun remove(context: Context, uri: Uri) {
        val key = uri.toString()
        memory.remove(key)
        runCatching { cacheFile(context, key).delete() }
            .onFailure { Log.w(TAG, "remove outline cache: ${it.message}") }
    }

    fun remove(context: Context, uriKey: String) {
        if (uriKey.isBlank()) return
        memory.remove(uriKey)
        runCatching { cacheFile(context, uriKey).delete() }
    }

    fun loadOrParse(context: Context, uri: Uri): List<PdfOutlineLoader.Node> {
        get(context, uri)?.let {
            Log.i(TAG, "outline cache hit ${uri.toString().takeLast(32)}")
            return it
        }
        val roots = PdfOutlineLoader.load(context, uri)
        // 空目录也缓存，避免反复解析无大纲 PDF
        put(context, uri, roots)
        Log.i(TAG, "outline cached nodes=${roots.size}")
        return roots
    }

    private fun cacheDir(ctx: Context): File =
        File(ctx.filesDir, DIR).also { it.mkdirs() }

    private fun cacheFile(ctx: Context, uriKey: String): File {
        val safe = uriKey.hashCode().toUInt().toString(16)
        return File(cacheDir(ctx), "$safe.json")
    }

    private fun saveDisk(
        ctx: Context,
        uriKey: String,
        stamp: String,
        roots: List<PdfOutlineLoader.Node>,
    ) {
        runCatching {
            val o = JSONObject()
                .put("stamp", stamp)
                .put("uri", uriKey)
                .put("roots", nodesToJson(roots))
            cacheFile(ctx, uriKey).writeText(o.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "save disk failed: ${it.message}") }
    }

    private fun loadDisk(
        ctx: Context,
        uriKey: String,
        stamp: String,
    ): List<PdfOutlineLoader.Node>? {
        val f = cacheFile(ctx, uriKey)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            if (o.optString("stamp") != stamp) return null
            jsonToNodes(o.getJSONArray("roots"))
        }.onFailure {
            Log.w(TAG, "load disk failed: ${it.message}")
        }.getOrNull()
    }

    private fun nodesToJson(nodes: List<PdfOutlineLoader.Node>): JSONArray {
        val arr = JSONArray()
        for (n in nodes) {
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("pageIndex", n.pageIndex)
                    .put("children", nodesToJson(n.children)),
            )
        }
        return arr
    }

    private fun jsonToNodes(arr: JSONArray): List<PdfOutlineLoader.Node> {
        val out = ArrayList<PdfOutlineLoader.Node>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val children = if (o.has("children")) {
                jsonToNodes(o.getJSONArray("children"))
            } else {
                emptyList()
            }
            out.add(
                PdfOutlineLoader.Node(
                    id = o.getInt("id"),
                    title = o.optString("title", "…"),
                    pageIndex = o.optInt("pageIndex", -1),
                    children = children,
                ),
            )
        }
        return out
    }
}
