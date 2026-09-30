package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.util.AppLog
import com.whj.reader.util.StorageAccess
import java.io.File
import java.io.RandomAccessFile

/**
 * 启动时删掉以前复制进缓存的整本和抽出的图片，并丢掉已经无效的解析缓存。
 * 无效：记录的书不在书架、最近阅读、阅读进度里，或解析出的路径上文件已经不在。
 */
object EbookCacheCleaner {

    private const val TAG = "EbookCacheCleaner"

    fun dropCopiedBooks(context: Context) {
        var freed = 0L
        for (root in AppDataDir.ebookCacheRoots(context)) {
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                freed += deleteFile(File(dir, "book.mobi"))
                freed += deleteFile(File(dir, "book.epub"))
                freed += deleteTree(File(dir, "images"))
                // 旧 spine 缓存里是抽出的图片路径，新版本改用 epubimg
                freed += deleteTree(File(dir, "spine_cache"))
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith("parsed_") && f.name.endsWith("_v7.bin")) {
                        freed += deleteFile(f)
                    }
                }
            }
        }
        if (freed > 0L) {
            AppLog.i(TAG, "dropped copied books ${freed / 1024}KB")
        }
    }

    fun dropStale(context: Context) {
        val keep = HashSet<String>()
        runCatching {
            BookshelfStore.books(context).forEach { keep.add(it.uri) }
            RecentStore.list(context).forEach { keep.add(it.uri) }
            ReadingProgressStore.exportAll(context).forEach { keep.add(it.first) }
        }
        var removed = 0
        for (root in AppDataDir.ebookCacheRoots(context)) {
            if (!root.isDirectory) continue
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val uri = readUri(dir) ?: return@forEach
                val missing = sourceMissing(context, uri)
                if (missing || uri !in keep) {
                    if (dir.deleteRecursively()) removed++
                }
            }
        }
        if (removed > 0) AppLog.i(TAG, "dropped stale ebook caches $removed")
    }

    private fun sourceMissing(context: Context, uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val path = StorageAccess.resolveFilePath(context, parsed) ?: return false
        return !File(path).exists()
    }

    private fun readUri(dir: File): String? {
        val src = File(dir, "source_uri.txt")
        if (src.isFile && src.length() in 1..8000) {
            val text = runCatching { src.readText().trim() }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        val names = listOf(
            "chapter_index_v1.json",
            "mobi_chapter_index_v1.json",
            "chapter_index.json",
        )
        for (name in names) {
            val f = File(dir, name)
            if (!f.isFile || f.length() > 512 * 1024) continue
            val head = readHead(f, 8000) ?: continue
            val uri = jsonUri(head)
            if (!uri.isNullOrBlank()) return uri
        }
        return null
    }

    private fun jsonUri(head: String): String? {
        val m = Regex(""""uri"\s*:\s*"((?:\\.|[^"\\])*)"""").find(head) ?: return null
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    private fun readHead(f: File, max: Int): String? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val n = minOf(max.toLong(), raf.length()).toInt().coerceAtLeast(0)
            if (n <= 0) return@use ""
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun deleteFile(f: File): Long {
        if (!f.isFile) return 0L
        val n = f.length()
        return if (f.delete()) n else 0L
    }

    private fun deleteTree(dir: File): Long {
        if (!dir.exists()) return 0L
        var n = 0L
        dir.walkBottomUp().forEach { f ->
            if (f.isFile) n += f.length()
            f.delete()
        }
        return n
    }
}
