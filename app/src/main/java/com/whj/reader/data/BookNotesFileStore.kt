package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.model.BookNotesDocument
import com.whj.reader.model.Highlight
import com.whj.reader.model.HighlightKind
import com.whj.reader.model.HighlightMode
import com.whj.reader.model.HighlightStyle
import com.whj.reader.model.TextAnchor
import com.whj.reader.model.UnderlineShape
import com.whj.reader.util.StorageAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔记只写在书旁边：`.notes/{书名}.notes.json`。
 * 写不了时不放进应用目录，由界面提示授权或说明文件夹不可写。
 */
object BookNotesFileStore {

    private const val NOTES_DIR = ".notes"
    private const val SUFFIX = ".notes.json"

    enum class SaveResult { OK, NEED_PERMISSION, NOT_WRITABLE }

    data class Location(
        val file: File,
    )

    fun resolveLocation(ctx: Context, bookUri: String): Location? {
        val book = bookFile(ctx, bookUri) ?: return null
        return sidecarFor(book)
    }

    fun load(ctx: Context, bookUri: String): BookNotesDocument {
        if (bookUri.isBlank()) {
            return BookNotesDocument(bookUri = bookUri, highlights = emptyList())
        }
        val loc = resolveLocation(ctx, bookUri)
        if (loc == null || !loc.file.isFile) {
            return BookNotesDocument(bookUri = bookUri, highlights = emptyList())
        }
        return runCatching {
            parse(loc.file.readText(Charsets.UTF_8), bookUri)
        }.getOrElse {
            BookNotesDocument(bookUri = bookUri, highlights = emptyList())
        }
    }

