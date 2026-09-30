package com.whj.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/**
 * 书内图片不落盘。
 * MOBI：`mobiimg:<offset>:<length>:<encodedAbsolutePath>`，显示时从原文件该段读出。
 * EPUB：`epubimg:<encodedAbsolutePath>:<encodedEntryName>`，显示时从 zip 条目读出。
 * 普通绝对路径仍走 [BitmapFactory.decodeFile]。
 */
object BookImageSource {

    private const val PREFIX = "mobiimg:"
    private const val EPUB_PREFIX = "epubimg:"
    private const val MAX_BYTES = 48 * 1024 * 1024

    fun isRef(path: String): Boolean =
        path.startsWith(PREFIX) || path.startsWith(EPUB_PREFIX)

    fun ref(absolutePath: String, offset: Long, length: Int): String {
        return PREFIX + offset + ":" + length + ":" + Uri.encode(absolutePath)
    }

    fun epubRef(absolutePath: String, entryName: String): String {
        return EPUB_PREFIX + Uri.encode(absolutePath) + ":" + Uri.encode(entryName)
    }

    fun epubEntryName(path: String): String? = parseEpub(path)?.entry

    /** 原文件还在（或普通路径指向已有文件）即可显示。EPUB 不打开 zip。 */
    fun readable(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith(EPUB_PREFIX)) {
            val spec = parseEpub(path) ?: return false
            return spec.file.isFile
        }
        if (!path.startsWith(PREFIX)) return File(path).isFile
        val spec = parse(path) ?: return false
        return spec.file.isFile && spec.length > 0
    }

    fun decode(path: String, opts: BitmapFactory.Options? = null): Bitmap? {
        if (path.startsWith(EPUB_PREFIX)) return decodeEpub(path, opts)
        if (!path.startsWith(PREFIX)) return BitmapFactory.decodeFile(path, opts)
        val spec = parse(path) ?: return null
        if (!spec.file.isFile || spec.length <= 0 || spec.length > MAX_BYTES) return null
        return runCatching {
            val bytes = ByteArray(spec.length)
            RandomAccessFile(spec.file, "r").use { raf ->
                raf.seek(spec.offset)
                val n = raf.read(bytes)
                if (n <= 0) return null
                BitmapFactory.decodeByteArray(bytes, 0, n, opts)
            }
        }.getOrNull()
    }

    private fun decodeEpub(path: String, opts: BitmapFactory.Options?): Bitmap? {
        val spec = parseEpub(path) ?: return null
        if (!spec.file.isFile) return null
        return runCatching {
            java.util.zip.ZipFile(spec.file).use { zip ->
                val entry = zip.getEntry(spec.entry) ?: return null
                if (entry.size > MAX_BYTES) return null
                zip.getInputStream(entry).use { input ->
                    val bytes = readLimited(input, MAX_BYTES) ?: return null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }
        }.getOrNull()
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        if (total <= 0) return null
        return out.toByteArray()
    }

    private data class EpubSpec(val file: File, val entry: String)

    private fun parseEpub(path: String): EpubSpec? {
        val body = path.removePrefix(EPUB_PREFIX)
        val p = body.indexOf(':')
        if (p <= 0 || p >= body.length - 1) return null
        val filePath = Uri.decode(body.substring(0, p))
        val entry = Uri.decode(body.substring(p + 1))
        if (filePath.isBlank() || entry.isBlank()) return null
        return EpubSpec(File(filePath), entry)
    }

    private data class Spec(val file: File, val offset: Long, val length: Int)

    private fun parse(path: String): Spec? {
        val body = path.removePrefix(PREFIX)
        val p1 = body.indexOf(':')
        if (p1 <= 0) return null
        val p2 = body.indexOf(':', p1 + 1)
        if (p2 <= p1) return null
        val offset = body.substring(0, p1).toLongOrNull() ?: return null
        val length = body.substring(p1 + 1, p2).toIntOrNull() ?: return null
        val filePath = Uri.decode(body.substring(p2 + 1))
        if (offset < 0 || length <= 0 || filePath.isBlank()) return null
        return Spec(File(filePath), offset, length)
    }
}
