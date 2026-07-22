package com.whj.reader.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 清除单本书的**全部**本地阅读记录与缓存（保留书架条目与源文件）。
 */
object BookLocalDataCleaner {
    private const val TAG = "BookLocalDataCleaner"

    fun clear(ctx: Context, uri: String) {
        if (uri.isBlank()) return
        Log.i(TAG, "clear ALL local data for ${uri.take(160)}")

        // 进度与历史（先清进度 store，再清书架字段）
        ReadingProgressStore.remove(ctx, uri)
        RecentStore.remove(ctx, uri)
        BookmarkStore.removeAllForFile(ctx, uri)
        // lastOpened=0 + lastParagraph=0，进度条与「上次阅读」一并更新
        BookshelfStore.clearLastOpenedForUri(ctx, uri)

        // PDF
        PdfOcrCacheStore.removeBook(ctx, uri)
        PdfOutlineCache.remove(ctx, uri)
        AppSettings.clearPdfViewState(ctx, uri)
        clearPdfOcrDebug(ctx)

        // TXT / MOBI 漫画视图
        AppSettings.clearTxtProgress(ctx, uri)
        AppSettings.clearMangaViewState(ctx, uri)
        BookEncodingStore.set(ctx, uri, null)
        BookChineseModeStore.set(ctx, uri, ChineseConvert.Mode.OFF)
        BookChapterPatternStore.clear(ctx, uri)

        // EPUB / MOBI 解析与章节缓存（禁止整文件 readText 大 bin，防 OOM 闪退）
        clearEbookParseCaches(ctx, uri)

        // 封面与元数据（页数/大小 → 书架副标题刷新）
        runCatching { CoverStore.fileFor(ctx, uri).delete() }
        ShelfFileMetaStore.remove(ctx, uri)

        Log.i(TAG, "clear done")
    }

    private fun clearEbookParseCaches(ctx: Context, uri: String) {
        val roots = listOf(
            File(ctx.cacheDir, "ebooks/epub"),
            File(ctx.cacheDir, "ebooks/mobi"),
        )
        val uriBytes = uri.toByteArray(Charsets.UTF_8)
        val nameHints = listOf(
            "",
            "book",
            uri.substringAfterLast('/').substringAfterLast(':').take(80),
        )
        for (root in roots) {
            if (!root.isDirectory) continue
            // 精确 key：md5(uri|name).take(16)
            for (name in nameHints) {
                val key = md5Hex("$uri|$name").take(16)
                val dir = File(root, key)
                if (dir.isDirectory) {
                    Log.i(TAG, "rm ebook cache key=$key")
                    runCatching { dir.deleteRecursively() }
                }
            }
            // 扫描：只读小 json 头，禁止加载大 bin
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                if (dirLooksLikeUri(dir, uri, uriBytes)) {
                    Log.i(TAG, "rm ebook cache scan ${dir.name}")
                    runCatching { dir.deleteRecursively() }
                }
            }
        }
    }

    private fun dirLooksLikeUri(dir: File, uri: String, uriBytes: ByteArray): Boolean {
        val smallJson = listOf(
            File(dir, "chapter_index_v1.json"),
            File(dir, "chapter_index.json"),
        )
        for (f in smallJson) {
            if (!f.isFile || f.length() > 512 * 1024) continue
            val head = readHeadString(f, 8000) ?: continue
            if (head.contains(uri)) return true
        }
        // spine 旁小文件
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.length() > 64 * 1024) return@forEach
            if (f.extension !in setOf("json", "txt", "meta")) return@forEach
            val head = readHeadString(f, 4000) ?: return@forEach
            if (head.contains(uri)) return true
        }
        // 二进制头里偶然含 uri 字符串（限 4KB）
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.length() > 8L * 1024 * 1024) return@forEach
            if (f.extension != "bin") return@forEach
            if (headContainsBytes(f, uriBytes, maxRead = 4096)) return true
        }
        return false
    }

    private fun readHeadString(f: File, max: Int): String? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val n = minOf(max.toLong(), raf.length()).toInt().coerceAtLeast(0)
            if (n <= 0) return@use ""
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun headContainsBytes(f: File, needle: ByteArray, maxRead: Int): Boolean {
        if (needle.isEmpty()) return false
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val n = minOf(maxRead.toLong(), raf.length()).toInt()
                if (n < needle.size) return@use false
                val buf = ByteArray(n)
                raf.readFully(buf)
                outer@ for (i in 0..buf.size - needle.size) {
                    for (j in needle.indices) {
                        if (buf[i + j] != needle[j]) continue@outer
                    }
                    return@use true
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun clearPdfOcrDebug(ctx: Context) {
        runCatching {
            File(ctx.filesDir, "pdf_ocr_debug").deleteRecursively()
        }
    }

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
