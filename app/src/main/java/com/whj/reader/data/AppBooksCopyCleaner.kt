package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import com.whj.reader.util.AppLog
import com.whj.reader.util.OpenFailGuide
import com.whj.reader.util.StorageAccess
import java.io.File
import java.util.ArrayDeque

/**
 * 清掉旧版复制进应用目录 `Android/data/.../files/books/` 的书。
 *
 * 书架、进度还指着副本时：在内部存储 / SD 卡上按文件名和大小找原文件，
 * 改指向原文件后再删副本。找不到原文件（例如当时从网盘复制）则保留。
 * 已经没有任何记录指向的副本直接删除。
 */
object AppBooksCopyCleaner {
    private const val TAG = "AppBooksCopyCleaner"
    private val collisionSuffix = Regex("""^(.*)_(\d{1,5})(\.[^.]+)$""")

    /** @return 删除的副本数量 */
    fun cleanup(ctx: Context): Int {
        val root = ctx.getExternalFilesDir(null) ?: return 0
        val dir = File(root, "books")
        if (!dir.isDirectory) return 0
        val copies = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (copies.isEmpty()) {
            deleteIfEmpty(File(dir, ".notes"))
            deleteIfEmpty(dir)
            return 0
        }
        var removed = 0
        val referenced = ArrayList<Pair<File, List<String>>>()
        for (copy in copies) {
            val aliases = urisPointingAt(ctx, copy)
            if (aliases.isEmpty()) {
                if (deleteCopy(copy, deleteNotes = true)) {
                    removed++
                    AppLog.i(TAG, "drop orphan ${copy.name}")
                }
            } else {
                referenced.add(copy to aliases)
            }
        }
        val originals = findOriginals(ctx, referenced.map { it.first })
        for ((copy, aliases) in referenced) {
            val original = originals[copy.absolutePath] ?: continue
            val newUri = Uri.fromFile(original).toString()
            for (oldUri in aliases) {
                retarget(ctx, oldUri, newUri)
            }
            if (deleteCopy(copy, deleteNotes = false)) {
                removed++
                AppLog.i(TAG, "drop copy ${copy.name} -> ${original.absolutePath}")
            }
        }
        deleteIfEmpty(File(dir, ".notes"))
        deleteIfEmpty(dir)
        return removed
    }

    private fun retarget(ctx: Context, oldUri: String, newUri: String) {
        OpenFailGuide.migrateBindings(ctx, oldUri, newUri)
        BookmarkStore.migrateFileKey(ctx, oldUri, newUri)
        RecentStore.migrateUri(ctx, oldUri, newUri)
        AppSettings.migrateReaderFileKey(ctx, oldUri, newUri)
        BookChapterPatternStore.migrate(ctx, oldUri, newUri)
        ShelfFileMetaStore.migrate(ctx, oldUri, newUri)
        CoverStore.migrate(ctx, oldUri, newUri)
        BookNotesFileStore.migrate(ctx, oldUri, newUri)
        PdfOcrCacheStore.migrateBook(ctx, oldUri, newUri)
    }

    private fun urisPointingAt(ctx: Context, copy: File): List<String> {
        val uris = LinkedHashSet<String>()
        BookshelfStore.books(ctx).forEach { uris.add(it.uri) }
        ReadingProgressStore.exportAll(ctx).forEach { uris.add(it.first) }
        RecentStore.list(ctx).forEach { uris.add(it.uri) }
        BookmarkStore.all(ctx).forEach { uris.add(it.fileKey) }
        AppSettings.lastBookUri(ctx)?.let { uris.add(it) }
        AppSettings.lastPdfUri(ctx)?.let { uris.add(it) }
        AppSettings.lastShelfFocus(ctx)?.uri?.let { uris.add(it) }
        return uris.filter { StorageAccess.isAppBooksCopy(ctx, it) && sameFile(it, copy) }
    }

