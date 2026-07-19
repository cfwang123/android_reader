package com.whj.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.whj.reader.model.LinkedDirEntry
import com.whj.reader.model.LinkedFileEntry
import java.text.Collator
import java.util.Locale

data class ImportResult(
    val folderName: String,
    val files: List<ImportedFile>,
)

data class ImportedFile(
    val uri: String,
    val displayName: String,
    val pathHint: String,
)

/** 某目录下一层：子文件夹 + txt/pdf */
data class LinkedListing(
    val dirs: List<LinkedDirEntry>,
    val files: List<LinkedFileEntry>,
)

object FolderImporter {

    fun takePersistable(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    /**
     * 绑定 tree 是否仍可读（持久授权或当前可 list）。
     * 卸载重装 / 用户撤销授权后会返回 false。
     */
    fun hasTreeAccess(context: Context, treeUri: String?): Boolean {
        if (treeUri.isNullOrBlank()) return false
        return hasTreeAccess(context, Uri.parse(treeUri))
    }

    fun hasTreeAccess(context: Context, treeUri: Uri): Boolean {
        val held = context.contentResolver.persistedUriPermissions.any { p ->
            p.isReadPermission && urisEqualForTree(p.uri, treeUri)
        }
        if (held) {
            // 仍有授权记录时再确认根目录可读
            return runCatching {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                root != null && (root.canRead() || root.exists())
            }.getOrDefault(false)
        }
        // 无持久授权：尝试打开（会话级 grant 也可能可读）
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching false
            root.canRead() || root.exists()
        }.getOrDefault(false)
    }

    /** 是否像权限类失败（便于引导重新授权） */
    fun isAccessFailure(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is SecurityException) return true
        val msg = (error.message ?: error.javaClass.simpleName).lowercase()
        return msg.contains("permission") ||
            msg.contains("权限") ||
            msg.contains("security") ||
            msg.contains("eacces") ||
            msg.contains("eperm") ||
            msg.contains("not authorized") ||
            msg.contains("unauthorized") ||
            msg.contains("无法打开") ||
            msg.contains("cannot open") ||
            msg.contains("no access")
    }

    private fun urisEqualForTree(a: Uri, b: Uri): Boolean {
        if (a == b) return true
        return a.toString().trimEnd('/') == b.toString().trimEnd('/')
    }

    /**
     * 导入树形目录下一层中的 .txt / .pdf（不递归子目录，满足「仅 1 层」书架模型）。
     */
    fun listTxtInTree(context: Context, treeUri: Uri): ImportResult =
        listBooksInTree(context, treeUri)

    fun listBooksInTree(context: Context, treeUri: Uri): ImportResult {
        takePersistable(context, treeUri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法打开文件夹")
        val folderName = root.name?.ifBlank { null } ?: queryTreeName(treeUri)
            ?: context.getString(com.whj.reader.R.string.import_shelf_default)
        val listing = listLinkedDirectory(context, treeUri, documentId = null)
        val files = listing.files.map { f ->
            ImportedFile(
                uri = f.uri,
                displayName = f.displayName,
                pathHint = "$folderName/${f.name}",
            )
        }
        return ImportResult(folderName = folderName, files = files)
    }

    /**
     * 列出绑定目录下一层内容。
     *
     * 必须用 [DocumentsContract.buildChildDocumentsUriUsingTree] 查询，
     * 不能用 [DocumentFile.fromSingleUri].listFiles()：子目录在仅有 tree 授权时会无权限/空列表。
     *
     * @param treeUri 绑定根的 SAF tree URI
     * @param documentId 当前子目录 documentId；null 表示 tree 根
     */
    fun listLinkedDirectory(
        context: Context,
        treeUri: Uri,
        documentId: String? = null,
    ): LinkedListing {
        // 列举时不再每次 takePersistable（首次绑定已 take）
        val parentId = documentId?.takeIf { it.isNotBlank() }
            ?: DocumentsContract.getTreeDocumentId(treeUri)
        return listChildrenViaTreeQuery(context, treeUri, parentId)
    }

    /**
     * 统计绑定目录下直接子项数（子文件夹 + txt/pdf）。
     * 注意：较慢，列表展示请避免对每个子目录调用。
     * @param documentId null 表示 tree 根
     */
    fun countChildren(
        context: Context,
        treeUri: Uri,
        documentId: String? = null,
    ): Int {
        return runCatching {
            val parentId = documentId?.takeIf { it.isNotBlank() }
                ?: DocumentsContract.getTreeDocumentId(treeUri)
            countChildrenOf(context, treeUri, parentId)
        }.getOrDefault(0)
    }

    /**
     * 在已授权的 tree 下，用 ContentResolver 列举 [parentDocumentId] 的直接子项。
     */
    private fun listChildrenViaTreeQuery(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
    ): LinkedListing {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )
        val collator = Collator.getInstance(Locale.getDefault())
        val dirs = mutableListOf<LinkedDirEntry>()
        val files = mutableListOf<LinkedFileEntry>()
        // 一次 query 带出 id/name/mime/size，避免列表绑定时再对每个文件 query SIZE
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        try {
            context.contentResolver.query(
                childrenUri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idIdx < 0 || nameIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                            mime.equals("vnd.android.document/directory", ignoreCase = true) -> {
                            // 不在列表时逐个 count 子目录（每个都是一次 SAF query，极慢）
                            // 显示用 -1：UI 仅显示「文件夹」
                            dirs.add(
                                LinkedDirEntry(
                                    name = name,
                                    uri = docUri.toString(),
                                    documentId = docId,
                                    childCount = -1,
                                ),
                            )
                        }
                        isBookFileName(name) -> {
                            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                cursor.getLong(sizeIdx).coerceAtLeast(-1L)
                            } else {
                                -1L
                            }
                            val uriStr = docUri.toString()
                            if (size >= 0L) {
                                ShelfFileMetaStore.setSizeBytes(context, uriStr, size)
                            }
                            files.add(
                                LinkedFileEntry(
                                    name = name,
                                    displayName = BookFileType.stripBookExt(name),
                                    uri = uriStr,
                                    isPdf = name.endsWith(".pdf", ignoreCase = true),
                                    sizeBytes = size,
                                    documentId = docId,
                                ),
                            )
                        }
                    }
                }
            } ?: error("无法读取文件夹内容（权限不足）")
        } catch (e: SecurityException) {
            throw SecurityException("无权限访问子文件夹: ${e.message}", e)
        }
        dirs.sortWith(compareBy(collator) { it.name })
        files.sortWith(compareBy(collator) { it.displayName })
        return LinkedListing(dirs = dirs, files = files)
    }

    /** 统计某 document 下直接子项（子目录 + 书文件） */
    private fun countChildrenOf(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
    ): Int {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var n = 0
        runCatching {
            context.contentResolver.query(
                childrenUri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (nameIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                        mime.equals("vnd.android.document/directory", ignoreCase = true)
                    if (isDir || isBookFileName(name)) n++
                }
            }
        }
        return n
    }

    fun resolveTreeDisplayName(context: Context, treeUri: Uri): String {
        takePersistable(context, treeUri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
        return root?.name?.ifBlank { null }
            ?: queryTreeName(treeUri)
            ?: context.getString(com.whj.reader.R.string.linked_folder_default)
    }

    fun isBookFileName(name: String): Boolean {
        return name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".pdf", ignoreCase = true)
    }

    private fun queryTreeName(treeUri: Uri): String? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val name = docId.substringAfterLast(':').substringAfterLast('/')
            name.ifBlank { null }
        }.getOrNull()
    }
}
