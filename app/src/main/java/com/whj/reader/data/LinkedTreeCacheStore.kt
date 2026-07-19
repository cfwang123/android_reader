package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.whj.reader.model.LinkedDirEntry
import com.whj.reader.model.LinkedFileEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.ArrayDeque
import java.util.Locale

/**
 * 绑定文件夹的文件树缓存：
 * - 绑定 /「重新扫描」时整树索引
 * - 列表项数直接读缓存
 * - 进入某目录时与 SAF 实时列表比对，不一致则更新该层
 */
object LinkedTreeCacheStore {
    private const val TAG = "LinkedTreeCache"
    private const val DIR_NAME = "linked_tree_cache"

    /** 内存快照：进入子目录时零磁盘、零 JSON 解析 */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    data class DirRec(
        val name: String,
        val uri: String,
        val documentId: String,
        val childCount: Int,
        /** 相对绑定根的路径，不含末尾 /；根下直接子目录为 name */
        val relativePath: String,
    )

    data class FileRec(
        val name: String,
        val displayName: String,
        val uri: String,
        val documentId: String,
        val isPdf: Boolean,
        val sizeBytes: Long,
        val relativePath: String,
        val parentDocumentId: String,
    )

    data class Level(
        val dirs: List<DirRec>,
        val files: List<FileRec>,
    ) {
        val itemCount: Int get() = dirs.size + files.size
    }

    data class Snapshot(
        val treeUri: String,
        val rootDocumentId: String,
        val scannedAt: Long,
        /** key = parent documentId（根用 [rootDocumentId]） */
        val levels: Map<String, Level>,
    ) {
        fun rootChildCount(): Int =
            levels[rootDocumentId]?.itemCount ?: -1

        fun levelKey(documentId: String?): String =
            documentId?.takeIf { it.isNotBlank() } ?: rootDocumentId

        fun hasLevel(documentId: String?): Boolean =
            levels.containsKey(levelKey(documentId))

        /**
         * 有该层缓存则返回列表（可为空目录）；**无该层键则 null**（勿与空目录混淆）。
         */
        fun listingOrNull(documentId: String?): LinkedListing? {
            val level = levels[levelKey(documentId)] ?: return null
            val collator = Collator.getInstance(Locale.getDefault())
            val dirs = level.dirs
                .map {
                    LinkedDirEntry(
                        name = it.name,
                        uri = it.uri,
                        documentId = it.documentId,
                        childCount = it.childCount,
                    )
                }
                .sortedWith(compareBy(collator) { it.name })
            val files = level.files
                .map {
                    LinkedFileEntry(
                        name = it.name,
                        displayName = it.displayName,
                        uri = it.uri,
                        isPdf = it.isPdf,
                        sizeBytes = it.sizeBytes,
                        relativePath = it.relativePath,
                        documentId = it.documentId,
                    )
                }
                .sortedWith(compareBy(collator) { it.displayName })
            return LinkedListing(dirs = dirs, files = files)
        }

        fun listing(documentId: String?): LinkedListing =
            listingOrNull(documentId) ?: LinkedListing(emptyList(), emptyList())

        fun allFiles(): List<FileRec> = levels.values.flatMap { it.files }
    }

    private fun cacheDir(ctx: Context): File =
        File(ctx.filesDir, DIR_NAME).also { it.mkdirs() }

    private fun cacheFile(ctx: Context, treeUri: String): File {
        val safe = treeUri.hashCode().toUInt().toString(16)
        return File(cacheDir(ctx), "$safe.json")
    }

    /** 仅内存，主线程可直接调用（不读盘） */
    fun peek(treeUri: String): Snapshot? =
        if (treeUri.isBlank()) null else memory[treeUri]

    fun load(ctx: Context, treeUri: String): Snapshot? {
        if (treeUri.isBlank()) return null
        memory[treeUri]?.let { return it }
        val f = cacheFile(ctx, treeUri)
        if (!f.isFile) return null
        val snap = runCatching {
            parseSnapshot(f.readText(Charsets.UTF_8))
        }.onFailure {
            Log.w(TAG, "load cache failed: ${it.message}")
        }.getOrNull()?.takeIf { it.treeUri == treeUri }
        if (snap != null) {
            memory[treeUri] = snap
        }
        return snap
    }

