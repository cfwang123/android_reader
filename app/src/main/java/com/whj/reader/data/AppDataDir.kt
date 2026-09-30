package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.whj.reader.util.AppLog
import java.io.File

/**
 * 应用产生的文件放哪。
 * 未绑定外部目录时用应用私有目录；绑定后用该目录，并把原来的文件转过去。
 * 绑定目录暂时读写失败时先退回本地，并记一次「请重新绑定」。
 */
object AppDataDir {

    const val EXTRA_PICK = "extra_pick_external_data"

    private const val TAG = "AppDataDir"
    private val lock = Any()

    @Volatile private var probedKey: String? = null
    @Volatile private var probedOk = false
    @Volatile private var probedAt = 0L
    /** 延迟确认后仍然不可写，才改走本地并允许提示重新绑定。 */
    @Volatile private var confirmedDead = false
    @Volatile private var promptedThisProcess = false

    private val relativeDirs = listOf(
        "covers",
        "fonts",
        "bg",
        "pdf_ocr",
        "pdf_outline_cache",
        "linked_tree_cache",
        "tts_export",
        "books",
        "cache/epub",
        "cache/mobi",
    )

    fun files(ctx: Context, name: String): File =
        place(ctx, name, File(ctx.filesDir, name))

    fun ebookCache(ctx: Context, kind: String): File =
        place(ctx, "cache/$kind", File(ctx.cacheDir, "ebooks/$kind"))

    fun externalKind(ctx: Context, name: String): File {
        val root = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return place(ctx, name, File(root, name))
    }

    /** 本地缓存和已绑定目录下的 epub/mobi 缓存根（不创建目录）。 */
    fun ebookCacheRoots(ctx: Context): List<File> {
        val out = ArrayList<File>(4)
        out.add(File(ctx.cacheDir, "ebooks/epub"))
        out.add(File(ctx.cacheDir, "ebooks/mobi"))
        val bound = AppSettings.externalDataPath(ctx)
        if (bound.isNotBlank()) {
            out.add(File(bound, "cache/epub"))
            out.add(File(bound, "cache/mobi"))
        }
        return out.distinctBy { it.absolutePath }
    }

    fun writeSourceUri(ctx: Context, dir: File, uri: String) {
        if (uri.isBlank()) return
        runCatching {
            val f = File(dir, "source_uri.txt")
            if (f.isFile && f.length() < 8000 && f.readText() == uri) return
            f.writeText(uri)
        }.onFailure {
            noteIoFailure(ctx, dir)
        }
    }

