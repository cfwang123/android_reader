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

    fun isEpub(nameOrPath: String?): Boolean {
        if (nameOrPath.isNullOrBlank()) return false
        val n = nameOrPath.lowercase()
        return n.endsWith(".epub") || n.contains(".epub?")
    }

    fun isMobi(nameOrPath: String?): Boolean {
        if (nameOrPath.isNullOrBlank()) return false
        val n = nameOrPath.lowercase()
        return n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3") ||
            n.endsWith(".prc") ||
            n.contains(".mobi?") || n.contains(".azw?")
    }

    /** 走 TXT 阅读页（VirtualReaderView）的电子书：txt/epub/mobi */
    fun isStreamBook(nameOrPath: String?): Boolean =
        isTxt(nameOrPath) || isEpub(nameOrPath) || isMobi(nameOrPath)

    fun isPdfUri(context: Context, uri: Uri, displayName: String? = null): Boolean {
        if (isPdf(displayName) || isPdf(uri.lastPathSegment) || isPdf(uri.toString())) {
            return true
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null && mime.equals("application/pdf", ignoreCase = true)) {
            return true
        }
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return ext.equals("pdf", ignoreCase = true)
    }

    fun isEpubUri(context: Context, uri: Uri, displayName: String? = null): Boolean {
        if (isEpub(displayName) || isEpub(uri.lastPathSegment) || isEpub(uri.toString())) {
            return true
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null && (
                mime.equals("application/epub+zip", ignoreCase = true) ||
                    mime.equals("application/epub", ignoreCase = true)
                )
        ) {
            return true
        }
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return ext.equals("epub", ignoreCase = true)
    }

    fun isMobiUri(context: Context, uri: Uri, displayName: String? = null): Boolean {
        if (isMobi(displayName) || isMobi(uri.lastPathSegment) || isMobi(uri.toString())) {
            return true
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null && (
                mime.contains("mobi", ignoreCase = true) ||
                    mime.contains("kindle", ignoreCase = true) ||
                    mime.equals("application/x-mobipocket-ebook", ignoreCase = true)
                )
        ) {
            return true
        }
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return ext.equals("mobi", ignoreCase = true) ||
            ext.equals("azw", ignoreCase = true) ||
            ext.equals("azw3", ignoreCase = true) ||
            ext.equals("prc", ignoreCase = true)
    }

    fun stripBookExt(name: String): String =
        name
            .removeSuffix(".txt").removeSuffix(".TXT")
            .removeSuffix(".pdf").removeSuffix(".PDF")
            .removeSuffix(".epub").removeSuffix(".EPUB")
            .removeSuffix(".mobi").removeSuffix(".MOBI")
            .removeSuffix(".azw3").removeSuffix(".AZW3")
            .removeSuffix(".azw").removeSuffix(".AZW")
            .removeSuffix(".prc").removeSuffix(".PRC")

    /** 展示用扩展名（小写，含点）；无法判断时 null */
    fun extensionOf(nameOrPath: String?): String? {
        if (nameOrPath.isNullOrBlank()) return null
        return when {
            isPdf(nameOrPath) -> ".pdf"
            isEpub(nameOrPath) -> ".epub"
            isMobi(nameOrPath) -> {
                val n = nameOrPath.lowercase()
                when {
                    n.endsWith(".azw3") -> ".azw3"
                    n.endsWith(".azw") -> ".azw"
                    n.endsWith(".prc") -> ".prc"
                    else -> ".mobi"
                }
            }
            isTxt(nameOrPath) -> ".txt"
            else -> null
        }
    }
}
