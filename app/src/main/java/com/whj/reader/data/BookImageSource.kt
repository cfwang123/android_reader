package com.whj.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/**
 * 书内图片不落盘。路径形如 `mobiimg:<offset>:<length>:<encodedAbsolutePath>`，
 * 显示时从原文件该段读出再解码。普通绝对路径仍走 [BitmapFactory.decodeFile]。
 */
object BookImageSource {

    private const val PREFIX = "mobiimg:"
    private const val MAX_BYTES = 48 * 1024 * 1024

    fun isRef(path: String): Boolean = path.startsWith(PREFIX)

    fun ref(absolutePath: String, offset: Long, length: Int): String {
        return PREFIX + offset + ":" + length + ":" + Uri.encode(absolutePath)
    }

    /** 原文件还在（或普通路径指向已有文件）即可显示。 */
    fun readable(path: String): Boolean {
        if (path.isBlank()) return false
        if (!isRef(path)) return File(path).isFile
        val spec = parse(path) ?: return false
        return spec.file.isFile && spec.length > 0
    }

    fun decode(path: String, opts: BitmapFactory.Options? = null): Bitmap? {
        if (!isRef(path)) return BitmapFactory.decodeFile(path, opts)
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