    fun save(ctx: Context, snapshot: Snapshot) {
        if (snapshot.treeUri.isBlank()) return
        memory[snapshot.treeUri] = snapshot
        val f = cacheFile(ctx, snapshot.treeUri)
        runCatching {
            f.writeText(toJson(snapshot).toString(), Charsets.UTF_8)
        }.onFailure {
            Log.e(TAG, "save cache failed", it)
        }
    }

    /** 只更新内存（后台写盘另议）；用于频繁的小修正 */
    private fun putMemory(snapshot: Snapshot) {
        if (snapshot.treeUri.isBlank()) return
        memory[snapshot.treeUri] = snapshot
    }

    fun remove(ctx: Context, treeUri: String?) {
        if (treeUri.isNullOrBlank()) return
        memory.remove(treeUri)
        runCatching { cacheFile(ctx, treeUri).delete() }
    }

    /** 所有已绑定文件夹缓存中的书文件（供全局搜索） */
    fun allCachedFiles(ctx: Context): List<Pair<String, FileRec>> {
        val result = ArrayList<Pair<String, FileRec>>()
        BookshelfStore.folders(ctx).forEach { folder ->
            if (!folder.isLinked) return@forEach
            val tree = folder.treeUri ?: return@forEach
            val snap = load(ctx, tree) ?: return@forEach
            snap.allFiles().forEach { result.add(tree to it) }
        }
        return result
    }

    /**
     * 整树扫描（BFS）。可能较慢，务必在 IO 线程调用。
     */
    fun scanEntireTree(ctx: Context, treeUri: String): Snapshot {
        val uri = Uri.parse(treeUri)
        FolderImporter.takePersistable(ctx, uri)
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val levelDrafts = LinkedHashMap<String, Pair<List<DirDraft>, List<FileRec>>>()
        val queue = ArrayDeque<Pair<String, String>>() // parentDocId, pathPrefix
        queue.addLast(rootId to "")

        while (queue.isNotEmpty()) {
            val (parentId, pathPrefix) = queue.removeFirst()
            if (levelDrafts.containsKey(parentId)) continue
            val listing = runCatching {
                FolderImporter.listLinkedDirectory(ctx, uri, parentId)
            }.getOrElse {
                Log.w(TAG, "list failed parent=$parentId: ${it.message}")
                LinkedListing(emptyList(), emptyList())
            }
            val dirs = listing.dirs.map { d ->
                val rel = joinPath(pathPrefix, d.name)
                DirDraft(
                    name = d.name,
                    uri = d.uri,
                    documentId = d.documentId,
                    relativePath = rel,
                )
            }
            val files = listing.files.map { f ->
                val rel = joinPath(pathPrefix, f.name)
                if (f.sizeBytes >= 0) {
                    ShelfFileMetaStore.setSizeBytesIfChanged(ctx, f.uri, f.sizeBytes)
                }
                FileRec(
                    name = f.name,
                    displayName = f.displayName,
                    uri = f.uri,
                    documentId = f.documentId.ifBlank {
                        // 列表若未填 documentId，从 uri 尽量解析
                        runCatching { DocumentsContract.getDocumentId(Uri.parse(f.uri)) }
                            .getOrDefault("")
                    },
                    isPdf = f.isPdf,
                    sizeBytes = f.sizeBytes,
                    relativePath = rel,
                    parentDocumentId = parentId,
                )
            }
            levelDrafts[parentId] = dirs to files
            dirs.forEach { d ->
                if (d.documentId.isNotBlank() && !levelDrafts.containsKey(d.documentId)) {
                    queue.addLast(d.documentId to d.relativePath)
                }
            }
        }

        val levels = levelDrafts.mapValues { (_, pair) ->
            val (dirs, files) = pair
            Level(
                dirs = dirs.map { d ->
                    val count = levelDrafts[d.documentId]?.let { it.first.size + it.second.size } ?: 0
                    DirRec(
                        name = d.name,
                        uri = d.uri,
                        documentId = d.documentId,
                        childCount = count,
                        relativePath = d.relativePath,
                    )
                },
                files = files,
            )
        }

        val snap = Snapshot(
            treeUri = treeUri,
            rootDocumentId = rootId,
            scannedAt = System.currentTimeMillis(),
            levels = levels,
        )
        save(ctx, snap)
        Log.i(
            TAG,
            "scan done tree=${treeUri.takeLast(24)} levels=${levels.size} " +
                "files=${snap.allFiles().size}",
        )
        return snap
    }

