package com.whj.reader.data

import android.content.Context
import android.net.Uri
import com.whj.reader.model.Chapter
import com.whj.reader.model.InlineImage
import com.whj.reader.model.Paragraph
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 简易 MOBI/PRC 加载：PalmDOC 解压 + HTML 子集解析。
 * 首屏只解析前几块 HTML，其余 [BookStreamer] 后台续载。
 */
object MobiLoader {

    private const val FIRST_MIN_PARAS = 24
    private const val FIRST_MAX_CHUNKS = 3

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
        if (rec0.size >= 0x20 && String(rec0, 16, 4, Charsets.US_ASCII) == "MOBI") {
            val headerLen = u32(rec0, 20)
            mobiType = u32(rec0, 24)
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
        var html = String(rawText.toByteArray(), Charsets.UTF_8)
        // 某些 MOBI 为 Windows-1252 / GBK
        if (html.indexOf('\uFFFD') >= 0 || looksLikeGarbled(html)) {
            val alt = runCatching {
                String(rawText.toByteArray(), charset("GB18030"))
            }.getOrNull()
            if (alt != null && !looksLikeGarbled(alt)) html = alt
        }

        // 截到 trailing 二进制前（常见 <mbp:pagebreak/> 后）
        html = stripMobiTrailingBinary(html)

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
        val paragraphs = ArrayList<Paragraph>(256)
        val chapters = ArrayList<Chapter>()
        val linkTargets = LinkedHashMap<String, Int>(256)
        var chunkIdx = 0
        var textParas = 0
        while (chunkIdx < chunks.size &&
            chunkIdx < FIRST_MAX_CHUNKS &&
            textParas < FIRST_MIN_PARAS
        ) {
            textParas += appendBlocks(
                HtmlRichParser.parse(chunks[chunkIdx]),
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
            return BookOpenResult(firstBook, null)
        }

        val streamer = MobiStreamer(
            chunks = chunks,
            nextChunk = chunkIdx,
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
                    appendBlocks(
                        HtmlRichParser.parse(chunks[nextChunk]),
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
            } catch (_: Exception) {
                finished = true
                onComplete?.invoke(snapshot(complete = true))
                false
            }
        }

        private fun finishComplete() {
            if (finished) return
            finished = true
            reindexParas(paragraphs)
            onProgress?.invoke("完成", chunks.size, chunks.size)
            onComplete?.invoke(snapshot(complete = true))
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

    private fun stripMobiTrailingBinary(html: String): String {
        // 常见：HTML 后跟 \u0000 填充
        val nul = html.indexOf('\u0000')
        if (nul > 100) return html.substring(0, nul)
        return html
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
