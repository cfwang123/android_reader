package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.whj.reader.model.Chapter
import com.whj.reader.model.InlineImage
import com.whj.reader.model.Paragraph
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 简易 MOBI/PRC 加载：PalmDOC 解压 + HTML 子集解析。
 * 首屏只解析前几块 HTML，其余 [BookStreamer] 后台续载。
 */
object MobiLoader {

    private const val TAG = "MobiLoader"
    private const val FIRST_MIN_PARAS = 24
    private const val FIRST_MAX_CHUNKS = 3
    private const val MAX_FULL_CACHE_BYTES = 6L * 1024L * 1024L
    private const val CHAPTER_INDEX_NAME = "mobi_chapter_index_v1.json"
    private const val CHUNK_CACHE_DIR = "chunk_cache"

    fun openFromUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
        onProgress: LoadProgressListener? = null,
    ): BookOpenResult {
        val titleHint = displayName
            ?: queryDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: context.getString(com.whj.reader.R.string.unnamed)
        val cacheKey = cacheKeyFor(uri.toString(), titleHint)
        val workDir = File(context.cacheDir, "ebooks/mobi/$cacheKey").apply { mkdirs() }
        val mobiFile = File(workDir, "book.mobi")
        if (!mobiFile.exists() || mobiFile.length() == 0L) {
            onProgress?.invoke(context.getString(com.whj.reader.R.string.load_stage_copy), 0, 0)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(mobiFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: error("无法打开 MOBI")
        }
        return openFromFile(mobiFile, workDir, titleHint, uri.toString(), chineseMode, onProgress)
    }

    fun loadFromUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
    ): LoadedBook {
        val open = openFromUri(context, uri, displayName, chineseMode, null)
        if (open.streamer == null) return open.book
        var latest = open.book
        var done = false
        open.streamer.start(
            onUpdate = { b -> latest = b },
            onComplete = { b ->
                latest = b
                done = true
            },
        )
        while (!done && open.streamer.loadNextBatchBlocking()) {
            // drain
        }
        return latest
    }

    fun loadFromFile(
        file: File,
        workDir: File,
        titleHint: String,
        uriStr: String,
        chineseMode: ChineseConvert.Mode,
    ): LoadedBook {
        val open = openFromFile(file, workDir, titleHint, uriStr, chineseMode, null)
        if (open.streamer == null) return open.book
        var latest = open.book
        var done = false
        open.streamer.start(
            onUpdate = { b -> latest = b },
            onComplete = { b ->
                latest = b
                done = true
            },
        )
        while (!done && open.streamer.loadNextBatchBlocking()) {
            // drain
        }
        return latest
    }

    fun openFromFile(
        file: File,
        workDir: File,
        titleHint: String,
        uriStr: String,
        chineseMode: ChineseConvert.Mode,
        onProgress: LoadProgressListener? = null,
    ): BookOpenResult {
        val sourceSize = file.length()
        val sourceModified = file.lastModified()
        val parsedCache = File(workDir, "parsed_${chineseMode.name}_v5.bin")
        val chapterIndexFile = File(workDir, CHAPTER_INDEX_NAME)
        val chunkCacheDir = File(workDir, CHUNK_CACHE_DIR).apply { mkdirs() }

        onProgress?.invoke("读取缓存…", 0, 0)
        if (parsedCache.isFile && parsedCache.length() in 1..MAX_FULL_CACHE_BYTES) {
            loadParsedCache(parsedCache, uriStr, sourceSize, sourceModified)?.let { cached ->
                Log.i(TAG, "hit parse cache paras=${cached.paragraphs.size}")
                onProgress?.invoke("完成", 1, 1)
                return BookOpenResult(cached.copy(isComplete = true), null)
            }
        }
        val cachedIndex = loadMobiChapterIndex(
            chapterIndexFile,
            uriStr,
            sourceSize,
            sourceModified,
        )

        onProgress?.invoke("解析 MOBI…", 0, 0)
        val data = file.readBytes()
        if (data.size < 80) error("MOBI 文件过小")

        val numRecords = u16(data, 76)
        if (numRecords <= 0 || 78 + numRecords * 8 > data.size) {
            error("无效 MOBI：记录表异常")
        }
        val offsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            offsets[i] = u32(data, 78 + i * 8)
        }

        val rec0 = sliceRecord(data, offsets, 0)
        if (rec0.size < 16) error("无效 MOBI：记录 0 过短")

        val compression = u16(rec0, 0)
        val textLength = u32(rec0, 4)
        val textRecordCount = u16(rec0, 8)
        // val recordSize = u16(rec0, 10)

        var fullName = titleHint
            .removeSuffix(".mobi").removeSuffix(".MOBI")
            .removeSuffix(".azw").removeSuffix(".AZW")
            .removeSuffix(".azw3").removeSuffix(".AZW3")
            .removeSuffix(".prc").removeSuffix(".PRC")

        // MOBI header starts at 16
        var firstImageIndex = -1
        var exthFlags = 0
        var mobiType = 2
        var mobiEncoding = 1252
        if (rec0.size >= 0x20 && String(rec0, 16, 4, Charsets.US_ASCII) == "MOBI") {
            val headerLen = u32(rec0, 20)
            mobiType = u32(rec0, 24)
            mobiEncoding = u32(rec0, 28)
            if (rec0.size >= 84) {
                firstImageIndex = u32(rec0, 108).takeIf { rec0.size > 108 } ?: -1
            }
            // full name
            if (rec0.size >= 88) {
                val fullNameOffset = u32(rec0, 84)
                val fullNameLength = u32(rec0, 88)
                if (fullNameOffset > 0 && fullNameLength > 0 &&
                    fullNameOffset + fullNameLength <= rec0.size
                ) {
                    val name = String(rec0, fullNameOffset, fullNameLength, Charsets.UTF_8)
                        .trim { it <= ' ' || it == '\u0000' }
                    if (name.isNotEmpty()) fullName = name
                }
            }
            if (rec0.size >= 0x84) {
                exthFlags = u32(rec0, 0x80)
            }
            // EXTH title
            if (exthFlags and 0x40 != 0 && headerLen + 16 <= rec0.size) {
                val exthStart = 16 + headerLen
                if (exthStart + 12 <= rec0.size &&
                    String(rec0, exthStart, 4, Charsets.US_ASCII) == "EXTH"
                ) {
                    val exthLen = u32(rec0, exthStart + 4)
                    val exthCount = u32(rec0, exthStart + 8)
                    var p = exthStart + 12
                    val end = (exthStart + exthLen).coerceAtMost(rec0.size)
                    var n = 0
                    while (n < exthCount && p + 8 <= end) {
                        val type = u32(rec0, p)
                        val len = u32(rec0, p + 4)
                        if (len < 8 || p + len > end) break
                        if (type == 503 || type == 99) { // title / updated title
                            val t = String(rec0, p + 8, len - 8, Charsets.UTF_8).trim()
                            if (t.isNotEmpty()) fullName = t
                        }
                        p += len
                        n++
                    }
                }
            }
            // first image index at offset 108 from MOBI start = 16+92
            if (rec0.size >= 16 + 92 + 4) {
                firstImageIndex = u32(rec0, 16 + 92)
            }
        }

        // 文本记录从 1 起，共 textRecordCount 条
        val textCount = textRecordCount.coerceIn(1, numRecords - 1)
        val rawText = ByteArrayOutputStream(textLength.coerceAtLeast(1024))
        for (i in 1..textCount) {
            if (i >= numRecords) break
            val rec = sliceRecord(data, offsets, i)
            val decoded = when (compression) {
                1 -> rec // no compression
                2 -> palmDocDecompress(rec)
                17480 -> {
                    // HUFF/CDIC：暂不支持，尝试当原始
                    rec
                }
                else -> palmDocDecompress(rec)
            }
            rawText.write(decoded)
        }
        var rawBytes = rawText.toByteArray()
        if (textLength in 1 until rawBytes.size) {
            rawBytes = rawBytes.copyOf(textLength)
        }
        rawBytes = sanitizeMobiRawHtml(rawBytes)
        var html = decodeMobiHtml(rawBytes, mobiEncoding, fullName)
        html = repairMobiHtml(html)
        logDecodeProbe(rawBytes, mobiEncoding, fullName, html)

        val beforeStrip = html.length
        html = stripMobiTrailingBinary(html)
        if (html.length < beforeStrip) {
            Log.w(TAG, "strip binary: $beforeStrip -> ${html.length}")
        }
        Log.i(
            TAG,
            "open compression=$compression textLen=$textLength records=$textCount " +
                "html=${html.length} chunks~=${splitHtmlChunks(html).size}",
        )

        val imgDir = File(workDir, "images").apply { mkdirs() }
        val imageMap = HashMap<String, String>() // recindex / src → path

        // 提取图片记录（从 firstImageIndex 起，直到非图片）
        val orderedImagePaths = ArrayList<String>()
        if (firstImageIndex in 1 until numRecords) {
            var imgN = 0
            for (ri in firstImageIndex until numRecords) {
                val rec = sliceRecord(data, offsets, ri)
                val ext = sniffImageExt(rec) ?: break
                imgN++
                val out = File(imgDir, "img_${imgN}.$ext")
                if (!out.exists()) {
                    out.writeBytes(rec)
                }
                // MOBI 中常见 recindex="00001" 从 1 起
                val key = String.format(Locale.US, "%05d", imgN)
                imageMap[key] = out.absolutePath
                imageMap[imgN.toString()] = out.absolutePath
                orderedImagePaths.add(out.absolutePath)
                if (imgN > 2000) break
            }
        }

        // recindex 图：<img recindex="00001">
        html = Regex(
            """(?is)<img([^>]*?)recindex\s*=\s*["']?(\d+)["']?([^>]*)/?>""",
        ).replace(html) { m ->
            val idx = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            val key = String.format(Locale.US, "%05d", idx)
            val path = imageMap[key] ?: imageMap[idx.toString()]
            if (path != null) {
                """<img src="file://$path"${m.groupValues[1]}${m.groupValues[3]}>"""
            } else {
                m.value
            }
        }

        // 按 h1/h2 切块，便于首屏只解析前几块
        val chunks = splitHtmlChunks(html)
        val mobiAnchorLinks = extractMobiAnchorLinks(html)
        val paragraphs = ArrayList<Paragraph>(256)
        val chapters = ArrayList<Chapter>()
        val linkTargets = LinkedHashMap<String, Int>(256)
        if (cachedIndex.chapters.isNotEmpty()) {
            chapters.addAll(cachedIndex.chapters)
            linkTargets.putAll(cachedIndex.linkTargets)
        }
        var chunkIdx = 0
        var textParas = 0
        while (chunkIdx < chunks.size &&
            chunkIdx < FIRST_MAX_CHUNKS &&
            textParas < FIRST_MIN_PARAS
        ) {
            textParas += appendChunkAt(
                chunkIdx,
                chunks[chunkIdx],
                chunkCacheDir,
                imageMap,
                chineseMode,
                paragraphs,
                chapters,
                linkTargets,
            )
            chunkIdx++
        }

        if (paragraphs.isEmpty() && chunks.isNotEmpty()) {
            // 兜底：整文解析
            appendBlocks(
                HtmlRichParser.parse(html), imageMap, chineseMode,
                paragraphs, chapters, linkTargets,
            )
            chunkIdx = chunks.size
        }

        if (paragraphs.isEmpty()) {
            val plain = html
                .replace(Regex("(?is)<[^>]+>"), "\n")
                .let { HtmlRichParser.decodeEntities(it) }
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            plain.forEachIndexed { i, line ->
                paragraphs.add(
                    Paragraph(index = i, text = ChineseConvert.apply(line, chineseMode)),
                )
            }
            chunkIdx = chunks.size
        }

        if (paragraphs.isEmpty() && orderedImagePaths.isEmpty()) {
            error("MOBI 中未解析到正文或图片")
        }
        // 纯图漫画：无正文时放占位段，避免阅读页空内容崩溃；漫画模式只用 imagePaths
        if (paragraphs.isEmpty()) {
            paragraphs.add(Paragraph(index = 0, text = ""))
        }

        @Suppress("UNUSED_VARIABLE")
        val unusedMobiType = mobiType

        reindexParas(paragraphs)
        applyMobiAnchorLinks(html, mobiAnchorLinks, paragraphs, linkTargets, chapters)
        val totalChunks = chunks.size.coerceAtLeast(1)
        val imagePaths = orderedImagePaths.toList()
        val firstBook = LoadedBook(
            title = fullName,
            paragraphs = paragraphs.toList(),
            chapters = chapters.toList().ifEmpty {
                paragraphs.filter { it.isChapter }.map {
                    Chapter(it.text.lineSequence().first().take(60), it.index)
                }
            },
            encoding = "UTF-8",
            uri = uriStr,
            isComplete = chunkIdx >= chunks.size,
            streamCurrent = chunkIdx,
            streamTotal = totalChunks,
            linkTargets = linkTargets.toMap(),
            imagePaths = imagePaths,
        )
        onProgress?.invoke("显示正文…", chunkIdx, totalChunks)

        if (chunkIdx >= chunks.size) {
            onProgress?.invoke("完成", 1, 1)
            maybeSaveParsedCache(parsedCache, firstBook, sourceSize, sourceModified)
            saveMobiChapterIndex(
                chapterIndexFile,
                uriStr,
                sourceSize,
                sourceModified,
                mobiAnchorLinks,
                firstBook.chapters,
                linkTargets,
            )
            return BookOpenResult(firstBook, null)
        }

        val streamer = MobiStreamer(
            chunks = chunks,
            nextChunk = chunkIdx,
            html = html,
            mobiAnchorLinks = mobiAnchorLinks,
            chunkCacheDir = chunkCacheDir,
            parsedCache = parsedCache,
            chapterIndexFile = chapterIndexFile,
            sourceSize = sourceSize,
            sourceModified = sourceModified,
            imageMap = imageMap,
            chineseMode = chineseMode,
            paragraphs = paragraphs,
            chapters = chapters,
            linkTargets = linkTargets,
            title = fullName,
            uriStr = uriStr,
            imagePaths = imagePaths,
        )
        return BookOpenResult(firstBook, streamer)
    }

    private data class MobiAnchorLink(val filepos: Int, val title: String)

    private val MOBI_ANCHOR_LINK = Regex(
        """(?is)<a\s+filepos\s*=\s*(\d+)\s*>(.*?)</a>""",
    )

    private fun extractMobiAnchorLinks(html: String): List<MobiAnchorLink> {
        return MOBI_ANCHOR_LINK.findAll(html).mapNotNull { m ->
            val fp = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val title = stripHtmlTags(m.groupValues[2])
            if (title.isEmpty()) null else MobiAnchorLink(fp, title)
        }.toList()
    }

    private fun stripHtmlTags(raw: String): String {
        return HtmlRichParser.decodeEntities(
            raw.replace(Regex("(?is)<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(),
        )
    }

    /**
     * MOBI 目录常用 filepos 链接；补全 linkTargets 与 chapters（无 h1/h2 时）。
     */
    private fun applyMobiAnchorLinks(
        html: String,
        links: List<MobiAnchorLink>,
        paragraphs: List<Paragraph>,
        linkTargets: MutableMap<String, Int>,
        chapters: MutableList<Chapter>,
    ) {
        if (links.isEmpty() || paragraphs.isEmpty()) return
        val sorted = links.sortedBy { it.filepos }
        val mapped = mapMobiAnchorChapters(html, sorted, paragraphs)
        if (mapped.isEmpty()) return
        chapters.clear()
        chapters.addAll(mapped)
        for (i in sorted.indices) {
            val key = HtmlRichParser.mobiFileposHref(sorted[i].filepos)
            linkTargets[key] = mapped[i].paragraphIndex
            linkTargets[key.lowercase(Locale.ROOT)] = mapped[i].paragraphIndex
        }
    }

    private fun appendChunkAt(
        chunkIndex: Int,
        chunk: String,
        chunkCacheDir: File,
        imageMap: Map<String, String>,
        chineseMode: ChineseConvert.Mode,
        paragraphs: ArrayList<Paragraph>,
        chapters: ArrayList<Chapter>,
        linkTargets: MutableMap<String, Int>,
    ): Int {
        val before = paragraphs.size
        if (loadChunkCache(chunkCacheDir, chunkIndex, paragraphs)) {
            return paragraphs.size - before
        }
        val added = appendBlocks(
            HtmlRichParser.parse(chunk),
            imageMap,
            chineseMode,
            paragraphs,
            chapters,
            linkTargets,
        )
        saveChunkCache(chunkCacheDir, chunkIndex, paragraphs, before, paragraphs.size)
        return added
    }

    private fun mapMobiAnchorChapters(
        html: String,
        sortedLinks: List<MobiAnchorLink>,
        paragraphs: List<Paragraph>,
    ): List<Chapter> {
        var lastPara = -1
        val out = ArrayList<Chapter>(sortedLinks.size)
        for (link in sortedLinks) {
            val para = matchMobiChapterParagraph(link.title, paragraphs, lastPara + 1)
                ?: estimateParaForMobiFilepos(link.filepos, html.length, paragraphs.size)
            val fixed = para.coerceIn(
                (lastPara + 1).coerceAtMost(paragraphs.lastIndex.coerceAtLeast(0)),
                paragraphs.lastIndex.coerceAtLeast(0),
            )
            lastPara = fixed
            out.add(Chapter(link.title.take(60), fixed))
        }
        return out
    }

    private fun matchMobiChapterParagraph(
        title: String,
        paragraphs: List<Paragraph>,
        startFrom: Int,
    ): Int? {
        val normTitle = normalizeForMobiMatch(title)
        val segments = normTitle.split(':').map { it.trim() }.filter { it.length >= 6 }
        val from = startFrom.coerceAtLeast(0)
        for (i in from until paragraphs.size) {
            val t = normalizeForMobiMatch(paragraphs[i].text)
            if (t.isEmpty()) continue
            if (normTitle.length >= 8 && (t.contains(normTitle) || normTitle.contains(t))) {
                return i
            }
            for (seg in segments) {
                if (t.contains(seg) || seg.contains(t)) return i
            }
            val num = Regex("""(?i)chapter\s+(\d+)""").find(normTitle)?.groupValues?.getOrNull(1)
            if (num != null && (t == num || t.startsWith("$num ") ||
                    Regex("""^$num\b""").containsMatchIn(t))
            ) {
                return i
            }
        }
        return null
    }

    private fun normalizeForMobiMatch(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()

    private fun estimateParaForMobiFilepos(filepos: Int, htmlLen: Int, paraCount: Int): Int {
        if (paraCount <= 0 || htmlLen <= 0) return 0
        return ((filepos.toLong() * paraCount) / htmlLen).toInt()
            .coerceIn(0, paraCount - 1)
    }

    private fun splitHtmlChunks(html: String): List<String> {
        if (html.length < 12_000) return listOf(html)
        val parts = html.split(Regex("(?i)(?=<h[12]\\b)"))
        if (parts.size >= 2) {
            // 合并过小碎片
            val out = ArrayList<String>()
            val buf = StringBuilder()
            for (p in parts) {
                buf.append(p)
                if (buf.length >= 8_000) {
                    out.add(buf.toString())
                    buf.clear()
                }
            }
            if (buf.isNotEmpty()) out.add(buf.toString())
            return out.ifEmpty { listOf(html) }
        }
        // 无标题：按固定长度切，尽量在标签边界
        val out = ArrayList<String>()
        var i = 0
        val n = html.length
        val step = 16_000
        while (i < n) {
            var end = (i + step).coerceAtMost(n)
            if (end < n) {
                val gt = html.indexOf('>', end)
                if (gt in end until (end + 200).coerceAtMost(n)) end = gt + 1
            }
            out.add(html.substring(i, end))
            i = end
        }
        return out
    }

    private fun appendBlocks(
        blocks: List<HtmlRichParser.ParsedBlock>,
        imageMap: Map<String, String>,
        chineseMode: ChineseConvert.Mode,
        paragraphs: ArrayList<Paragraph>,
        chapters: ArrayList<Chapter>,
        linkTargets: MutableMap<String, Int>,
    ): Int {
        var textAdded = 0
        for (block in blocks) {
            if (!block.imageSrc.isNullOrBlank()) {
                val path = resolveMobiImageSrc(block.imageSrc, imageMap) ?: continue
                val idx = paragraphs.size
                paragraphs.add(
                    Paragraph(
                        index = idx,
                        text = "",
                        imagePath = path,
                        imageDisplaySize = block.imageDisplaySize,
                    ),
                )
                for (a in block.anchors) {
                    linkTargets[a.id] = idx
                    linkTargets[a.id.lowercase(Locale.ROOT)] = idx
                }
                continue
            }
            var text = block.text
            if (text.isBlank()) {
                if (block.anchors.isNotEmpty()) {
                    val idx = paragraphs.size
                    paragraphs.add(Paragraph(index = idx, text = "\u200B"))
                    for (a in block.anchors) {
                        linkTargets[a.id] = idx
                        linkTargets[a.id.lowercase(Locale.ROOT)] = idx
                    }
                }
                continue
            }
            text = ChineseConvert.apply(text, chineseMode)
            val spans = if (chineseMode == ChineseConvert.Mode.OFF || text == block.text) {
                block.spans
            } else {
                emptyList()
            }
            val inlines = block.inlineImages.mapNotNull { ref ->
                val path = resolveMobiImageSrc(ref.src, imageMap) ?: return@mapNotNull null
                InlineImage(ref.start, ref.end, path, ref.displaySize)
            }
            val idx = paragraphs.size
            paragraphs.add(
                Paragraph(
                    index = idx,
                    text = text,
                    isChapter = block.isChapter,
                    spans = spans,
                    inlineImages = inlines,
                    align = block.align,
                    preformatted = block.preformatted,
                ),
            )
            for (a in block.anchors) {
                linkTargets[a.id] = idx
                linkTargets[a.id.lowercase(Locale.ROOT)] = idx
            }
            textAdded++
            if (block.isChapter) {
                chapters.add(Chapter(text.lineSequence().first().take(60), idx))
            }
        }
        return textAdded
    }

    private fun reindexParas(paragraphs: ArrayList<Paragraph>) {
        for (i in paragraphs.indices) {
            if (paragraphs[i].index != i) paragraphs[i] = paragraphs[i].copy(index = i)
        }
    }

    private class MobiStreamer(
        private val chunks: List<String>,
        private var nextChunk: Int,
        private val html: String,
        private val mobiAnchorLinks: List<MobiAnchorLink>,
        private val chunkCacheDir: File,
        private val parsedCache: File,
        private val chapterIndexFile: File,
        private val sourceSize: Long,
        private val sourceModified: Long,
        private val imageMap: Map<String, String>,
        private val chineseMode: ChineseConvert.Mode,
        private val paragraphs: ArrayList<Paragraph>,
        private val chapters: ArrayList<Chapter>,
        private val linkTargets: MutableMap<String, Int>,
        private val title: String,
        private val uriStr: String,
        private val imagePaths: List<String>,
    ) : BookStreamer {
        /** 每批解析的 HTML 块数（按需续载，避免一次扫完全书） */
        private val batchChunks = 2

        @Volatile
        private var cancelled = false
        @Volatile
        private var finished = false
        private var onUpdate: ((LoadedBook) -> Unit)? = null
        private var onComplete: ((LoadedBook) -> Unit)? = null
        private var onProgress: LoadProgressListener? = null

        override fun start(
            onUpdate: (LoadedBook) -> Unit,
            onComplete: (LoadedBook) -> Unit,
            onProgress: LoadProgressListener?,
        ) {
            this.onUpdate = onUpdate
            this.onComplete = onComplete
            this.onProgress = onProgress
        }

        override fun loadNextBatchBlocking(): Boolean {
            if (cancelled || finished) return false
            if (nextChunk >= chunks.size) {
                finishComplete()
                return false
            }
            return try {
                var n = 0
                while (!cancelled && nextChunk < chunks.size && n < batchChunks) {
                    appendChunkAt(
                        nextChunk,
                        chunks[nextChunk],
                        chunkCacheDir,
                        imageMap,
                        chineseMode,
                        paragraphs,
                        chapters,
                        linkTargets,
                    )
                    nextChunk++
                    n++
                }
                if (cancelled) return false
                reindexParas(paragraphs)
                applyMobiAnchorLinks(html, mobiAnchorLinks, paragraphs, linkTargets, chapters)
                saveMobiChapterIndex(
                    chapterIndexFile,
                    uriStr,
                    sourceSize,
                    sourceModified,
                    mobiAnchorLinks,
                    chapters,
                    linkTargets,
                )
                if (nextChunk >= chunks.size) {
                    finishComplete()
                    false
                } else {
                    onProgress?.invoke(
                        "加载 $nextChunk/${chunks.size}",
                        nextChunk,
                        chunks.size,
                    )
                    onUpdate?.invoke(snapshot(complete = false))
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "stream batch fail at chunk $nextChunk", e)
                if (nextChunk < chunks.size) {
                    nextChunk++
                    onUpdate?.invoke(snapshot(complete = false))
                    return true
                }
                finished = true
                onComplete?.invoke(snapshot(complete = true))
                false
            }
        }

        override fun loadUntilParagraphBlocking(targetParaInclusive: Int): Boolean {
            if (cancelled || finished) return false
            if (nextChunk >= chunks.size) {
                finishComplete()
                return false
            }
            return try {
                val t0 = System.currentTimeMillis()
                var loadedChunks = 0
                while (!cancelled &&
                    nextChunk < chunks.size &&
                    paragraphs.lastIndex < targetParaInclusive
                ) {
                    appendChunkAt(
                        nextChunk,
                        chunks[nextChunk],
                        chunkCacheDir,
                        imageMap,
                        chineseMode,
                        paragraphs,
                        chapters,
                        linkTargets,
                    )
                    nextChunk++
                    loadedChunks++
                }
                if (cancelled) return false
                reindexParas(paragraphs)
                applyMobiAnchorLinks(html, mobiAnchorLinks, paragraphs, linkTargets, chapters)
                saveMobiChapterIndex(
                    chapterIndexFile,
                    uriStr,
                    sourceSize,
                    sourceModified,
                    mobiAnchorLinks,
                    chapters,
                    linkTargets,
                )
                Log.i(
                    TAG,
                    "seek load chunks=$loadedChunks paras=${paragraphs.size} " +
                        "target=$targetParaInclusive in ${System.currentTimeMillis() - t0}ms",
                )
                if (nextChunk >= chunks.size) {
                    finishComplete()
                    false
                } else {
                    onUpdate?.invoke(snapshot(complete = false))
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "seek load fail at chunk $nextChunk", e)
                loadNextBatchBlocking()
            }
        }

        private fun finishComplete() {
            if (finished) return
            finished = true
            reindexParas(paragraphs)
            applyMobiAnchorLinks(html, mobiAnchorLinks, paragraphs, linkTargets, chapters)
            val final = snapshot(complete = true)
            maybeSaveParsedCache(parsedCache, final, sourceSize, sourceModified)
            saveMobiChapterIndex(
                chapterIndexFile,
                uriStr,
                sourceSize,
                sourceModified,
                mobiAnchorLinks,
                chapters,
                linkTargets,
            )
            onProgress?.invoke("完成", chunks.size, chunks.size)
            onComplete?.invoke(final)
        }

        private fun snapshot(complete: Boolean): LoadedBook {
            return LoadedBook(
                title = title,
                paragraphs = paragraphs.toList(),
                chapters = chapters.toList().ifEmpty {
                    paragraphs.filter { it.isChapter }.map {
                        Chapter(it.text.lineSequence().first().take(60), it.index)
                    }
                },
                encoding = "UTF-8",
                uri = uriStr,
                isComplete = complete,
                streamCurrent = nextChunk,
                streamTotal = chunks.size.coerceAtLeast(1),
                linkTargets = linkTargets.toMap(),
                imagePaths = imagePaths,
            )
        }

        override fun cancel() {
            cancelled = true
            finished = true
        }
    }

    private fun resolveMobiImageSrc(src: String, imageMap: Map<String, String>): String? {
        return when {
            src.startsWith("file://") -> src.removePrefix("file://").takeIf { File(it).isFile }
            imageMap.containsKey(src) -> imageMap[src]
            File(src).isFile -> src
            else -> {
                val key = src.filter { it.isDigit() }
                if (key.isNotEmpty()) imageMap[key] ?: imageMap[String.format(Locale.US, "%05d", key.toIntOrNull() ?: -1)]
                else null
            }
        }
    }

    /**
     * PalmDOC 解压后清理：去 NUL、修补 LZ 误插入的 height 碎片等（须在 UTF-8 解码前）。
     */
    private fun sanitizeMobiRawHtml(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return raw
        val pTagHeig = byteArrayOf(
            '>'.code.toByte(), '<'.code.toByte(), 'p'.code.toByte(), ' '.code.toByte(),
            0x68, 0x65, 0x69, 0x67,
        )
        val paraOpen = "></p><p height=\"1em\" width=\"0pt\">".toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        val n = raw.size
        while (i < n) {
            if (raw[i] == 0.toByte()) {
                i++
                continue
            }
            // e6 9c + heig + a8 -> e6 9c a8（木）
            if (i + 6 < n &&
                raw[i] == 0xE6.toByte() && raw[i + 1] == 0x9C.toByte() &&
                raw[i + 2] == 0x68.toByte() && raw[i + 3] == 0x65.toByte() &&
                raw[i + 4] == 0x69.toByte() && raw[i + 5] == 0x67.toByte() &&
                raw[i + 6] == 0xA8.toByte()
            ) {
                out.write(0xE6)
                out.write(0x9C)
                out.write(0xA8)
                i += 7
                continue
            }
            var tagAt = i
            if (i + 1 < n && isStrayUtf8Tail(raw[i]) && matchesAt(raw, i + 1, pTagHeig) &&
                !matchesAt(raw, i + 5, "height".toByteArray(Charsets.US_ASCII))
            ) {
                tagAt = i + 1
            }
            if (tagAt + 8 <= n && matchesAt(raw, tagAt, pTagHeig) &&
                !matchesAt(raw, tagAt + 4, "height".toByteArray(Charsets.US_ASCII))
            ) {
                val restEnd = (tagAt + 24).coerceAtMost(n)
                val hasZhu = containsAt(raw, tagAt + 8, byteArrayOf(0xE4.toByte(), 0xBD.toByte(), 0x8F.toByte()), restEnd)
                if (hasZhu) {
                    i = tagAt + 8
                    while (i < n && isStrayUtf8Tail(raw[i])) i++
                } else {
                    out.write(paraOpen)
                    i = tagAt + 8
                    while (i < n && isStrayUtf8Tail(raw[i])) i++
                }
                continue
            }
            out.write(raw[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    private fun isStrayUtf8Tail(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 0x80..0xBF
    }

    private fun matchesAt(raw: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > raw.size) return false
        for (j in needle.indices) {
            if (raw[offset + j] != needle[j]) return false
        }
        return true
    }

    private fun containsAt(raw: ByteArray, offset: Int, needle: ByteArray, end: Int): Boolean {
        val last = (end - needle.size).coerceAtMost(raw.size - needle.size)
        var p = offset
        while (p <= last) {
            if (matchesAt(raw, p, needle)) return true
            p++
        }
        return false
    }

    /** UTF-8 解码后修补残留碎片 */
    private fun repairMobiHtml(html: String): String {
        var s = html.replace("><p ht=\"", "><p height=\"")
        if (s.indexOf('\uFFFD') >= 0) {
            s = s.replace("\uFFFD", "")
        }
        return s
    }

    private fun stripMobiTrailingBinary(html: String): String {
        // 尾部 NUL 填充（</html> 之后）可去掉
        var s = html.trimEnd('\u0000')
        val close = s.indexOf("</html>", ignoreCase = true)
        if (close >= 0) {
            s = s.substring(0, close + 7)
        }
        // 正文中间的 \u0000（如「石盆、\0石床」）不能截断全书，仅删除该字符
        if (s.indexOf('\u0000') >= 0) {
            s = s.replace("\u0000", "")
        }
        return s
    }

    /**
     * MOBI 正文编码：读 MOBI 头 encoding；中文书常见 GBK 字节误标为 1252/UTF-8。
     */
    private fun decodeMobiHtml(rawBytes: ByteArray, mobiEncoding: Int, titleHint: String): String {
        val expectChinese = titleLooksChinese(titleHint)
        val utf8 = String(rawBytes, Charsets.UTF_8)

        // 文件头标明 UTF-8：始终用 UTF-8（sanitize 后少量 U+FFFD 仍优于误用 GB18030）
        if (mobiEncoding == 65001) {
            Log.i(TAG, "decode: UTF-8 (enc=65001 title=$titleHint repl=${utf8.count { it == '\uFFFD' }})")
            return utf8
        }

        val gb = runCatching { String(rawBytes, charset("GB18030")) }.getOrNull()
        if (gb != null && shouldPreferGbk(utf8, gb, expectChinese, mobiEncoding)) {
            Log.i(TAG, "decode: GB18030 (enc=$mobiEncoding title=$titleHint)")
            return gb
        }

        var html = utf8
        if (!expectChinese && isMostlyLatin(html)) {
            val win = runCatching { String(rawBytes, charset("windows-1252")) }.getOrNull()
            if (win != null && shouldPreferWin1252(html, win)) {
                Log.i(TAG, "decode: windows-1252 (latin MOBI enc=$mobiEncoding)")
                return win
            }
        }
        val hasRepl = html.indexOf('\uFFFD') >= 0
        if (hasRepl || looksLikeGarbled(html)) {
            if (gb != null && !looksLikeGarbled(gb)) {
                Log.i(TAG, "decode: GB18030 fallback (enc=$mobiEncoding)")
                html = gb
            }
        }
        return html
    }

    private fun titleLooksChinese(title: String): Boolean {
        for (ch in title) {
            if (ch in '\u4E00'..'\u9FFF') return true
        }
        return false
    }

    private fun shouldPreferGbk(
        utf8: String,
        gb: String,
        expectChinese: Boolean,
        mobiEncoding: Int,
    ): Boolean {
        val uCjk = countCjkSample(utf8)
        val gCjk = countCjkSample(gb)
        if (expectChinese && gCjk > uCjk + 5 && mobiEncoding != 65001) return true
        if (utf8.indexOf('\uFFFD') >= 0 && gb.indexOf('\uFFFD') < 0) return true
        if (expectChinese && uCjk < 30 && gCjk >= 30 && mobiEncoding != 65001) return true
        if (hasCjkAdjacentToLatin(utf8) && !hasCjkAdjacentToLatin(gb)) return true
        if (mobiEncoding != 65001 && expectChinese && gCjk >= 50 && gCjk > uCjk * 2) return true
        return false
    }

    /** UTF-8 试解码含误成汉字的字节时，西文 MOBI 应改用 Windows-1252 */
    private fun shouldPreferWin1252(utf8: String, win: String): Boolean {
        if (utf8.indexOf('\uFFFD') >= 0 && win.indexOf('\uFFFD') < 0) return true
        if (hasCjkAdjacentToLatin(utf8) && !hasCjkAdjacentToLatin(win)) return true
        val uCjk = countCjkSample(utf8)
        val wCjk = countCjkSample(win)
        return uCjk > 0 && wCjk < uCjk
    }

    private fun hasCjkAdjacentToLatin(s: String): Boolean {
        val sample = s.take(8000)
        for (i in sample.indices) {
            val ch = sample[i]
            if (ch !in '\u4E00'..'\u9FFF') continue
            val prev = sample.getOrNull(i - 1)
            val next = sample.getOrNull(i + 1)
            if (prev != null && prev.isLetter() && prev.code < 128) return true
            if (next != null && next.isLetter() && next.code < 128) return true
        }
        return false
    }

    private fun countCjkSample(s: String): Int {
        var n = 0
        for (ch in s.take(8000)) {
            if (ch in '\u4E00'..'\u9FFF') n++
        }
        return n
    }

    /** 抽样判断是否为西文为主（避免 GB18030 误伤英文 MOBI） */
    private fun isMostlyLatin(s: String): Boolean {
        val sample = s.take(6000)
        if (sample.isEmpty()) return true
        var latin = 0
        var cjk = 0
        for (ch in sample) {
            if (ch == '\uFFFD' || ch.isWhitespace()) continue
            when {
                ch.code < 0x300 -> latin++
                ch in '\u4E00'..'\u9FFF' -> cjk++
            }
        }
        val letters = latin + cjk
        if (letters == 0) return true
        return cjk * 10 < latin
    }

    private fun looksLikeGarbled(s: String): Boolean {
        val sample = s.take(4000)
        var bad = 0
        var total = 0
        for (ch in sample) {
            if (ch == '\uFFFD') bad += 3
            total++
        }
        return total > 0 && bad * 10 > total
    }

    private fun logDecodeProbe(
        rawBytes: ByteArray,
        mobiEncoding: Int,
        titleHint: String,
        chosen: String,
    ) {
        if (!titleLooksChinese(titleHint) &&
            !titleHint.contains("西游", ignoreCase = true)
        ) {
            return
        }
        val utf8 = String(rawBytes, Charsets.UTF_8)
        val gb = runCatching { String(rawBytes, charset("GB18030")) }.getOrElse { "" }
        val big5 = runCatching { String(rawBytes, charset("Big5")) }.getOrElse { "" }
        val metaCs = sniffHtmlCharset(chosen)
        Log.i(
            TAG,
            "probe title=$titleHint enc=$mobiEncoding meta=$metaCs rawLen=${rawBytes.size}",
        )
        Log.i(
            TAG,
            "probe cjk utf8=${countCjkSample(utf8)} gb=${countCjkSample(gb)} " +
                "big5=${countCjkSample(big5)} chosen=${countCjkSample(chosen)}",
        )
        Log.i(TAG, "probe utf8=${sampleForLog(utf8)}")
        Log.i(TAG, "probe gb=${sampleForLog(gb)}")
        Log.i(TAG, "probe big5=${sampleForLog(big5)}")
        Log.i(TAG, "probe chosen=${sampleForLog(chosen)}")
        val firstPara = Regex("""(?is)<p[^>]*>(.*?)</p>""")
            .find(chosen)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { stripHtmlTags(it) }
        Log.i(TAG, "probe firstPara=$firstPara")
        Log.i(
            TAG,
            "probe rawHex=${rawBytes.take(48).joinToString("") { "%02x".format(it) }}",
        )
    }

    private fun sniffHtmlCharset(html: String): String {
        val head = html.take(6000)
        val m1 = Regex("""(?is)<meta[^>]+charset\s*=\s*["']?([^"'\\s/>]+)""")
            .find(head)
        if (m1 != null) return m1.groupValues[1].trim()
        val m2 = Regex("""(?is)content\s*=\s*["'][^"']*charset=([^"'\\s;]+)""")
            .find(head)
        return m2?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun sampleForLog(s: String): String =
        s.replace(Regex("\\s+"), " ").take(120)

    private fun sniffImageExt(rec: ByteArray): String? {
        if (rec.size < 4) return null
        return when {
            rec[0] == 0xFF.toByte() && rec[1] == 0xD8.toByte() -> "jpg"
            rec[0] == 0x89.toByte() && rec[1] == 0x50.toByte() -> "png"
            rec[0] == 'G'.code.toByte() && rec[1] == 'I'.code.toByte() -> "gif"
            rec[0] == 'B'.code.toByte() && rec[1] == 'M'.code.toByte() -> "bmp"
            rec.size > 12 &&
                rec[0] == 'R'.code.toByte() &&
                rec[8] == 'W'.code.toByte() -> "webp"
            else -> null
        }
    }

    /** PalmDOC LZ77 解压 */
    fun palmDocDecompress(src: ByteArray): ByteArray {
        // 可回读的输出缓冲（支持重叠拷贝）
        var buf = ByteArray(src.size.coerceAtLeast(256) * 2)
        var outLen = 0
        fun ensure(extra: Int) {
            if (outLen + extra <= buf.size) return
            var cap = buf.size
            while (outLen + extra > cap) cap *= 2
            buf = buf.copyOf(cap)
        }
        fun writeByte(b: Int) {
            ensure(1)
            buf[outLen++] = b.toByte()
        }
        fun writeBytes(srcArr: ByteArray, from: Int, len: Int) {
            ensure(len)
            System.arraycopy(srcArr, from, buf, outLen, len)
            outLen += len
        }
        var i = 0
        while (i < src.size) {
            val c = src[i].toInt() and 0xFF
            i++
            when {
                c in 0x01..0x08 -> {
                    val n = c
                    val end = (i + n).coerceAtMost(src.size)
                    writeBytes(src, i, end - i)
                    i = end
                }
                c < 0x80 -> writeByte(c)
                c >= 0xC0 -> {
                    writeByte(' '.code)
                    writeByte(c xor 0x80)
                }
                else -> {
                    // 0x80-0xBF：两字节距离/长度
                    if (i >= src.size) break
                    val c2 = src[i].toInt() and 0xFF
                    i++
                    val combined = (c shl 8) or c2
                    val distance = (combined shr 3) and 0x7FF
                    val length = (combined and 7) + 3
                    if (distance == 0 || distance > outLen) {
                        writeByte(c)
                        writeByte(c2)
                        continue
                    }
                    var pos = outLen - distance
                    ensure(length)
                    repeat(length) {
                        if (pos < 0) return@repeat
                        // 允许重叠：边写边读
                        buf[outLen] = buf[pos]
                        outLen++
                        pos++
                    }
                }
            }
        }
        return buf.copyOf(outLen)
    }

    private fun sliceRecord(data: ByteArray, offsets: IntArray, index: Int): ByteArray {
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else data.size
        if (start < 0 || start >= data.size) return ByteArray(0)
        val e = end.coerceIn(start, data.size)
        return data.copyOfRange(start, e)
    }

    private fun u16(data: ByteArray, off: Int): Int {
        if (off + 1 >= data.size) return 0
        return ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
    }

    private fun u32(data: ByteArray, off: Int): Int {
        if (off + 3 >= data.size) return 0
        return ((data[off].toInt() and 0xFF) shl 24) or
            ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or
            (data[off + 3].toInt() and 0xFF)
    }

    // ─── 解析缓存（二次打开直接跳转） ─────────────────────────

    private data class MobiChapterIndex(
        val chapters: List<Chapter>,
        val linkTargets: Map<String, Int>,
    )

    private fun maybeSaveParsedCache(
        file: File,
        book: LoadedBook,
        sourceSize: Long,
        sourceModified: Long,
    ) {
        if (book.paragraphs.size > 12_000) {
            Log.i(TAG, "skip save full cache paras=${book.paragraphs.size}")
            return
        }
        runCatching { saveParsedCache(file, book, sourceSize, sourceModified) }
            .onFailure { Log.w(TAG, "save full cache fail", it) }
        if (file.isFile && file.length() > MAX_FULL_CACHE_BYTES) {
            Log.i(TAG, "full cache too large ${file.length()}B, delete")
            runCatching { file.delete() }
        }
    }

    private fun saveParsedCache(
        file: File,
        book: LoadedBook,
        sourceSize: Long,
        sourceModified: Long,
    ) {
        file.outputStream().buffered().use { out ->
            out.write("MOBIv5".toByteArray(Charsets.US_ASCII))
            writeLong(out, sourceSize)
            writeLong(out, sourceModified)
            writeStr(out, book.title)
            writeStr(out, book.uri)
            writeStr(out, book.encoding)
            writeInt(out, book.imagePaths.size)
            for (p in book.imagePaths) writeStr(out, p)
            writeInt(out, book.chapters.size)
            for (c in book.chapters) {
                writeStr(out, c.title)
                writeInt(out, c.paragraphIndex)
            }
            writeInt(out, book.paragraphs.size)
            for (p in book.paragraphs) {
                writeParagraph(out, p)
            }
            writeInt(out, book.linkTargets.size)
            for ((k, v) in book.linkTargets) {
                writeStr(out, k)
                writeInt(out, v)
            }
        }
    }

    private fun loadParsedCache(
        file: File,
        expectedUri: String,
        sourceSize: Long,
        sourceModified: Long,
    ): LoadedBook? {
        if (!file.isFile || file.length() < 32) return null
        return runCatching {
            file.inputStream().buffered().use { inp ->
                val magic = ByteArray(6)
                if (inp.read(magic) != 6) return null
                val magicStr = String(magic, Charsets.US_ASCII)
                if (magicStr != "MOBIv5" && magicStr != "MOBIv4" &&
                    magicStr != "MOBIv3" && magicStr != "MOBIv2" && magicStr != "MOBIv1"
                ) return null
                if (readLong(inp) != sourceSize || readLong(inp) != sourceModified) return null
                val title = readStr(inp)
                val uri = readStr(inp)
                if (uri != expectedUri) return null
                val encoding = readStr(inp)
                val imgCount = readInt(inp).coerceAtLeast(0)
                val imagePaths = ArrayList<String>(imgCount)
                repeat(imgCount) {
                    val p = readStr(inp)
                    if (p.isNotEmpty()) imagePaths.add(p)
                }
                val chCount = readInt(inp)
                val chapters = ArrayList<Chapter>(chCount.coerceAtLeast(0))
                repeat(chCount) {
                    chapters.add(Chapter(readStr(inp), readInt(inp)))
                }
                val pCount = readInt(inp)
                if (pCount <= 0 || pCount > 5_000_000) return null
                val paras = ArrayList<Paragraph>(pCount)
                repeat(pCount) { i ->
                    paras.add(readParagraph(inp, i))
                }
                val ltCount = readInt(inp).coerceAtLeast(0)
                val linkTargets = LinkedHashMap<String, Int>(ltCount)
                repeat(ltCount) {
                    val k = readStr(inp)
                    val v = readInt(inp)
                    if (k.isNotEmpty()) linkTargets[k] = v
                }
                LoadedBook(
                    title = title,
                    paragraphs = paras,
                    chapters = chapters,
                    encoding = encoding,
                    uri = uri,
                    linkTargets = linkTargets,
                    imagePaths = imagePaths,
                )
            }
        }.onFailure { Log.w(TAG, "parse cache load fail: ${it.message}") }.getOrNull()
    }

    private fun saveMobiChapterIndex(
        file: File,
        uri: String,
        sourceSize: Long,
        sourceModified: Long,
        links: List<MobiAnchorLink>,
        chapters: List<Chapter>,
        linkTargets: Map<String, Int>,
    ) {
        if (chapters.isEmpty()) return
        runCatching {
            val chArr = JSONArray()
            for (i in chapters.indices) {
                val fp = links.getOrNull(i)?.filepos ?: -1
                chArr.put(
                    JSONObject()
                        .put("title", chapters[i].title)
                        .put("para", chapters[i].paragraphIndex)
                        .put("filepos", fp),
                )
            }
            val ltObj = JSONObject()
            for ((k, v) in linkTargets) {
                if (k.startsWith("mobi:filepos:", ignoreCase = true)) {
                    ltObj.put(k, v)
                }
            }
            val o = JSONObject()
                .put("v", 1)
                .put("uri", uri)
                .put("sourceSize", sourceSize)
                .put("sourceModified", sourceModified)
                .put("chapters", chArr)
                .put("linkTargets", ltObj)
            file.writeText(o.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "save chapter index fail", it) }
    }

    private fun loadMobiChapterIndex(
        file: File,
        expectedUri: String,
        sourceSize: Long,
        sourceModified: Long,
    ): MobiChapterIndex {
        if (!file.isFile || file.length() < 8) {
            return MobiChapterIndex(emptyList(), emptyMap())
        }
        return runCatching {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            if (o.optString("uri") != expectedUri) return MobiChapterIndex(emptyList(), emptyMap())
            if (o.optLong("sourceSize") != sourceSize) return MobiChapterIndex(emptyList(), emptyMap())
            if (o.optLong("sourceModified") != sourceModified) {
                return MobiChapterIndex(emptyList(), emptyMap())
            }
            val arr = o.optJSONArray("chapters") ?: return MobiChapterIndex(emptyList(), emptyMap())
            val chapters = ArrayList<Chapter>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val title = c.optString("title").trim()
                if (title.isEmpty()) continue
                chapters += Chapter(title, c.optInt("para", -1))
            }
            val ltObj = o.optJSONObject("linkTargets")
            val linkTargets = LinkedHashMap<String, Int>()
            if (ltObj != null) {
                val keys = ltObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    linkTargets[k] = ltObj.optInt(k, -1)
                }
            }
            MobiChapterIndex(chapters, linkTargets)
        }.getOrDefault(MobiChapterIndex(emptyList(), emptyMap()))
    }

    private fun chunkCacheFile(dir: File, chunkIndex: Int): File =
        File(dir, String.format(Locale.US, "%05d.bin", chunkIndex))

    private fun saveChunkCache(
        dir: File,
        chunkIndex: Int,
        paragraphs: List<Paragraph>,
        from: Int,
        toExclusive: Int,
    ) {
        if (from >= toExclusive || from !in paragraphs.indices) return
        val end = toExclusive.coerceAtMost(paragraphs.size)
        runCatching {
            dir.mkdirs()
            chunkCacheFile(dir, chunkIndex).outputStream().buffered().use { out ->
                out.write("CHK5".toByteArray(Charsets.US_ASCII))
                writeInt(out, end - from)
                for (i in from until end) {
                    writeParagraph(out, paragraphs[i])
                }
            }
        }.onFailure { Log.w(TAG, "save chunk cache $chunkIndex fail", it) }
    }

    private fun loadChunkCache(
        dir: File,
        chunkIndex: Int,
        paragraphs: ArrayList<Paragraph>,
    ): Boolean {
        val f = chunkCacheFile(dir, chunkIndex)
        if (!f.isFile || f.length() < 8) return false
        return runCatching {
            f.inputStream().buffered().use { inp ->
                val magic = ByteArray(4)
                if (inp.read(magic) != 4) return false
                if (String(magic, Charsets.US_ASCII) != "CHK5") return false
                val count = readInt(inp)
                if (count < 0 || count > 500_000) return false
                val base = paragraphs.size
                repeat(count) { i ->
                    paragraphs.add(readParagraph(inp, base + i))
                }
                true
            }
        }.getOrDefault(false)
    }

    private fun writeParagraph(out: java.io.OutputStream, p: Paragraph) {
        writeStr(out, p.text)
        writeInt(out, if (p.isChapter) 1 else 0)
        writeStr(out, p.imagePath.orEmpty())
        writeInt(
            out,
            when (p.align) {
                com.whj.reader.model.TextAlign.CENTER -> 1
                com.whj.reader.model.TextAlign.END -> 2
                else -> 0
            },
        )
        writeInt(out, if (p.preformatted) 1 else 0)
        writeInt(out, p.spans.size)
        for (s in p.spans) {
            writeInt(out, s.start)
            writeInt(out, s.end)
            writeInt(
                out,
                (if (s.bold) 1 else 0) or
                    (if (s.italic) 2 else 0) or
                    (if (s.underline) 4 else 0),
            )
            writeInt(out, s.color ?: Int.MIN_VALUE)
            writeInt(out, s.backgroundColor ?: Int.MIN_VALUE)
            writeStr(out, s.linkHref.orEmpty())
        }
        writeInt(out, p.inlineImages.size)
        for (im in p.inlineImages) {
            writeInt(out, im.start)
            writeInt(out, im.end)
            writeStr(out, im.path)
        }
    }

    private fun readParagraph(inp: InputStream, index: Int): Paragraph {
        val text = readStr(inp)
        val isCh = readInt(inp) == 1
        val img = readStr(inp).ifEmpty { null }
        val alignCode = readInt(inp)
        val pre = readInt(inp) == 1
        val align = when (alignCode) {
            1 -> com.whj.reader.model.TextAlign.CENTER
            2 -> com.whj.reader.model.TextAlign.END
            else -> com.whj.reader.model.TextAlign.START
        }
        val sc = readInt(inp).coerceAtLeast(0)
        val spans = ArrayList<com.whj.reader.model.TextSpanStyle>(sc)
        repeat(sc) {
            val start = readInt(inp)
            val end = readInt(inp)
            val flags = readInt(inp)
            val col = readInt(inp).let { v -> if (v == Int.MIN_VALUE) null else v }
            val bg = readInt(inp).let { v -> if (v == Int.MIN_VALUE) null else v }
            val href = readStr(inp).ifEmpty { null }
            spans.add(
                com.whj.reader.model.TextSpanStyle(
                    start = start,
                    end = end,
                    bold = flags and 1 != 0,
                    italic = flags and 2 != 0,
                    underline = flags and 4 != 0,
                    color = col,
                    backgroundColor = bg,
                    linkHref = href,
                ),
            )
        }
        val ic = readInt(inp).coerceAtLeast(0)
        val inlines = ArrayList<InlineImage>(ic)
        repeat(ic) {
            val start = readInt(inp)
            val end = readInt(inp)
            val path = readStr(inp)
            if (path.isNotEmpty()) inlines.add(InlineImage(start, end, path))
        }
        return Paragraph(
            index = index,
            text = text,
            isChapter = isCh,
            spans = spans,
            imagePath = img,
            inlineImages = inlines,
            align = align,
            preformatted = pre,
        )
    }

    private fun writeStr(out: java.io.OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(out, b.size)
        out.write(b)
    }

    private fun readStr(inp: InputStream): String {
        val n = readInt(inp)
        if (n < 0 || n > 50_000_000) error("bad str len")
        if (n == 0) return ""
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val k = inp.read(b, off, n - off)
            if (k < 0) error("eof")
            off += k
        }
        return String(b, Charsets.UTF_8)
    }

    private fun writeInt(out: java.io.OutputStream, v: Int) {
        out.write(v ushr 24)
        out.write(v ushr 16)
        out.write(v ushr 8)
        out.write(v)
    }

    private fun readInt(inp: InputStream): Int {
        val a = inp.read()
        val b = inp.read()
        val c = inp.read()
        val d = inp.read()
        if (a or b or c or d < 0) error("eof int")
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    private fun writeLong(out: java.io.OutputStream, v: Long) {
        writeInt(out, (v ushr 32).toInt())
        writeInt(out, v.toInt())
    }

    private fun readLong(inp: InputStream): Long {
        val hi = readInt(inp).toLong() and 0xffffffffL
        val lo = readInt(inp).toLong() and 0xffffffffL
        return (hi shl 32) or lo
    }

    private fun cacheKeyFor(uri: String, name: String): String {
        val md = MessageDigest.getInstance("MD5")
        val dig = md.digest((uri + "|" + name).toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }
}