    data class RefreshResult(
        val listing: LinkedListing,
        /** 列表内容或子项数是否相对进入前缓存有变化（决定是否刷新 UI / 写盘） */
        val contentChanged: Boolean,
    )

    /**
     * 用实时 SAF 列表校验某一层。
     * - 与缓存一致：**不写盘**，直接返回缓存 listing
     * - 不一致：更新内存；写盘异步由调用方或此处完成
     */
    fun refreshLevel(
        ctx: Context,
        treeUri: String,
        parentDocumentId: String?,
    ): RefreshResult {
        val uri = Uri.parse(treeUri)
        val old = load(ctx, treeUri)
        val rootId = old?.rootDocumentId
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        if (rootId.isBlank()) {
            val live = FolderImporter.listLinkedDirectory(ctx, uri, parentDocumentId)
            return RefreshResult(live, contentChanged = true)
        }
        val key = parentDocumentId?.takeIf { it.isNotBlank() } ?: rootId
        val live = FolderImporter.listLinkedDirectory(ctx, uri, parentDocumentId)

        val pathPrefix = when {
            key == rootId -> ""
            else -> old?.findDir(key)?.relativePath.orEmpty()
        }

        val newDirs = live.dirs.map { d ->
            val oldDir = old?.findDir(d.documentId)
            val countFromLevel = old?.levels?.get(d.documentId)?.itemCount
            val childCount = when {
                countFromLevel != null -> countFromLevel
                oldDir != null && oldDir.childCount >= 0 -> oldDir.childCount
                else -> -1
            }
            DirRec(
                name = d.name,
                uri = d.uri,
                documentId = d.documentId,
                childCount = childCount,
                relativePath = joinPath(pathPrefix, d.name),
            )
        }
        val newFiles = live.files.map { f ->
            // 浏览时不再写 SharedPreferences（扫描时已写）；仅内存里带 size
            FileRec(
                name = f.name,
                displayName = f.displayName,
                uri = f.uri,
                documentId = f.documentId.ifBlank {
                    runCatching { DocumentsContract.getDocumentId(Uri.parse(f.uri)) }
                        .getOrDefault("")
                },
                isPdf = f.isPdf,
                sizeBytes = f.sizeBytes,
                relativePath = joinPath(pathPrefix, f.name),
                parentDocumentId = key,
            )
        }

        val newLevel = Level(dirs = newDirs, files = newFiles)
        val oldLevel = old?.levels?.get(key)
        val contentChanged = oldLevel == null || !levelContentEquals(oldLevel, newLevel)

        // 仅内容一致：不写盘、不重建 Snapshot
        if (!contentChanged && old != null) {
            // 若子目录 childCount 可从 levels 修正且不同，才轻量更新
            val countFixed = recomputeChildCounts(old.levels)
            val countChanged = countFixed[key]?.dirs != old.levels[key]?.dirs
            if (countChanged) {
                val snap = old.copy(levels = countFixed)
                putMemory(snap)
                // 数量修正低频，异步写盘
                runCatching { save(ctx, snap) }
                return RefreshResult(snap.listing(parentDocumentId), contentChanged = true)
            }
            return RefreshResult(old.listing(parentDocumentId), contentChanged = false)
        }

        val baseLevels = (old?.levels ?: emptyMap()).toMutableMap()
        if (oldLevel != null) {
            val keepIds = newDirs.map { it.documentId }.toSet()
            val removed = oldLevel.dirs.map { it.documentId }.filter { it !in keepIds }
            removed.forEach { rid -> removeSubtreeLevels(baseLevels, rid) }
        }
        baseLevels[key] = newLevel
        val finalized = recomputeChildCounts(baseLevels)
        val snap = Snapshot(
            treeUri = treeUri,
            rootDocumentId = rootId,
            scannedAt = System.currentTimeMillis(),
            levels = finalized,
        )
        // 先写内存，立刻可被下一层进入使用；磁盘写入相对慢
        putMemory(snap)
        save(ctx, snap)
        Log.i(TAG, "level updated key=${key.takeLast(20)} items=${newLevel.itemCount}")
        return RefreshResult(snap.listing(parentDocumentId), contentChanged = true)
    }

    private data class DirDraft(
        val name: String,
        val uri: String,
        val documentId: String,
        val relativePath: String,
    )

    private fun Snapshot.findDir(documentId: String): DirRec? {
        levels.values.forEach { level ->
            level.dirs.firstOrNull { it.documentId == documentId }?.let { return it }
        }
        return null
    }

