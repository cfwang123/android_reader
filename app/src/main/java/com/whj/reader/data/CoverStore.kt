package com.whj.reader.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 书架封面图：按书 URI 哈希存到 covers/（未绑定外部目录时在应用私有目录）。
 */
object CoverStore {

    fun fileFor(context: Context, bookUri: String): File {
        val dir = AppDataDir.files(context, "covers")
        return File(dir, "${hashKey(bookUri)}.jpg")
    }

    fun exists(context: Context, bookUri: String): Boolean {
        val f = fileFor(context, bookUri)
        return f.isFile && f.length() > 0L
    }

    /** 从已解压的封面文件复制到稳定路径（供书架） */
    fun saveFromFile(context: Context, bookUri: String, src: File?): File? {
        if (src == null || !src.isFile || src.length() == 0L) return null
        val dest = fileFor(context, bookUri)
        if (dest.absolutePath == src.absolutePath) return dest
        return runCatching {
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            dest.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
    }

    fun migrate(context: Context, oldUri: String, newUri: String) {
        if (oldUri.isBlank() || newUri.isBlank() || oldUri == newUri) return
        val old = fileFor(context, oldUri)
        val dest = fileFor(context, newUri)
        if (!old.isFile || old.length() == 0L || dest.isFile) return
        if (!old.renameTo(dest)) {
            runCatching {
                old.copyTo(dest, overwrite = false)
                old.delete()
            }
        }
    }

    private fun hashKey(uri: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val dig = md.digest(uri.toByteArray(Charsets.UTF_8))
        return dig.take(10).joinToString("") { b -> "%02x".format(b) }
    }
}
