package com.whj.reader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import com.whj.reader.data.AppDataDir
import com.whj.reader.data.AppSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 全盘可读（MANAGE_EXTERNAL_STORAGE）与外部打开书目持久化。
 *
 * 本机存储（内部共享存储 / SD 卡）直接读原文件，不复制进应用目录。
 * 只有解析不出真实路径、又拿不到持久授权的 content（网盘等）才复制到 books/。
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
     * 2) 能解析到本机真实路径且当前可读时，改为 file://（含 SD 卡，不复制）
     * 3) 路径已知但暂时读不了：保留原 URI，不复制
     * 4) 没有真实路径且未持久授权：才复制到应用专属目录 books/
     *
     * @return 可写入书架的 uri 字符串
     */
    fun ensurePersistentReadable(
        context: Context,
        uri: Uri,
        displayName: String?,
    ): String {
        tryTakePersistableRead(context, uri)
        readableFileUri(context, uri)?.let { return it }
        if (uri.scheme.equals("file", ignoreCase = true) || isPersistableHeld(context, uri)) {
            return uri.toString()
        }
        // SD 卡 / 内部存储路径已经能解析：即使这次只有一次性 content 授权，也不复制整本
        if (resolveFilePath(context, uri) != null) {
            return uri.toString()
        }
        if (canRead(context, uri)) {
            return copyToAppBooks(context, uri, displayName) ?: uri.toString()
        }
        return uri.toString()
    }

    /** 书架条目是否指向复制进应用目录 books/ 的副本。 */
    fun isAppBooksCopy(context: Context, uriString: String): Boolean {
        if (!uriString.startsWith("file:")) return false
        val path = Uri.parse(uriString).path ?: return false
        val abs = File(path).absolutePath
        if (underBooks(context.getExternalFilesDir(null), abs)) return true
        val bound = AppSettings.externalDataPath(context)
        if (bound.isNotBlank() && underBooks(File(bound), abs)) return true
        return false
    }

    private fun underBooks(root: File?, abs: String): Boolean {
        if (root == null) return false
        val books = File(root, "books").absolutePath
        return abs == books || abs.startsWith(books + File.separator)
    }

    /**
     * 能直接读的本机文件（内部存储 / SD 卡）。没有真实路径或当前打不开则 null。
     */
    fun readableBookFile(context: Context, uri: Uri): File? {
        val path = resolveFilePath(context, uri) ?: return null
        val file = File(path)
        return try {
            FileInputStream(file).use { }
            file
        } catch (_: Exception) {
            null
        }
    }

    /** 全盘权限下把 content/file 收成可读的 file://；读不了则返回 null。 */
    private fun readableFileUri(context: Context, uri: Uri): String? {
        if (!hasAllFilesAccess() && !uri.scheme.equals("file", ignoreCase = true)) return null
        val path = resolveFilePath(context, uri) ?: return null
        val file = File(path)
        return try {
            FileInputStream(file).use { }
            Uri.fromFile(file).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun isPersistableHeld(context: Context, uri: Uri): Boolean {
        val list = context.contentResolver.persistedUriPermissions
        return list.any { it.uri == uri && it.isReadPermission }
    }

    private fun copyToAppBooks(context: Context, uri: Uri, displayName: String?): String? {
        return try {
            val dir = AppDataDir.externalKind(context, "books")
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
     * 外置卡在未授权时 [File.exists] 常为 false，仍返回推测路径，避免被当成“没有原文件”而去复制。
     */
    fun resolveFilePath(context: Context, uri: Uri): String? {
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path
                return if (path.isNullOrBlank()) null else path
            }
            "content" -> return resolveContentFilePath(context, uri)
        }
        return null
    }

    private fun resolveContentFilePath(context: Context, uri: Uri): String? {
        val candidates = ArrayList<String>()
        documentPathCandidates(context, uri)?.let { candidates.addAll(it) }
        embeddedStoragePath(uri)?.let { candidates.add(it) }
        queryDataColumn(context, uri)?.let { candidates.add(it) }
        if (candidates.isEmpty()) return null
        for (path in candidates) {
            if (File(path).isFile) return path
        }
        return candidates.firstOrNull { looksLikeDevicePath(it) }
    }

    /**
     * 用户用目录选择器选中的文件夹。能解析成真实目录才返回。
     */
    fun resolveTreeDirectory(context: Context, uri: Uri): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return null
            return File(path).takeIf { it.isDirectory }
        }
        val docId = when {
            DocumentsContract.isTreeUri(uri) ->
                runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            DocumentsContract.isDocumentUri(context, uri) ->
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            else -> null
        } ?: return null
        val candidates = pathsFromDocumentId(context, docId) ?: return null
        for (path in candidates) {
            if (File(path).isDirectory) return File(path)
        }
        return null
    }

    private fun documentPathCandidates(context: Context, uri: Uri): List<String>? {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return pathsFromDocumentId(context, docId)
    }

    private fun pathsFromDocumentId(context: Context, docId: String): List<String>? {
        if (docId.startsWith("raw:")) return listOf(docId.removePrefix("raw:"))
        if (docId.startsWith("/")) return listOf(docId)
        val split = docId.split(":", limit = 2)
        if (split.size != 2) return null
        val type = split[0]
        val rel = split[1].trimStart('/')
        if (type.equals("primary", ignoreCase = true)) {
            return listOf("${Environment.getExternalStorageDirectory()}/$rel")
        }
        if (type.equals("home", ignoreCase = true)) {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            return listOf(File(docs, rel).absolutePath)
        }
        if (type.equals("msf", ignoreCase = true)) return msfPathCandidates(context, rel)
        val out = ArrayList<String>()
        volumeRootByUuid(context, type)?.let { out.add(File(it, rel).absolutePath) }
        out.add("/storage/$type/$rel")
        if (rel.isNotEmpty()) {
            for (root in removableRoots(context)) {
                val path = File(root, rel).absolutePath
                if (!out.contains(path)) out.add(path)
            }
        }
        return out
    }

    private fun msfPathCandidates(context: Context, id: String): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val n = id.toLongOrNull() ?: return null
        val media = android.content.ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            n,
        )
        val path = queryDataColumn(context, media) ?: return null
        return listOf(path)
    }

    /** 文件管理器把 /storage/... 嵌在 content URI 里。 */
    private fun embeddedStoragePath(uri: Uri): String? {
        val decoded = Uri.decode(uri.toString())
        val markers = arrayOf("/storage/", "/sdcard/", "/mnt/sdcard/", "/mnt/media_rw/")
        for (marker in markers) {
            val i = decoded.indexOf(marker)
            if (i < 0) continue
            var path = decoded.substring(i)
            val cut = path.indexOfAny(charArrayOf('?', '#'))
            if (cut >= 0) path = path.substring(0, cut)
            if (path.length > marker.length) return path
        }
        val ext = "/external_files/"
        val j = decoded.indexOf(ext)
        if (j >= 0) {
            var rest = decoded.substring(j + ext.length)
            val cut = rest.indexOfAny(charArrayOf('?', '#'))
            if (cut >= 0) rest = rest.substring(0, cut)
            if (rest.startsWith("storage/") || rest.startsWith("sdcard/") || rest.startsWith("mnt/")) {
                return "/$rest"
            }
            if (rest.contains("/")) return "/storage/$rest"
        }
        return null
    }

    private fun looksLikeDevicePath(path: String): Boolean {
        return path.startsWith("/storage/") ||
            path.startsWith("/sdcard") ||
            path.startsWith("/mnt/")
    }

    private fun volumeRootByUuid(context: Context, uuid: String): File? {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
        for (vol in sm.storageVolumes) {
            val id = vol.uuid ?: continue
            if (!id.equals(uuid, ignoreCase = true)) continue
            return volumeDirectory(vol)
        }
        return null
    }

    private fun removableRoots(context: Context): List<File> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        val out = ArrayList<File>()
        for (vol in sm.storageVolumes) {
            if (vol.isPrimary) continue
            val dir = volumeDirectory(vol) ?: continue
            if (!out.contains(dir)) out.add(dir)
        }
        return out
    }

    private fun volumeDirectory(vol: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return vol.directory
        }
        return runCatching {
            val path = StorageVolume::class.java.getMethod("getPath").invoke(vol) as? String
            if (path.isNullOrBlank()) null else File(path)
        }.getOrNull()
    }

    private fun queryDataColumn(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                val i = c.getColumnIndex("_data")
                if (i >= 0 && c.moveToFirst()) {
                    val p = c.getString(i)
                    if (!p.isNullOrBlank()) p else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
