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
 * 按书落盘：同目录 `.notes/{书名}.notes.json`；不可写时降级 `files/notes_mirror/`。
 */
object BookNotesFileStore {

    private const val NOTES_DIR = ".notes"
    private const val MIRROR_DIR = "notes_mirror"
    private const val SUFFIX = ".notes.json"

    data class Location(
        val file: File,
        val isMirror: Boolean,
    )

    fun resolveLocation(ctx: Context, bookUri: String): Location {
        val uri = runCatching { Uri.parse(bookUri) }.getOrNull()
        if (uri != null) {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val book = File(uri.path.orEmpty())
                    if (book.isFile && book.parentFile != null) {
                        return sidecarFor(book)
                    }
                }
                "content" -> {
                    StorageAccess.resolveFilePath(ctx, uri)?.let { path ->
                        val book = File(path)
                        if (book.isFile) return sidecarFor(book)
                    }
                }
            }
        }
        return mirrorFor(ctx, bookUri)
    }

    fun load(ctx: Context, bookUri: String): BookNotesDocument {
        if (bookUri.isBlank()) {
            return BookNotesDocument(bookUri = bookUri, highlights = emptyList())
        }
        val loc = resolveLocation(ctx, bookUri)
        if (!loc.file.isFile) {
            return BookNotesDocument(bookUri = bookUri, highlights = emptyList())
        }
        return runCatching {
            parse(loc.file.readText(Charsets.UTF_8), bookUri)
        }.getOrElse {
            BookNotesDocument(bookUri = bookUri, highlights = emptyList())
        }
    }

    fun save(ctx: Context, doc: BookNotesDocument): Location {
        val loc = resolveLocation(ctx, doc.bookUri)
        loc.file.parentFile?.mkdirs()
        val json = serialize(doc)
        val tmp = File(loc.file.parentFile, loc.file.name + ".tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (loc.file.exists()) loc.file.delete()
        if (!tmp.renameTo(loc.file)) {
            tmp.copyTo(loc.file, overwrite = true)
            tmp.delete()
        }
        return loc
    }

    fun deleteAll(ctx: Context, bookUri: String) {
        save(ctx, BookNotesDocument(bookUri = bookUri, highlights = emptyList()))
    }

    private fun sidecarFor(book: File): Location {
        val notesDir = File(book.parentFile, NOTES_DIR)
        notesDir.mkdirs()
        val safeName = book.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return Location(File(notesDir, "$safeName$SUFFIX"), isMirror = false)
    }

    private fun mirrorFor(ctx: Context, bookUri: String): Location {
        val dir = File(ctx.filesDir, MIRROR_DIR).apply { mkdirs() }
        val key = bookUri.hashCode().toUInt().toString(16)
        return Location(File(dir, "$key$SUFFIX"), isMirror = true)
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
