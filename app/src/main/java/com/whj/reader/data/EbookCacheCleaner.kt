package com.whj.reader.data

import android.content.Context
import com.whj.reader.util.AppLog
import java.io.File

/**
 * 删掉以前为打开 EPUB/MOBI 复制进缓存的整本，以及 MOBI 提前抽出的图片。
 * 解析用的小缓存（章节索引等）留着。
 */
object EbookCacheCleaner {

    private const val TAG = "EbookCacheCleaner"

    fun dropCopiedBooks(context: Context) {
        val mobi = File(context.cacheDir, "ebooks/mobi")
        var freed = 0L
        mobi.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            freed += deleteFile(File(dir, "book.mobi"))
            freed += deleteTree(File(dir, "images"))
        }
        val epub = File(context.cacheDir, "ebooks/epub")
        epub.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            freed += deleteFile(File(dir, "book.epub"))
        }
        if (freed > 0L) {
            AppLog.i(TAG, "dropped copied books ${freed / 1024}KB")
        }
    }

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
