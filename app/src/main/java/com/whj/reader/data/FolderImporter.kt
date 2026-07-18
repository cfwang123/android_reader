package com.whj.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

data class ImportResult(
    val folderName: String,
    val files: List<ImportedFile>,
)

data class ImportedFile(
    val uri: String,
    val displayName: String,
    val pathHint: String,
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
     * 导入树形目录下一层中的 .txt（不递归子目录，满足「仅 1 层」书架模型）。
     */
    fun listTxtInTree(context: Context, treeUri: Uri): ImportResult {
        takePersistable(context, treeUri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法打开文件夹")
        val folderName = root.name?.ifBlank { null } ?: queryTreeName(treeUri)
            ?: context.getString(com.whj.reader.R.string.import_shelf_default)
        val files = mutableListOf<ImportedFile>()
        root.listFiles().forEach { child ->
            if (!child.isFile) return@forEach
            val name = child.name ?: return@forEach
            if (!name.endsWith(".txt", ignoreCase = true)) return@forEach
            val uri = child.uri
            // 单文件也尽量拿持久权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            files.add(
                ImportedFile(
                    uri = uri.toString(),
                    displayName = name.removeSuffix(".txt").removeSuffix(".TXT"),
                    pathHint = "$folderName/$name",
                ),
            )
        }
        files.sortBy { it.displayName.lowercase() }
        return ImportResult(folderName = folderName, files = files)
    }

    private fun queryTreeName(treeUri: Uri): String? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val name = docId.substringAfterLast(':').substringAfterLast('/')
            name.ifBlank { null }
        }.getOrNull()
    }
}
