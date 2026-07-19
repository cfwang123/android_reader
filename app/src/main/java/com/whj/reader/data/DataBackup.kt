package com.whj.reader.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.whj.reader.model.Bookmark
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.model.ShelfFolderKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 书架 / 绑定文件夹 / 阅读进度 / 书签 的 SQLite 备份导入导出。
 * 文件默认写到：内部存储 Documents/WhjReader/ 或公共 Documents/WhjReader/
 */
object DataBackup {
    private const val TAG = "DataBackup"
    const val DB_VERSION = 1
    const val MIME = "application/x-sqlite3"

    data class ExportResult(
        val file: File,
        val folderCount: Int,
        val bookCount: Int,
        val progressCount: Int,
        val bookmarkCount: Int,
    )

    data class ImportResult(
        val folderCount: Int,
        val bookCount: Int,
        val progressCount: Int,
        val bookmarkCount: Int,
    )

    /** 备份目录（优先公共 Documents，失败则用应用外部目录） */
    fun backupDir(ctx: Context): File {
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(publicDocs, "WhjReader")
        if (runCatching { if (!dir.exists()) dir.mkdirs(); dir.canWrite() }.getOrDefault(false)) {
            return dir
        }
        val appDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WhjReader")
        if (!appDir.exists()) appDir.mkdirs()
        return appDir
    }

    fun defaultExportFile(ctx: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(backupDir(ctx), "reader_backup_$stamp.db")
    }

