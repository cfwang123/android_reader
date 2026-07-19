package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap

object BookFileType {
    fun isPdf(nameOrPath: String?): Boolean {
        if (nameOrPath.isNullOrBlank()) return false
        val n = nameOrPath.lowercase()
        return n.endsWith(".pdf") || n.contains(".pdf?")
    }

    fun isTxt(nameOrPath: String?): Boolean {
        if (nameOrPath.isNullOrBlank()) return false
        val n = nameOrPath.lowercase()
        return n.endsWith(".txt")
    }

    fun isPdfUri(context: Context, uri: Uri, displayName: String? = null): Boolean {
        if (isPdf(displayName) || isPdf(uri.lastPathSegment) || isPdf(uri.toString())) {
            return true
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null && mime.equals("application/pdf", ignoreCase = true)) {
            return true
        }
        // 部分机型 MIME 为空，再试扩展名映射
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return ext.equals("pdf", ignoreCase = true)
    }

    fun stripBookExt(name: String): String =
        name
            .removeSuffix(".txt").removeSuffix(".TXT")
            .removeSuffix(".pdf").removeSuffix(".PDF")
}