    private fun sameFile(uriString: String, file: File): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        if (!uri.scheme.equals("file", ignoreCase = true)) return false
        val path = uri.path ?: return false
        return File(path).absolutePath == file.absolutePath
    }

    /**
     * 每个副本最多对应一个原文件。同名同大小多于一份则不猜。
     * 旧版重名时副本会变成 `书名_12345.pdf`，对不上再试去掉这截后缀。
     */
    private fun findOriginals(ctx: Context, copies: List<File>): Map<String, File> {
        val result = HashMap<String, File>()
        val needWalk = ArrayList<File>()
        for (copy in copies) {
            if (copy.length() <= 0L) continue
            when (val hit = uniqueMatch(copy, mediaStoreMatches(ctx, copy.name, copy.length()))) {
                is Match.One -> result[copy.absolutePath] = hit.file
                is Match.Many -> Unit
                Match.None -> {
                    val stripped = strippedName(copy.name)
                    val alt = if (stripped == null) {
                        Match.None
                    } else {
                        uniqueMatch(copy, mediaStoreMatches(ctx, stripped, copy.length()))
                    }
                    when (alt) {
                        is Match.One -> result[copy.absolutePath] = alt.file
                        is Match.Many -> Unit
                        Match.None -> needWalk.add(copy)
                    }
                }
            }
        }
        if (needWalk.isEmpty()) return result
        val walked = walkOriginals(ctx, needWalk)
        for (copy in needWalk) {
            val exact = walked[copy.name to copy.length()].orEmpty()
                .filter { it.absolutePath != copy.absolutePath }
            when (val hit = uniqueMatch(copy, exact)) {
                is Match.One -> result[copy.absolutePath] = hit.file
                is Match.Many -> Unit
                Match.None -> {
                    val stripped = strippedName(copy.name) ?: continue
                    val alt = walked[stripped to copy.length()].orEmpty()
                        .filter { it.absolutePath != copy.absolutePath }
                    val altHit = uniqueMatch(copy, alt)
                    if (altHit is Match.One) result[copy.absolutePath] = altHit.file
                }
            }
        }
        return result
    }

    private sealed class Match {
        data class One(val file: File) : Match()
        data object Many : Match()
        data object None : Match()
    }

    private fun uniqueMatch(copy: File, hits: List<File>): Match {
        val outside = LinkedHashMap<String, File>()
        for (hit in hits) {
            if (hit.absolutePath != copy.absolutePath && hit.length() == copy.length()) {
                outside[hit.absolutePath] = hit
            }
        }
        return when (outside.size) {
            0 -> Match.None
            1 -> Match.One(outside.values.first())
            else -> Match.Many
        }
    }

    private fun strippedName(copyName: String): String? {
        val m = collisionSuffix.matchEntire(copyName) ?: return null
        val name = m.groupValues[1] + m.groupValues[3]
        return name.takeIf { it != copyName && it.isNotBlank() }
    }

    private fun mediaStoreMatches(ctx: Context, name: String, size: Long): List<File> {
        val out = ArrayList<File>()
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?"
        val args = arrayOf(name, size.toString())
        for (uri in filesUris(ctx)) {
            val rows = runCatching {
                ctx.contentResolver.query(uri, projection, selection, args, null)
            }.getOrNull() ?: continue
            rows.use { c ->
                val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (dataIdx < 0) return@use
                while (c.moveToNext()) {
                    val path = c.getString(dataIdx)
                    val sz = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                    if (path.isNullOrBlank() || sz != size) continue
                    val file = File(path)
                    if (file.isFile && file.length() == size) out.add(file)
                }
            }
        }
        return out
    }

    private fun filesUris(ctx: Context): List<Uri> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.getExternalVolumeNames(ctx).map { MediaStore.Files.getContentUri(it) }
        }
        return listOf(MediaStore.Files.getContentUri("external"))
    }

    /** 一次扫存储：键是「文件名 + 大小」。只收录待对上的名字。 */
    private fun walkOriginals(ctx: Context, copies: List<File>): Map<Pair<String, Long>, List<File>> {
        val wanted = HashSet<Pair<String, Long>>()
        for (copy in copies) {
            wanted.add(copy.name to copy.length())
            val stripped = strippedName(copy.name)
            if (stripped != null) wanted.add(stripped to copy.length())
        }
        val found = HashMap<Pair<String, Long>, ArrayList<File>>()
        val skip = ctx.getExternalFilesDir(null)
        val stack = ArrayDeque<File>()
        for (root in storageRoots(ctx)) {
            if (root.isDirectory) stack.add(root)
        }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (!shouldSkipDir(child, skip)) stack.add(child)
                    continue
                }
                if (!child.isFile) continue
                val key = child.name to child.length()
                if (key !in wanted) continue
                found.getOrPut(key) { ArrayList() }.add(child)
            }
        }
        return found
    }

    private fun shouldSkipDir(dir: File, appFiles: File?): Boolean {
        if (appFiles != null && dir.absolutePath == appFiles.absolutePath) return true
        val parent = dir.parentFile?.name ?: return false
        if (parent == "Android" && (dir.name == "data" || dir.name == "obb")) return true
        return false
    }

    private fun storageRoots(ctx: Context): List<File> {
        val out = ArrayList<File>()
        fun add(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            val path = dir.absolutePath
            if (out.none { it.absolutePath == path }) out.add(dir)
        }
        add(Environment.getExternalStorageDirectory())
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (sm != null) {
            for (vol in sm.storageVolumes) {
                add(volumeDirectory(vol))
            }
        }
        File("/storage").listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name == "emulated" || child.name == "self") return@forEach
            add(child)
        }
        return out
    }

    private fun volumeDirectory(vol: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return vol.directory
        return runCatching {
            val path = StorageVolume::class.java.getMethod("getPath").invoke(vol) as? String
            if (path.isNullOrBlank()) null else File(path)
        }.getOrNull()
    }

    private fun deleteCopy(file: File, deleteNotes: Boolean): Boolean {
        val ok = file.delete()
        if (ok && deleteNotes) {
            val notesDir = File(file.parentFile, ".notes")
            val safe = file.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            File(notesDir, "$safe.notes.json").delete()
        }
        return ok
    }

    private fun deleteIfEmpty(dir: File) {
        if (!dir.isDirectory) return
        if (dir.listFiles()?.isEmpty() != false) dir.delete()
    }
}
