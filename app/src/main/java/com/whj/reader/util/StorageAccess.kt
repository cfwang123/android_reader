package com.whj.reader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream

/**
 * 全盘可读（MANAGE_EXTERNAL_STORAGE）与外部打开书目持久化。
 *
 * 从文件管理器 ACTION_VIEW 传入的 content:// 往往只有一次性授权，
 * 无法 takePersistable 时会复制到应用目录，保证书架可再次打开。
 */
object StorageAccess {

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun manageAllFilesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun tryTakePersistableRead(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 当前是否可读 */
    fun canRead(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 确保书架上的 URI 长期可读：
     * 1) 尝试持久授权
     * 2) 若已有全盘权限且可解析真实路径，改为 file://
     * 3) 否则复制到应用专属目录 books/
     *
     * @return 可写入书架的 uri 字符串
     */
    fun ensurePersistentReadable(
        context: Context,
        uri: Uri,
        displayName: String?,
    ): String {
        tryTakePersistableRead(context, uri)
        if (canRead(context, uri)) {
            // 有全盘权限时尽量落到 file 路径，避免 content 授权过期
            if (hasAllFilesAccess()) {
                resolveFilePath(context, uri)?.let { path ->
                    val f = File(path)
                    if (f.isFile && f.canRead()) {
                        return Uri.fromFile(f).toString()
                    }
                }
            }
            // 仍用原 uri（已持久授权或当前会话可读）
            if (uri.scheme == "file" || isPersistableHeld(context, uri)) {
                return uri.toString()
            }
            // content 且未持久授权：复制一份
            return copyToAppBooks(context, uri, displayName) ?: uri.toString()
        }
        // 当前不可读：尝试真实路径 / 复制
        if (hasAllFilesAccess()) {
            resolveFilePath(context, uri)?.let { path ->
                val f = File(path)
                if (f.isFile && f.canRead()) return Uri.fromFile(f).toString()
            }
        }
        return copyToAppBooks(context, uri, displayName) ?: uri.toString()
    }

    private fun isPersistableHeld(context: Context, uri: Uri): Boolean {
        val list = context.contentResolver.persistedUriPermissions
        return list.any { it.uri == uri && it.isReadPermission }
    }

    private fun copyToAppBooks(context: Context, uri: Uri, displayName: String?): String? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "books").apply { mkdirs() }
            val name = sanitizeFileName(
                displayName
                    ?: queryDisplayName(context, uri)
                    ?: "book_${System.currentTimeMillis()}",
            )
            var dest = File(dir, name)
            if (dest.exists()) {
                val stem = dest.nameWithoutExtension
                val ext = dest.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                dest = File(dir, "${stem}_${System.currentTimeMillis() % 100000}$ext")
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(dest).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return n.ifBlank { "book.bin" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    /**
     * 尽力解析 content/file URI 为绝对路径（全盘权限下可直接读）。
     */
    fun resolveFilePath(context: Context, uri: Uri): String? {
        when (uri.scheme?.lowercase()) {
            "file" -> return uri.path
            "content" -> {
                // DocumentProvider primary storage
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    if (docId != null) {
                        // raw:/storage/...
                        if (docId.startsWith("raw:")) {
                            return docId.removePrefix("raw:")
                        }
                        // primary:Download/a.pdf
                        val split = docId.split(":", limit = 2)
                        if (split.size == 2) {
                            val type = split[0]
                            val rel = split[1]
                            if (type.equals("primary", ignoreCase = true)) {
                                return "${Environment.getExternalStorageDirectory()}/$rel"
                            }
                            // 外置卡：/storage/<uuid>/...
                            val ext = File("/storage/$type/$rel")
                            if (ext.exists()) return ext.absolutePath
                        }
                    }
                }
                // _data 列（部分文件管理器）
                runCatching {
                    context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                        val i = c.getColumnIndex("_data")
                        if (i >= 0 && c.moveToFirst()) {
                            val p = c.getString(i)
                            if (!p.isNullOrBlank()) return p
                        }
                    }
                }
            }
        }
        return null
    }
}