    private fun levelContentEquals(a: Level, b: Level): Boolean {
        if (a.dirs.size != b.dirs.size || a.files.size != b.files.size) return false
        val ad = a.dirs.map { it.documentId to it.name }.toSet()
        val bd = b.dirs.map { it.documentId to it.name }.toSet()
        if (ad != bd) return false
        val af = a.files.map { it.uri to it.name }.toSet()
        val bf = b.files.map { it.uri to it.name }.toSet()
        return af == bf
    }

    private fun removeSubtreeLevels(levels: MutableMap<String, Level>, rootDocId: String) {
        val queue = ArrayDeque<String>()
        queue.add(rootDocId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val level = levels.remove(id) ?: continue
            level.dirs.forEach { queue.add(it.documentId) }
        }
    }

    private fun recomputeChildCounts(levels: Map<String, Level>): Map<String, Level> {
        return levels.mapValues { (_, level) ->
            Level(
                dirs = level.dirs.map { d ->
                    val count = levels[d.documentId]?.itemCount
                    d.copy(childCount = count ?: d.childCount)
                },
                files = level.files,
            )
        }
    }

    private fun joinPath(prefix: String, name: String): String =
        if (prefix.isBlank()) name else "$prefix/$name"

    private fun toJson(snap: Snapshot): JSONObject {
        val levelsObj = JSONObject()
        snap.levels.forEach { (key, level) ->
            val dirsArr = JSONArray()
            level.dirs.forEach { d ->
                dirsArr.put(
                    JSONObject()
                        .put("name", d.name)
                        .put("uri", d.uri)
                        .put("documentId", d.documentId)
                        .put("childCount", d.childCount)
                        .put("relativePath", d.relativePath),
                )
            }
            val filesArr = JSONArray()
            level.files.forEach { f ->
                filesArr.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("displayName", f.displayName)
                        .put("uri", f.uri)
                        .put("documentId", f.documentId)
                        .put("isPdf", f.isPdf)
                        .put("sizeBytes", f.sizeBytes)
                        .put("relativePath", f.relativePath)
                        .put("parentDocumentId", f.parentDocumentId),
                )
            }
            levelsObj.put(
                key,
                JSONObject()
                    .put("dirs", dirsArr)
                    .put("files", filesArr),
            )
        }
        return JSONObject()
            .put("treeUri", snap.treeUri)
            .put("rootDocumentId", snap.rootDocumentId)
            .put("scannedAt", snap.scannedAt)
            .put("levels", levelsObj)
    }

    private fun parseSnapshot(raw: String): Snapshot {
        val o = JSONObject(raw)
        val treeUri = o.getString("treeUri")
        val rootId = o.getString("rootDocumentId")
        val scannedAt = o.optLong("scannedAt", 0L)
        val levelsObj = o.getJSONObject("levels")
        val levels = LinkedHashMap<String, Level>()
        val keys = levelsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lo = levelsObj.getJSONObject(key)
            val dirsArr = lo.optJSONArray("dirs") ?: JSONArray()
            val filesArr = lo.optJSONArray("files") ?: JSONArray()
            val dirs = buildList {
                for (i in 0 until dirsArr.length()) {
                    val d = dirsArr.getJSONObject(i)
                    add(
                        DirRec(
                            name = d.getString("name"),
                            uri = d.getString("uri"),
                            documentId = d.getString("documentId"),
                            childCount = d.optInt("childCount", -1),
                            relativePath = d.optString("relativePath", d.getString("name")),
                        ),
                    )
                }
            }
            val files = buildList {
                for (i in 0 until filesArr.length()) {
                    val f = filesArr.getJSONObject(i)
                    add(
                        FileRec(
                            name = f.getString("name"),
                            displayName = f.optString("displayName", f.getString("name")),
                            uri = f.getString("uri"),
                            documentId = f.optString("documentId", ""),
                            isPdf = f.optBoolean("isPdf", f.getString("name").endsWith(".pdf", true)),
                            sizeBytes = f.optLong("sizeBytes", -1L),
                            relativePath = f.optString("relativePath", f.getString("name")),
                            parentDocumentId = f.optString("parentDocumentId", key),
                        ),
                    )
                }
            }
            levels[key] = Level(dirs, files)
        }
        return Snapshot(
            treeUri = treeUri,
            rootDocumentId = rootId,
            scannedAt = scannedAt,
            levels = levels,
        )
    }
}
