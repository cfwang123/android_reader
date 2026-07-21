package com.whj.reader.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 自定义字体（TTF/OTF）安装与元数据。
 * 文件：filesDir/fonts/；元数据：SharedPreferences JSON。
 */
object CustomFontStore {

    data class Entry(
        val id: String,
        val name: String,
        val fileName: String,
    )

    sealed class InstallResult {
        data class Ok(val entry: Entry) : InstallResult()
        data class Fail(val reason: FailReason) : InstallResult()
    }

    enum class FailReason {
        BAD_FORMAT,
        TOO_LARGE,
        LIMIT,
        IO,
        INVALID_FONT,
    }

    private const val PREF = "custom_fonts"
    private const val KEY_LIST = "list"
    private const val DIR = "fonts"
    const val MAX_COUNT = 20
    private const val MAX_BYTES = 30L * 1024 * 1024

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun fontsDir(ctx: Context): File =
        File(ctx.filesDir, DIR).also { it.mkdirs() }

    fun list(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    val name = o.optString("name", "")
                    val fileName = o.optString("fileName", "")
                    if (id.isBlank() || fileName.isBlank()) continue
                    add(Entry(id = id, name = name.ifBlank { fileName }, fileName = fileName))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun find(ctx: Context, id: String): Entry? =
        list(ctx).firstOrNull { it.id == id }

    fun fileFor(ctx: Context, entry: Entry): File =
        File(fontsDir(ctx), entry.fileName)

    fun fileForId(ctx: Context, id: String): File? {
        val e = find(ctx, id) ?: return null
        val f = fileFor(ctx, e)
        return f.takeIf { it.isFile }
    }

    /**
     * 从 content URI 安装字体。应在后台线程调用。
     */
    fun installFromUri(ctx: Context, uri: Uri): InstallResult {
        if (list(ctx).size >= MAX_COUNT) {
            return InstallResult.Fail(FailReason.LIMIT)
        }
        val display = queryDisplayName(ctx, uri)
        val ext = resolveExtension(ctx, display, uri)
        if (ext == null) {
            return InstallResult.Fail(FailReason.BAD_FORMAT)
        }
        val size = querySize(ctx, uri)
        if (size > MAX_BYTES) {
            return InstallResult.Fail(FailReason.TOO_LARGE)
        }

        val uuid = UUID.randomUUID().toString().replace("-", "").take(12)
        val id = "custom:$uuid"
        val fileName = "$uuid.$ext"
        val dest = File(fontsDir(ctx), fileName)

        try {
            val copied = ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            dest.delete()
                            return InstallResult.Fail(FailReason.TOO_LARGE)
                        }
                        output.write(buf, 0, n)
                    }
                    total
                }
            } ?: return InstallResult.Fail(FailReason.IO)

            if (copied <= 0L || !dest.isFile) {
                dest.delete()
                return InstallResult.Fail(FailReason.IO)
            }

            val tf = runCatching { Typeface.createFromFile(dest) }.getOrNull()
            if (tf == null) {
                dest.delete()
                return InstallResult.Fail(FailReason.INVALID_FONT)
            }

            val name = displayNameFrom(display, list(ctx).size + 1)
            val entry = Entry(id = id, name = name, fileName = fileName)
            saveList(ctx, list(ctx) + entry)
            return InstallResult.Ok(entry)
        } catch (_: Exception) {
            dest.delete()
            return InstallResult.Fail(FailReason.IO)
        }
    }

    fun delete(ctx: Context, id: String): Boolean {
        val entries = list(ctx)
        val target = entries.firstOrNull { it.id == id } ?: return false
        runCatching { fileFor(ctx, target).delete() }
        saveList(ctx, entries.filter { it.id != id })
        return true
    }

    private fun saveList(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("fileName", e.fileName),
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        return runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun querySize(ctx: Context, uri: Uri): Long {
        val fromCol = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
            }
        }.getOrNull() ?: -1L
        if (fromCol >= 0L) return fromCol
        return runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun resolveExtension(ctx: Context, display: String?, uri: Uri): String? {
        fun fromName(n: String?): String? {
            if (n.isNullOrBlank()) return null
            val lower = n.substringAfterLast('/', n).lowercase()
            return when {
                lower.endsWith(".ttf") -> "ttf"
                lower.endsWith(".otf") -> "otf"
                else -> null
            }
        }
        fromName(display)?.let { return it }
        fromName(uri.lastPathSegment)?.let { return it }
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()?.lowercase()
        return when {
            mime == null -> null
            mime.contains("ttf") || mime == "font/ttf" || mime == "application/x-font-ttf" -> "ttf"
            mime.contains("otf") || mime == "font/otf" || mime == "application/x-font-opentype" -> "otf"
            else -> null
        }
    }

    private fun displayNameFrom(display: String?, index: Int): String {
        val raw = display
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "Font$index" }.take(40)
    }
}