    fun exportToFile(ctx: Context, dest: File = defaultExportFile(ctx)): ExportResult {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        // 在缓存中建库再 copy，避免持有锁
        val tmp = File(ctx.cacheDir, "backup_export_${System.currentTimeMillis()}.db")
        if (tmp.exists()) tmp.delete()

        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(tmp, null)
        try {
            createSchema(db)
            val folders = BookshelfStore.folders(ctx)
            val books = BookshelfStore.books(ctx)
            val progress = ReadingProgressStore.exportAll(ctx)
            val bookmarks = BookmarkStore.all(ctx)

            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO meta(k,v) VALUES(?,?)",
                    arrayOf("version", DB_VERSION.toString()),
                )
                db.execSQL(
                    "INSERT INTO meta(k,v) VALUES(?,?)",
                    arrayOf("exported_at", System.currentTimeMillis().toString()),
                )
                folders.forEach { f ->
                    db.execSQL(
                        """INSERT INTO shelf_folders(id,name,created_at,kind,tree_uri)
                           VALUES(?,?,?,?,?)""".trimIndent(),
                        arrayOf(
                            f.id,
                            f.name,
                            f.createdAt,
                            f.kind.name,
                            f.treeUri,
                        ),
                    )
                }
                books.forEach { b ->
                    db.execSQL(
                        """INSERT INTO shelf_books(id,uri,display_name,folder_id,path_hint,last_paragraph,last_opened)
                           VALUES(?,?,?,?,?,?,?)""".trimIndent(),
                        arrayOf(
                            b.id,
                            b.uri,
                            b.displayName,
                            b.folderId,
                            b.pathHint,
                            b.lastParagraph,
                            b.lastOpened,
                        ),
                    )
                }
                progress.forEach { (uri, p) ->
                    db.execSQL(
                        """INSERT INTO reading_progress(uri,last_opened,position,total,kind)
                           VALUES(?,?,?,?,?)""".trimIndent(),
                        arrayOf(uri, p.lastOpened, p.position, p.total, p.kind.name),
                    )
                }
                bookmarks.forEach { b ->
                    db.execSQL(
                        """INSERT INTO bookmarks(file_key,paragraph_index,preview,created_at)
                           VALUES(?,?,?,?)""".trimIndent(),
                        arrayOf(b.fileKey, b.paragraphIndex, b.preview, b.createdAt),
                    )
                }
                // TXT/PDF 附加进度键（与 AppSettings 兼容）
                exportPrefKeys(
                    db,
                    "reader_settings",
                    ctx.getSharedPreferences("reader_settings", Context.MODE_PRIVATE).all
                        .filterKeys { it.startsWith("progress_") || it.startsWith("lastBook") },
                )
                exportPrefKeys(
                    db,
                    "reader_pdf_settings",
                    ctx.getSharedPreferences("reader_pdf_settings", Context.MODE_PRIVATE).all
                        .filterKeys {
                            it.startsWith("pdf_progress_") ||
                                it.startsWith("pdf_zoom_") ||
                                it.startsWith("pdf_pan") ||
                                it.startsWith("pdf_scrollY_") ||
                                it.startsWith("lastPdf")
                        },
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            db.close()
            FileInputStream(tmp).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }

            return ExportResult(
                file = dest,
                folderCount = folders.size,
                bookCount = books.size,
                progressCount = progress.size,
                bookmarkCount = bookmarks.size,
            )
        } finally {
            runCatching { if (db.isOpen) db.close() }
            tmp.delete()
        }
    }

    /**
     * 从备份文件导入并**替换**本地书架 / 进度 / 书签。
     * 绑定文件夹的 treeUri 会原样恢复；若权限丢失需用户重新授权。
     */
    fun importFromFile(ctx: Context, source: File): ImportResult {
        if (!source.exists() || source.length() <= 0L) {
            error("备份文件无效")
        }
        val tmp = File(ctx.cacheDir, "backup_import_${System.currentTimeMillis()}.db")
        if (tmp.exists()) tmp.delete()
        FileInputStream(source).use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }

        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            tmp.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val version = queryMeta(db, "version")?.toIntOrNull() ?: 0
            if (version > DB_VERSION) {
                error("备份版本过高($version)，请升级应用")
            }

            val folders = ArrayList<ShelfFolder>()
            db.rawQuery("SELECT id,name,created_at,kind,tree_uri FROM shelf_folders", null).use { c ->
                val iId = c.getColumnIndex("id")
                val iName = c.getColumnIndex("name")
                val iCreated = c.getColumnIndex("created_at")
                val iKind = c.getColumnIndex("kind")
                val iTree = c.getColumnIndex("tree_uri")
                while (c.moveToNext()) {
                    val kind = when (c.getString(iKind)?.uppercase()) {
                        "LINKED" -> ShelfFolderKind.LINKED
                        else -> ShelfFolderKind.SHELF
                    }
                    folders.add(
                        ShelfFolder(
                            id = c.getString(iId) ?: UUID.randomUUID().toString(),
                            name = c.getString(iName).orEmpty(),
                            createdAt = if (iCreated >= 0) c.getLong(iCreated) else 0L,
                            kind = kind,
                            treeUri = c.getString(iTree)?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }

            val books = ArrayList<ShelfBook>()
            db.rawQuery(
                "SELECT id,uri,display_name,folder_id,path_hint,last_paragraph,last_opened FROM shelf_books",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    books.add(
                        ShelfBook(
                            id = c.getString(0) ?: UUID.randomUUID().toString(),
                            uri = c.getString(1).orEmpty(),
                            displayName = c.getString(2).orEmpty(),
                            folderId = c.getString(3)?.takeIf { it.isNotBlank() && it != "null" },
                            pathHint = c.getString(4).orEmpty(),
                            lastParagraph = c.getInt(5),
                            lastOpened = c.getLong(6),
                        ),
                    )
                }
            }

            val progress = ArrayList<Pair<String, ReadingProgressStore.Progress>>()
            db.rawQuery(
                "SELECT uri,last_opened,position,total,kind FROM reading_progress",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val uri = c.getString(0) ?: continue
                    progress.add(
                        uri to ReadingProgressStore.Progress(
                            lastOpened = c.getLong(1),
                            position = c.getInt(2),
                            total = c.getInt(3),
                            kind = when (c.getString(4)?.uppercase()) {
                                "PDF" -> ReadingProgressStore.Kind.PDF
                                else -> ReadingProgressStore.Kind.TXT
                            },
                        ),
                    )
                }
            }

            val bookmarks = ArrayList<Bookmark>()
            runCatching {
                db.rawQuery(
                    "SELECT file_key,paragraph_index,preview,created_at FROM bookmarks",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        bookmarks.add(
                            Bookmark(
                                fileKey = c.getString(0).orEmpty(),
                                paragraphIndex = c.getInt(1),
                                preview = c.getString(2).orEmpty(),
                                createdAt = c.getLong(3),
                            ),
                        )
                    }
                }
            }

            BookshelfStore.replaceAllData(ctx, folders, books)
            ReadingProgressStore.replaceAll(ctx, progress)
            BookmarkStore.replaceAll(ctx, bookmarks)
            importPrefKeys(ctx, db)

            return ImportResult(
                folderCount = folders.size,
                bookCount = books.size,
                progressCount = progress.size,
                bookmarkCount = bookmarks.size,
            )
        } finally {
            runCatching { db.close() }
            tmp.delete()
        }
    }

    /** 从 content Uri 复制到临时文件再导入 */
    fun importFromUri(ctx: Context, uri: android.net.Uri): ImportResult {
        val tmp = File(ctx.cacheDir, "backup_import_uri_${System.currentTimeMillis()}.db")
        if (tmp.exists()) tmp.delete()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        } ?: error("无法读取备份文件")
        return try {
            importFromFile(ctx, tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun createSchema(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)",
        )
        db.execSQL(
            """CREATE TABLE shelf_folders(
                id TEXT PRIMARY KEY,
                name TEXT,
                created_at INTEGER,
                kind TEXT,
                tree_uri TEXT
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE shelf_books(
                id TEXT PRIMARY KEY,
                uri TEXT,
                display_name TEXT,
                folder_id TEXT,
                path_hint TEXT,
                last_paragraph INTEGER,
                last_opened INTEGER
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE reading_progress(
                uri TEXT PRIMARY KEY,
                last_opened INTEGER,
                position INTEGER,
                total INTEGER,
                kind TEXT
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE bookmarks(
                file_key TEXT,
                paragraph_index INTEGER,
                preview TEXT,
                created_at INTEGER,
                PRIMARY KEY(file_key, paragraph_index)
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE app_kv(
                pref TEXT,
                k TEXT,
                v TEXT,
                type TEXT,
                PRIMARY KEY(pref, k)
            )""".trimIndent(),
        )
    }

    private fun exportPrefKeys(
        db: android.database.sqlite.SQLiteDatabase,
        prefName: String,
        map: Map<String, *>,
    ) {
        map.forEach { (k, v) ->
            if (v == null) return@forEach
            val type = when (v) {
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is Boolean -> "bool"
                is String -> "string"
                else -> return@forEach
            }
            db.execSQL(
                "INSERT OR REPLACE INTO app_kv(pref,k,v,type) VALUES(?,?,?,?)",
                arrayOf(prefName, k, v.toString(), type),
            )
        }
    }

    private fun importPrefKeys(ctx: Context, db: android.database.sqlite.SQLiteDatabase) {
        runCatching {
            db.rawQuery("SELECT pref,k,v,type FROM app_kv", null).use { c ->
                val byPref = HashMap<String, ArrayList<Quad>>()
                while (c.moveToNext()) {
                    val pref = c.getString(0) ?: continue
                    val list = byPref.getOrPut(pref) { ArrayList() }
                    list.add(Quad(c.getString(1), c.getString(2), c.getString(3)))
                }
                byPref.forEach { (prefName, rows) ->
                    val ed = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
                    rows.forEach { row ->
                        val key = row.a ?: return@forEach
                        val value = row.b ?: return@forEach
                        when (row.c) {
                            "int" -> ed.putInt(key, value.toIntOrNull() ?: 0)
                            "long" -> ed.putLong(key, value.toLongOrNull() ?: 0L)
                            "float" -> ed.putFloat(key, value.toFloatOrNull() ?: 0f)
                            "bool" -> ed.putBoolean(key, value.toBooleanStrictOrNull() ?: (value == "true"))
                            else -> ed.putString(key, value)
                        }
                    }
                    ed.apply()
                }
            }
        }.onFailure { Log.w(TAG, "importPrefKeys: ${it.message}") }
    }

    private fun queryMeta(db: android.database.sqlite.SQLiteDatabase, key: String): String? {
        return runCatching {
            db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private data class Quad(val a: String?, val b: String?, val c: String?)
}