    fun save(ctx: Context, doc: BookNotesDocument): SaveResult {
        val loc = resolveLocation(ctx, doc.bookUri)
            ?: return if (StorageAccess.hasAllFilesAccess()) {
                SaveResult.NOT_WRITABLE
            } else {
                SaveResult.NEED_PERMISSION
            }
        val parent = loc.file.parentFile ?: return SaveResult.NOT_WRITABLE
        if (!parent.isDirectory && !parent.mkdirs()) {
            return if (StorageAccess.hasAllFilesAccess()) {
                SaveResult.NOT_WRITABLE
            } else {
                SaveResult.NEED_PERMISSION
            }
        }
        return try {
            val json = serialize(doc)
            val tmp = File(parent, loc.file.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (loc.file.exists()) loc.file.delete()
            if (!tmp.renameTo(loc.file)) {
                tmp.copyTo(loc.file, overwrite = true)
                tmp.delete()
            }
            if (loc.file.isFile) SaveResult.OK else failure()
        } catch (_: Exception) {
            failure()
        }
    }

    /** 副本 URI 换成原文件后，笔记文件跟着走。 */
    fun migrate(ctx: Context, oldUri: String, newUri: String) {
        if (oldUri.isBlank() || newUri.isBlank() || oldUri == newUri) return
        val oldLoc = resolveLocation(ctx, oldUri) ?: return
        val newLoc = resolveLocation(ctx, newUri) ?: return
        if (!oldLoc.file.isFile || newLoc.file.isFile) return
        if (oldLoc.file.absolutePath == newLoc.file.absolutePath) return
        newLoc.file.parentFile?.mkdirs()
        if (!oldLoc.file.renameTo(newLoc.file)) {
            runCatching {
                oldLoc.file.copyTo(newLoc.file, overwrite = false)
                oldLoc.file.delete()
            }
        }
    }

    fun deleteAll(ctx: Context, bookUri: String) {
        val loc = resolveLocation(ctx, bookUri) ?: return
        if (loc.file.isFile && loc.file.delete()) return
        save(ctx, BookNotesDocument(bookUri = bookUri, highlights = emptyList()))
    }

    private fun bookFile(ctx: Context, bookUri: String): File? {
        val uri = runCatching { Uri.parse(bookUri) }.getOrNull() ?: return null
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val book = File(uri.path.orEmpty())
                if (book.parentFile != null) return book
            }
            "content" -> {
                StorageAccess.resolveFilePath(ctx, uri)?.let { path ->
                    val book = File(path)
                    if (book.parentFile != null) return book
                }
            }
        }
        return null
    }

    private fun failure(): SaveResult =
        if (StorageAccess.hasAllFilesAccess()) SaveResult.NOT_WRITABLE
        else SaveResult.NEED_PERMISSION

    private fun sidecarFor(book: File): Location {
        val notesDir = File(book.parentFile, NOTES_DIR)
        val safeName = book.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return Location(File(notesDir, "$safeName$SUFFIX"))
    }

    private fun serialize(doc: BookNotesDocument): String {
        val root = JSONObject()
            .put("version", doc.version)
            .put("bookUri", doc.bookUri)
        val arr = JSONArray()
        doc.highlights.forEach { h ->
            arr.put(serializeHighlight(h))
        }
        root.put("highlights", arr)
        return root.toString(2)
    }

    private fun serializeHighlight(h: Highlight): JSONObject {
        val anchor = JSONObject()
            .put("startParagraph", h.anchor.startParagraph)
            .put("startOffset", h.anchor.startOffset)
            .put("endParagraph", h.anchor.endParagraph)
            .put("endOffset", h.anchor.endOffset)
        val style = JSONObject()
            .put("mode", h.style.mode.name)
            .put("underlineShape", h.style.underlineShape.name)
            .put("colorArgb", h.style.colorArgb)
            .put("opacity", h.style.opacity)
        return JSONObject()
            .put("id", h.id)
            .put("kind", h.kind.name)
            .put("anchor", anchor)
            .put("selectedText", h.selectedText)
            .put("note", h.note)
            .put("style", style)
            .put("createdAt", h.createdAt)
            .put("updatedAt", h.updatedAt)
    }

    private fun parse(raw: String, fallbackUri: String): BookNotesDocument {
        val root = JSONObject(raw)
        val uri = root.optString("bookUri", fallbackUri)
        val arr = root.optJSONArray("highlights") ?: JSONArray()
        val list = buildList {
            for (i in 0 until arr.length()) {
                parseHighlight(arr.getJSONObject(i))?.let { add(it) }
            }
        }
        return BookNotesDocument(
            version = root.optInt("version", 1),
            bookUri = uri,
            highlights = list,
        )
    }

    private fun parseHighlight(o: JSONObject): Highlight? {
        val id = o.optString("id", "").ifBlank { return null }
        val kind = runCatching {
            HighlightKind.valueOf(o.optString("kind", "TXT"))
        }.getOrDefault(HighlightKind.TXT)
        val a = o.optJSONObject("anchor") ?: return null
        val anchor = TextAnchor(
            startParagraph = a.optInt("startParagraph", 0),
            startOffset = a.optInt("startOffset", 0),
            endParagraph = a.optInt("endParagraph", 0),
            endOffset = a.optInt("endOffset", 0),
        )
        val s = o.optJSONObject("style")
        val mode = runCatching {
            HighlightMode.valueOf(s?.optString("mode", "BACKGROUND") ?: "BACKGROUND")
        }.getOrDefault(HighlightMode.BACKGROUND)
        val shape = runCatching {
            UnderlineShape.valueOf(s?.optString("underlineShape", "SOLID") ?: "SOLID")
        }.getOrDefault(UnderlineShape.SOLID)
        val style = HighlightStyle(
            mode = mode,
            underlineShape = shape,
            colorArgb = s?.optInt("colorArgb", 0x66FFE082.toInt()) ?: 0x66FFE082.toInt(),
            opacity = s?.optInt("opacity", 80) ?: 80,
        )
        return Highlight(
            id = id,
            kind = kind,
            anchor = anchor,
            selectedText = o.optString("selectedText", ""),
            note = o.optString("note", ""),
            style = style,
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}