    fun probeWritable(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val probe = File(dir, ".whj_write_probe")
        return try {
            probe.writeText("ok")
            val ok = probe.readText() == "ok"
            probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 当前要用的绑定根。未绑定返回 null。
     * 刚启动时外部存储可能还不可写，这时仍返回已绑定路径，避免误判后改写到本地。
     * 只有延迟确认仍然失败后才返回 null，调用方暂时用本地。
     */
    fun boundRoot(ctx: Context): File? {
        val path = AppSettings.externalDataPath(ctx)
        if (path.isBlank()) return null
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (probedKey == path && probedOk) return File(path)
            if (confirmedDead && probedKey == path && !probedOk && now - probedAt < 5_000L) {
                return null
            }
            if (!confirmedDead && probedKey == path && !probedOk && now - probedAt < 1_000L) {
                return File(path)
            }
        }
        if (!storageSettled(path)) return File(path)
        val ok = probeWritable(File(path))
        synchronized(lock) {
            probedKey = path
            probedOk = ok
            probedAt = SystemClock.elapsedRealtime()
            if (ok) confirmedDead = false
        }
        if (ok || !confirmedDead) return File(path)
        return null
    }

    /** 最近一次探测已经成功，界面不必再等。 */
    fun hasFreshOkProbe(ctx: Context): Boolean {
        val path = AppSettings.externalDataPath(ctx)
        if (path.isBlank()) return true
        val now = SystemClock.elapsedRealtime()
        return probedKey == path && probedOk && now - probedAt < 30_000L
    }

    /**
     * 启动约一秒后再调用。存储已挂载且仍然写不了才返回 true，每个进程只提示一次。
     */
    fun confirmRebindNeeded(ctx: Context): Boolean {
        val path = AppSettings.externalDataPath(ctx)
        if (path.isBlank() || promptedThisProcess) return false
        if (!storageSettled(path)) return false
        val ok = probeWritable(File(path))
        synchronized(lock) {
            probedKey = path
            probedOk = ok
            probedAt = SystemClock.elapsedRealtime()
            if (ok) {
                confirmedDead = false
                return false
            }
            confirmedDead = true
            promptedThisProcess = true
            return true
        }
    }

    fun bind(ctx: Context, dir: File, treeUri: String, done: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                val old = AppSettings.externalDataPath(ctx)
                if (old.isNotBlank() && !sameFile(File(old), dir)) {
                    moveRelative(ctx, File(old), dir)
                }
                moveLocalInto(ctx, dir)
                AppSettings.setExternalDataDir(ctx, dir.absolutePath, treeUri)
                invalidateProbe()
                true
            }.getOrElse {
                AppLog.e(TAG, "bind failed", it)
                false
            }
            Handler(Looper.getMainLooper()).post { done(ok) }
        }.apply {
            name = "external-data-bind"
            isDaemon = true
            start()
        }
    }

    /** 解除绑定：把目录里的应用数据移回本地，之后继续写本地。 */
    fun unbind(ctx: Context, done: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                val old = AppSettings.externalDataPath(ctx)
                if (old.isNotBlank()) {
                    moveBoundBack(ctx, File(old))
                }
                AppSettings.clearExternalDataDir(ctx)
                invalidateProbe()
                true
            }.getOrElse {
                AppLog.e(TAG, "unbind failed", it)
                false
            }
            Handler(Looper.getMainLooper()).post { done(ok) }
        }.apply {
            name = "external-data-unbind"
            isDaemon = true
            start()
        }
    }

    private fun place(ctx: Context, relative: String, local: File): File {
        val root = boundRoot(ctx)
        if (root == null) {
            if (!local.isDirectory) local.mkdirs()
            return local
        }
        val target = File(root, relative)
        if (ensureDir(target)) return target
        if (!storageSettled(root.absolutePath) || probeWritable(root)) {
            if (ensureDir(target)) return target
            return target
        }
        markSoftFailure(root.absolutePath)
        if (!confirmedDead) return target
        if (!local.isDirectory) local.mkdirs()
        return local
    }

    private fun ensureDir(dir: File): Boolean {
        if (dir.isDirectory) return true
        if (dir.mkdirs()) return true
        return dir.isDirectory
    }

    private fun storageSettled(path: String): Boolean {
        val state = runCatching { Environment.getExternalStorageState(File(path)) }
            .getOrDefault(Environment.MEDIA_UNKNOWN)
        return state == Environment.MEDIA_MOUNTED ||
            state == Environment.MEDIA_MOUNTED_READ_ONLY
    }

    private fun noteIoFailure(ctx: Context, dir: File) {
        val path = AppSettings.externalDataPath(ctx)
        if (path.isBlank() || !storageSettled(path)) return
        val abs = dir.absolutePath
        if (abs != path && !abs.startsWith(path + File.separator)) return
        if (probeWritable(File(path))) return
        markSoftFailure(path)
    }

    private fun markSoftFailure(path: String) {
        synchronized(lock) {
            probedKey = path
            probedOk = false
            probedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun invalidateProbe() {
        synchronized(lock) {
            probedKey = null
            probedOk = false
            probedAt = 0L
            confirmedDead = false
            promptedThisProcess = false
        }
    }

    private fun moveLocalInto(ctx: Context, destRoot: File) {
        val ext = ctx.getExternalFilesDir(null)
        val pairs = ArrayList<Pair<File, String>>()
        pairs.add(File(ctx.filesDir, "covers") to "covers")
        pairs.add(File(ctx.filesDir, "fonts") to "fonts")
        pairs.add(File(ctx.filesDir, "bg") to "bg")
        pairs.add(File(ctx.filesDir, "pdf_ocr") to "pdf_ocr")
        pairs.add(File(ctx.filesDir, "pdf_outline_cache") to "pdf_outline_cache")
        pairs.add(File(ctx.filesDir, "linked_tree_cache") to "linked_tree_cache")
        pairs.add(File(ctx.cacheDir, "ebooks/epub") to "cache/epub")
        pairs.add(File(ctx.cacheDir, "ebooks/mobi") to "cache/mobi")
        if (ext != null) {
            pairs.add(File(ext, "books") to "books")
            pairs.add(File(ext, "tts_export") to "tts_export")
        }
        for ((src, rel) in pairs) {
            val dest = File(destRoot, rel)
            if (rel == "books") relocateBooks(ctx, src, dest) else moveTree(src, dest)
        }
    }

    private fun moveRelative(ctx: Context, srcRoot: File, destRoot: File) {
        if (sameFile(srcRoot, destRoot)) return
        for (rel in relativeDirs) {
            val src = File(srcRoot, rel)
            val dest = File(destRoot, rel)
            if (rel == "books") relocateBooks(ctx, src, dest) else moveTree(src, dest)
        }
    }

    private fun moveBoundBack(ctx: Context, bound: File) {
        val ext = ctx.getExternalFilesDir(null)
        moveTree(File(bound, "covers"), File(ctx.filesDir, "covers"))
        moveTree(File(bound, "fonts"), File(ctx.filesDir, "fonts"))
        moveTree(File(bound, "bg"), File(ctx.filesDir, "bg"))
        moveTree(File(bound, "pdf_ocr"), File(ctx.filesDir, "pdf_ocr"))
        moveTree(File(bound, "pdf_outline_cache"), File(ctx.filesDir, "pdf_outline_cache"))
        moveTree(File(bound, "linked_tree_cache"), File(ctx.filesDir, "linked_tree_cache"))
        moveTree(File(bound, "cache/epub"), File(ctx.cacheDir, "ebooks/epub"))
        moveTree(File(bound, "cache/mobi"), File(ctx.cacheDir, "ebooks/mobi"))
        val booksLocal = if (ext != null) File(ext, "books") else File(ctx.filesDir, "books")
        val ttsLocal = if (ext != null) File(ext, "tts_export") else File(ctx.filesDir, "tts_export")
        relocateBooks(ctx, File(bound, "books"), booksLocal)
        moveTree(File(bound, "tts_export"), ttsLocal)
    }

    private fun relocateBooks(ctx: Context, src: File, dest: File) {
        if (!src.isDirectory || sameFile(src, dest)) return
        dest.mkdirs()
        val children = src.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                moveTree(child, File(dest, child.name))
                continue
            }
            val target = File(dest, child.name)
            val oldUri = Uri.fromFile(child).toString()
            if (target.exists()) {
                child.delete()
                continue
            }
            if (moveFile(child, target)) {
                AppBooksCopyCleaner.retarget(ctx, oldUri, Uri.fromFile(target).toString())
            }
        }
        deleteIfEmpty(src)
    }

    private fun moveTree(src: File, dest: File) {
        if (!src.exists() || sameFile(src, dest)) return
        if (src.isFile) {
            if (dest.exists()) src.delete() else moveFile(src, dest)
            return
        }
        if (!dest.isDirectory && !dest.mkdirs()) return
        src.listFiles()?.forEach { child ->
            moveTree(child, File(dest, child.name))
        }
        deleteIfEmpty(src)
    }

    private fun moveFile(src: File, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        if (src.renameTo(dest)) return true
        return runCatching {
            src.copyTo(dest, overwrite = false)
            src.delete()
            dest.isFile
        }.getOrDefault(false)
    }

    private fun sameFile(a: File, b: File): Boolean {
        val ap = runCatching { a.canonicalPath }.getOrDefault(a.absolutePath)
        val bp = runCatching { b.canonicalPath }.getOrDefault(b.absolutePath)
        return ap == bp
    }

    private fun deleteIfEmpty(dir: File) {
        if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete()
    }
}
