package com.whj.reader.data

import android.content.Context
import android.net.Uri

/**
 * 统一入口：按扩展名/MIME 分发到 TXT / EPUB / MOBI 加载器。
 * PDF 仍走 [com.whj.reader.PdfReadingActivity]，不经过此处。
 *
 * EPUB/MOBI 返回 [BookOpenResult.streamer] 时：首屏已可显示；
 * 按需 [BookStreamer.loadNextBatchBlocking] 续载，不必一次加载全书。
 */
object BookLoader {

    fun openFromUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        preferredEncoding: String? = null,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
        onProgress: LoadProgressListener? = null,
    ): BookOpenResult {
        val name = displayName
            ?: runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull()
            ?: uri.lastPathSegment

        return when {
            BookFileType.isEpubUri(context, uri, name) ->
                EpubLoader.openFromUri(context, uri, name, chineseMode, onProgress)
            BookFileType.isMobiUri(context, uri, name) ->
                MobiLoader.openFromUri(context, uri, name, chineseMode, onProgress)
            else -> {
                onProgress?.invoke(
                    context.getString(com.whj.reader.R.string.load_stage_parse),
                    0,
                    0,
                )
                val book = TextLoader.loadFromUri(
                    context,
                    uri,
                    name,
                    preferredEncoding,
                    chineseMode,
                )
                onProgress?.invoke(context.getString(com.whj.reader.R.string.load_stage_done), 1, 1)
                BookOpenResult(book = book, streamer = null)
            }
        }
    }

    /** 兼容旧调用：阻塞到整本解析完（含流式抽干） */
    fun loadFromUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        preferredEncoding: String? = null,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
        onProgress: LoadProgressListener? = null,
    ): LoadedBook {
        val open = openFromUri(
            context,
            uri,
            displayName,
            preferredEncoding,
            chineseMode,
            onProgress,
        )
        val streamer = open.streamer ?: return open.book
        var latest = open.book
        streamer.start(
            onUpdate = { b -> latest = b },
            onComplete = { b -> latest = b },
            onProgress = onProgress,
        )
        while (streamer.loadNextBatchBlocking()) {
            // 阻塞抽干
        }
        return latest
    }
}
