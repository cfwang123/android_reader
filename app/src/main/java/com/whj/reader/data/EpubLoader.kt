package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.whj.reader.model.Chapter
import com.whj.reader.model.InlineImage
import com.whj.reader.model.Paragraph
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * EPUB → [LoadedBook]（段落流 + 富文本 span + 图片缓存路径）。
 *
 * **流式首屏**：
 * 1. 缓存命中 → 整本秒开
 * 2. 否则：索引 OPF + 只解析前几章 → 立即返回可显示正文
 * 3. 后台 [BookStreamer] 续解析其余 spine，完成后写磁盘缓存
 */
object EpubLoader {

    private const val TAG = "EpubLoader"
    /** 首屏：至少有这些段再返回（封面图不算） */
    private const val FIRST_MIN_TEXT_PARAS = 24
    /** 首屏：最多扫这么多 spine，避免封面连环图拖死 */
    private const val FIRST_MAX_SPINES = 12
    /** 首屏时间预算（含解析） */
    private const val FIRST_BUDGET_MS = 350L
    /** 后台每解析多少 spine 回调一次 UI */
    private const val BATCH_SPINES = 8

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
        val workDir = File(context.cacheDir, "ebooks/epub/$cacheKey").apply { mkdirs() }
        val epubFile = File(workDir, "book.epub")
        if (!epubFile.exists() || epubFile.length() == 0L) {
            val t0 = System.currentTimeMillis()
            onProgress?.invoke(
                context.getString(com.whj.reader.R.string.load_stage_copy),
                0,
                0,
            )
            workDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("parsed_")) f.delete()
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(epubFile).use { out -> copyStream(input, out) }
            } ?: error("无法打开 EPUB")
            Log.i(TAG, "copy epub ${epubFile.length()}B in ${System.currentTimeMillis() - t0}ms")
        }
        val coverDest = CoverStore.fileFor(context, uri.toString())
        return openFromFile(
            epubFile, workDir, titleHint, uri.toString(), chineseMode, coverDest, onProgress,
        )
    }

    fun openFromFile(
        epubFile: File,
        workDir: File,
        titleHint: String,
        uriStr: String,
        chineseMode: ChineseConvert.Mode,
        coverDest: File? = null,
        onProgress: LoadProgressListener? = null,
    ): BookOpenResult {
        val tAll = System.currentTimeMillis()
        // v7：表格列对齐
        val parsedCache = File(workDir, "parsed_${chineseMode.name}_v7.bin")
        onProgress?.invoke("读取缓存…", 0, 0)
        loadParsedCache(parsedCache, uriStr)?.let { cached ->
            Log.i(TAG, "hit parse cache paras=${cached.paragraphs.size} in ${System.currentTimeMillis() - tAll}ms")
            onProgress?.invoke("完成", 1, 1)
            return BookOpenResult(book = cached.copy(isComplete = true), streamer = null)
        }

        onProgress?.invoke("打开 EPUB…", 0, 0)
        val zip = ZipFile(epubFile)
        try {
            val t0 = System.currentTimeMillis()
            val index = ZipIndex.build(zip)
            Log.i(TAG, "zip index entries=${index.size} in ${System.currentTimeMillis() - t0}ms")

            val container = readZipText(index, zip, "META-INF/container.xml")
                ?: error("无效 EPUB：缺少 container.xml")
            val opfPath = parseRootFilePath(container)
                ?: error("无效 EPUB：无法定位 OPF")
            val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
            val opfXml = readZipText(index, zip, opfPath) ?: error("无效 EPUB：无法读取 OPF")
            val opf = parseOpf(opfXml)
            val title = opf.title?.takeIf { it.isNotBlank() }
                ?: titleHint.removeSuffix(".epub").removeSuffix(".EPUB")
            val spine = opf.spineHrefs
            if (spine.isEmpty()) error("EPUB 无 spine")

            val imgDir = File(workDir, "images").apply { mkdirs() }
            val paragraphs = ArrayList<Paragraph>(512)
            val headingChapters = ArrayList<Chapter>(64)
            val navChapters = parseNavChapters(index, zip, opf, opfDir)
            val navTitleKeys = HashSet<String>(navChapters.size * 2)
            for (n in navChapters) {
                val t = n.title.trim()
                if (t.isNotEmpty()) {
                    navTitleKeys.add(t)
                    if (t.length >= 12) navTitleKeys.add(t.take(12))
                }
            }
            val linkTargets = LinkedHashMap<String, Int>(512)

            // 封面：OPF cover-image / meta cover → 块图段 + 书架缓存
            val coverLocal = extractCoverImage(index, zip, opf, opfDir, imgDir)
            if (coverLocal != null) {
                if (coverDest != null && coverDest.absolutePath != coverLocal.absolutePath) {
                    runCatching {
                        coverDest.parentFile?.mkdirs()
                        coverLocal.inputStream().use { input ->
                            FileOutputStream(coverDest).use { out -> copyStream(input, out) }
                        }
                    }
                }
                paragraphs.add(
                    Paragraph(
                        index = 0,
                        text = "",
                        imagePath = coverLocal.absolutePath,
                    ),
                )
            }

            // 首屏：快速解析前几章
            val tFirst = System.currentTimeMillis()
            var nextSpine = 0
            var textParas = 0
            while (nextSpine < spine.size &&
                nextSpine < FIRST_MAX_SPINES &&
                (textParas < FIRST_MIN_TEXT_PARAS || nextSpine < 2) &&
                System.currentTimeMillis() - tFirst < FIRST_BUDGET_MS
            ) {
                val added = appendSpine(
                    index, zip, opfDir, spine[nextSpine], imgDir,
                    chineseMode, navTitleKeys, paragraphs, headingChapters, linkTargets,
                )
                nextSpine++
                textParas += added
            }
            // 若预算内几乎无正文（全封面），再硬解几章
            while (textParas < 8 && nextSpine < spine.size && nextSpine < FIRST_MAX_SPINES + 8) {
                textParas += appendSpine(
                    index, zip, opfDir, spine[nextSpine], imgDir,
                    chineseMode, navTitleKeys, paragraphs, headingChapters, linkTargets,
                )
                nextSpine++
            }

            if (paragraphs.isEmpty()) {
                zip.close()
                error("EPUB 中未解析到正文")
            }

            reindex(paragraphs)
            val injectedNavKeys = HashSet<String>(navChapters.size)
            injectTocTitleLines(
                paragraphs = paragraphs,
                headingChapters = headingChapters,
                navChapters = navChapters,
                linkTargets = linkTargets,
                chineseMode = chineseMode,
                alreadyInjected = injectedNavKeys,
            )
            reindex(paragraphs)
            val firstBook = buildBook(
                title = title,
                uriStr = uriStr,
                paragraphs = paragraphs,
                headingChapters = headingChapters,
                navChapters = navChapters,
                complete = nextSpine >= spine.size,
                streamCurrent = nextSpine,
                streamTotal = spine.size,
                linkTargets = linkTargets,
            )
            Log.i(
                TAG,
                "first screen spines=$nextSpine/${spine.size} paras=${paragraphs.size} " +
                    "nav=${navChapters.size} injected=${injectedNavKeys.size} " +
                    "in ${System.currentTimeMillis() - tAll}ms",
            )
            onProgress?.invoke("显示正文…", nextSpine, spine.size)

            if (nextSpine >= spine.size) {
                runCatching { saveParsedCache(parsedCache, firstBook) }
                zip.close()
                onProgress?.invoke("完成", 1, 1)
                return BookOpenResult(firstBook, null)
            }

            val streamer = EpubStreamer(
                zip = zip,
                index = index,
                opfDir = opfDir,
                spine = spine,
                nextSpine = nextSpine,
                imgDir = imgDir,
                chineseMode = chineseMode,
                navTitleKeys = navTitleKeys,
                paragraphs = paragraphs,
                headingChapters = headingChapters,
                navChapters = navChapters,
                linkTargets = linkTargets,
                title = title,
                uriStr = uriStr,
                parsedCache = parsedCache,
                injectedNavKeys = injectedNavKeys,
            )
            return BookOpenResult(firstBook, streamer)
        } catch (e: Exception) {
            runCatching { zip.close() }
            throw e
        }
    }

    /** 阻塞整本（兼容） */
    fun loadFromUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        chineseMode: ChineseConvert.Mode = ChineseConvert.Mode.OFF,
        onProgress: LoadProgressListener? = null,
    ): LoadedBook {
        val open = openFromUri(context, uri, displayName, chineseMode, onProgress)
        return if (open.streamer == null) open.book else drainStreamer(open)
    }

    private fun drainStreamer(open: BookOpenResult): LoadedBook {
        val streamer = open.streamer ?: return open.book
        var latest = open.book
        val lock = Object()
        var done = false
        streamer.start(
            onUpdate = { b -> synchronized(lock) { latest = b } },
            onComplete = { b ->
                synchronized(lock) {
                    latest = b
                    done = true
                    lock.notifyAll()
                }
            },
            onProgress = null,
        )
        synchronized(lock) {
            while (!done) {
                try {
                    lock.wait(100)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return latest
    }

    /**
     * @return 本 spine 新增的「有正文」段落数
     */
    private fun appendSpine(
        index: ZipIndex,
        zip: ZipFile,
        opfDir: String,
        href: String,
        imgDir: File,
        chineseMode: ChineseConvert.Mode,
        navTitleKeys: MutableSet<String>,
        paragraphs: ArrayList<Paragraph>,
        chapters: ArrayList<Chapter>,
        linkTargets: MutableMap<String, Int>,
    ): Int {
        val entryPath = resolveZipPath(opfDir, href.substringBefore('#'))
        val html = readZipText(index, zip, entryPath) ?: return 0
        val resolvedEntry = index.resolve(entryPath) ?: entryPath
        val baseDir = resolvedEntry.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = resolvedEntry.substringAfterLast('/')
        val firstParaOfFile = paragraphs.size
        // 文件级锚点：path / 文件名 → 该 spine 首段
        putLinkTarget(linkTargets, entryPath, firstParaOfFile)
        putLinkTarget(linkTargets, resolvedEntry, firstParaOfFile)
        putLinkTarget(linkTargets, fileName, firstParaOfFile)

        val blocks = HtmlRichParser.parse(html)
        var textAdded = 0
        for (block in blocks) {
            if (!block.imageSrc.isNullOrBlank()) {
                val local = extractImage(index, zip, baseDir, block.imageSrc, imgDir) ?: continue
                val idx = paragraphs.size
                paragraphs.add(
                    Paragraph(
                        index = idx,
                        text = "",
                        imagePath = local.absolutePath,
                        imageDisplaySize = block.imageDisplaySize,
                    ),
                )
                registerBlockAnchors(linkTargets, block, idx, entryPath, fileName, resolvedEntry)
                continue
            }
            var text = block.text
            if (text.isBlank()) {
                // 仅锚点占位
                if (block.anchors.isNotEmpty()) {
                    val idx = paragraphs.size
                    paragraphs.add(Paragraph(index = idx, text = "\u200B"))
                    registerBlockAnchors(linkTargets, block, idx, entryPath, fileName, resolvedEntry)
                }
                continue
            }
            if (chineseMode != ChineseConvert.Mode.OFF) {
                text = ChineseConvert.apply(text, chineseMode)
            }
            val spans = if (chineseMode == ChineseConvert.Mode.OFF || text == block.text) {
                block.spans
            } else {
                emptyList()
            }
            val inlines = resolveInlineImages(index, zip, baseDir, block.inlineImages, imgDir)
            val firstLine = text.lineSequence().first().take(80)
            val isChapter = block.isChapter ||
                firstLine in navTitleKeys ||
                (firstLine.length >= 20 && firstLine.take(20) in navTitleKeys)
            val idx = paragraphs.size
            paragraphs.add(
                Paragraph(
                    index = idx,
                    text = text,
                    isChapter = isChapter,
                    spans = spans,
                    inlineImages = inlines,
                    align = block.align,
                    preformatted = block.preformatted,
                ),
            )
            registerBlockAnchors(linkTargets, block, idx, entryPath, fileName, resolvedEntry)
            textAdded++
            if (isChapter) {
                chapters.add(Chapter(title = firstLine.take(60), paragraphIndex = idx))
            }
        }
        return textAdded
    }

    private fun putLinkTarget(map: MutableMap<String, Int>, key: String, para: Int) {
        val k = key.trim()
        if (k.isEmpty()) return
        map[k] = para
        map[k.lowercase(Locale.ROOT)] = para
    }

    private fun registerBlockAnchors(
        map: MutableMap<String, Int>,
        block: HtmlRichParser.ParsedBlock,
        paraIndex: Int,
        entryPath: String,
        fileName: String,
        resolvedEntry: String,
    ) {
        for (a in block.anchors) {
            val id = a.id
            putLinkTarget(map, id, paraIndex)
            putLinkTarget(map, "$fileName#$id", paraIndex)
            putLinkTarget(map, "$entryPath#$id", paraIndex)
            putLinkTarget(map, "$resolvedEntry#$id", paraIndex)
            // OEBPS/foo.html#id 等路径变体
            if (resolvedEntry.contains('/')) {
                putLinkTarget(map, resolvedEntry.substringAfter('/'), paraIndex)
                putLinkTarget(map, "${resolvedEntry.substringAfter('/')}#$id", paraIndex)
            }
        }
    }

    private fun reindex(paragraphs: ArrayList<Paragraph>) {
        for (i in paragraphs.indices) {
            if (paragraphs[i].index != i) {
                paragraphs[i] = paragraphs[i].copy(index = i)
            }
        }
    }

    private fun buildBook(
        title: String,
        uriStr: String,
        paragraphs: List<Paragraph>,
        headingChapters: List<Chapter>,
        navChapters: List<NavChapter>,
        complete: Boolean,
        streamCurrent: Int,
        streamTotal: Int,
        linkTargets: Map<String, Int>,
    ): LoadedBook {
        val fromNav = resolveNavToChapters(navChapters, linkTargets, paragraphs)
        val ch = when {
            fromNav.isNotEmpty() -> fromNav
            else -> headingChapters
                .map { Chapter(it.title, it.paragraphIndex.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0))) }
                .distinctBy { it.paragraphIndex }
                .ifEmpty {
                    paragraphs.filter { it.isChapter }.map {
                        Chapter(it.text.lineSequence().first().take(60), it.index)
                    }
                }
        }
        return LoadedBook(
            title = title,
            paragraphs = paragraphs.toList(),
            chapters = ch,
            encoding = "UTF-8",
            uri = uriStr,
            isComplete = complete,
            streamCurrent = streamCurrent,
            streamTotal = streamTotal.coerceAtLeast(1),
            linkTargets = linkTargets.toMap(),
        )
    }

    /**
     * 在每个 TOC 目标段落**之前**插入一行 TOC 标题（[Paragraph.isChapter]）。
     * 流式加载时可反复调用；[alreadyInjected] 防止重复插入。
     * 正文已有相同标题则跳过。
     */
    private fun injectTocTitleLines(
        paragraphs: ArrayList<Paragraph>,
        headingChapters: ArrayList<Chapter>,
        navChapters: List<NavChapter>,
        linkTargets: MutableMap<String, Int>,
        chineseMode: ChineseConvert.Mode,
        alreadyInjected: MutableSet<String>,
    ) {
        if (navChapters.isEmpty() || paragraphs.isEmpty()) return

        data class Pending(val at: Int, val title: String, val key: String)

        val pending = ArrayList<Pending>(navChapters.size)
        for (n in navChapters) {
            val key = navKey(n)
            if (key in alreadyInjected) continue
            val title = n.title.trim()
            if (title.isEmpty()) continue
            val para = lookupNavParagraph(n, linkTargets) ?: continue
            if (para !in paragraphs.indices) continue

            val targetText = paragraphs[para].text.lineSequence().firstOrNull()?.trim().orEmpty()
            if (tocTitleMatchesBody(title, targetText)) {
                // 正文已有该标题，不重复插入
                alreadyInjected.add(key)
                continue
            }
            if (para > 0) {
                val prev = paragraphs[para - 1]
                if (prev.isChapter && tocTitleMatchesBody(title, prev.text.trim())) {
                    alreadyInjected.add(key)
                    continue
                }
            }
            pending.add(Pending(para, title, key))
        }
        if (pending.isEmpty()) return

        // 从后往前插，避免打乱尚未处理的 at
        pending.sortByDescending { it.at }
        for (ins in pending) {
            var t = ins.title
            if (chineseMode != ChineseConvert.Mode.OFF) {
                t = ChineseConvert.apply(t, chineseMode)
            }
            val at = ins.at.coerceIn(0, paragraphs.size)
            paragraphs.add(
                at,
                Paragraph(
                    index = at,
                    text = t,
                    isChapter = true,
                ),
            )
            // 其后所有段索引 +1
            for ((k, v) in linkTargets.entries.toList()) {
                if (v >= at) linkTargets[k] = v + 1
            }
            // 目录跳转落在标题行
            putLinkTarget(linkTargets, nPathKey(ins.key), at)
            // 启发式章节表同步偏移
            for (i in headingChapters.indices) {
                val c = headingChapters[i]
                if (c.paragraphIndex >= at) {
                    headingChapters[i] = Chapter(c.title, c.paragraphIndex + 1)
                }
            }
            alreadyInjected.add(ins.key)
        }
        reindex(paragraphs)
        Log.i(TAG, "inject TOC titles +${pending.size} (total injected=${alreadyInjected.size})")
    }

    private fun navKey(n: NavChapter): String =
        "${n.hrefPath}\u0000${n.fragment}"

    /** 从 navKey 取 path 用于 putLinkTarget（可选） */
    private fun nPathKey(key: String): String {
        val path = key.substringBefore('\u0000')
        val frag = key.substringAfter('\u0000', "")
        return if (frag.isEmpty()) path else "$path#$frag"
    }

    private fun tocTitleMatchesBody(tocTitle: String, bodyFirstLine: String): Boolean {
        if (bodyFirstLine.isEmpty()) return false
        if (bodyFirstLine == tocTitle) return true
        // 正文标题常略短/带序号差异
        val a = tocTitle.replace(Regex("\\s+"), "")
        val b = bodyFirstLine.replace(Regex("\\s+"), "")
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        return false
    }

    /** 将 NCX/nav 条目解析到段落索引（优先 path#id，其次文件首段；优先 TOC 注入标题行） */
    private fun resolveNavToChapters(
        nav: List<NavChapter>,
        linkTargets: Map<String, Int>,
        paragraphs: List<Paragraph>,
    ): List<Chapter> {
        if (nav.isEmpty() || paragraphs.isEmpty()) return emptyList()
        val last = paragraphs.lastIndex
        val out = ArrayList<Chapter>(nav.size)
        val seenPara = HashSet<Int>()
        for (n in nav) {
            val title = n.title.trim()
            if (title.isEmpty()) continue
            var para = lookupNavParagraph(n, linkTargets) ?: continue
            para = para.coerceIn(0, last)
            // 若目标正文前一行是我们注入的同名 TOC 标题，目录跳到标题
            if (para > 0) {
                val prev = paragraphs[para - 1]
                if (prev.isChapter && tocTitleMatchesBody(title, prev.text.trim())) {
                    para = para - 1
                }
            }
            // 或目标本身已是标题
            if (paragraphs[para].isChapter && tocTitleMatchesBody(title, paragraphs[para].text.trim())) {
                // keep
            }
            if (!seenPara.add(para)) continue
            out.add(Chapter(title.take(80), para))
        }
        return out.sortedBy { it.paragraphIndex }
    }

    private fun lookupNavParagraph(n: NavChapter, linkTargets: Map<String, Int>): Int? {
        val path = n.hrefPath
        val frag = n.fragment
        fun get(key: String): Int? {
            if (key.isEmpty()) return null
            return linkTargets[key] ?: linkTargets[key.lowercase(Locale.ROOT)]
        }
        if (frag.isNotEmpty()) {
            get("$path#$frag")?.let { return it }
            get("${path.substringAfterLast('/')}#$frag")?.let { return it }
            get(frag)?.let { return it }
        }
        get(path)?.let { return it }
        get(path.substringAfterLast('/'))?.let { return it }
        // 去后缀再试
        val noExt = path.substringBeforeLast('.')
        if (noExt != path) get(noExt)?.let { return it }
        return null
    }

    private class EpubStreamer(
        private val zip: ZipFile,
        private val index: ZipIndex,
        private val opfDir: String,
        private val spine: List<String>,
        private var nextSpine: Int,
        private val imgDir: File,
        private val chineseMode: ChineseConvert.Mode,
        private val navTitleKeys: MutableSet<String>,
        private val paragraphs: ArrayList<Paragraph>,
        private val headingChapters: ArrayList<Chapter>,
        private val navChapters: List<NavChapter>,
        private val linkTargets: MutableMap<String, Int>,
        private val title: String,
        private val uriStr: String,
        private val parsedCache: File,
        private val injectedNavKeys: MutableSet<String>,
    ) : BookStreamer {
        @Volatile
        private var cancelled = false
        private var thread: Thread? = null

        private fun injectAndBuild(complete: Boolean, streamCurrent: Int): LoadedBook {
            reindex(paragraphs)
            injectTocTitleLines(
                paragraphs = paragraphs,
                headingChapters = headingChapters,
                navChapters = navChapters,
                linkTargets = linkTargets,
                chineseMode = chineseMode,
                alreadyInjected = injectedNavKeys,
            )
            reindex(paragraphs)
            return buildBook(
                title, uriStr, paragraphs, headingChapters, navChapters,
                complete = complete,
                streamCurrent = streamCurrent,
                streamTotal = spine.size,
                linkTargets = linkTargets,
            )
        }

        override fun start(
            onUpdate: (LoadedBook) -> Unit,
            onComplete: (LoadedBook) -> Unit,
            onProgress: LoadProgressListener?,
        ) {
            if (thread != null) return
            thread = Thread({
                try {
                    var batch = 0
                    while (!cancelled && nextSpine < spine.size) {
                        appendSpine(
                            index, zip, opfDir, spine[nextSpine], imgDir,
                            chineseMode, navTitleKeys, paragraphs, headingChapters, linkTargets,
                        )
                        nextSpine++
                        batch++
                        if (batch >= BATCH_SPINES || nextSpine >= spine.size) {
                            batch = 0
                            val partial = injectAndBuild(
                                complete = false,
                                streamCurrent = nextSpine,
                            )
                            onProgress?.invoke(
                                "后台加载 ${nextSpine}/${spine.size}",
                                nextSpine,
                                spine.size,
                            )
                            if (!cancelled) onUpdate(partial)
                        }
                    }
                    val finalBook = injectAndBuild(
                        complete = true,
                        streamCurrent = spine.size,
                    )
                    if (!cancelled) {
                        runCatching { saveParsedCache(parsedCache, finalBook) }
                        onProgress?.invoke("完成", spine.size, spine.size)
                        onComplete(finalBook)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stream fail", e)
                    val partial = runCatching {
                        injectAndBuild(complete = true, streamCurrent = nextSpine)
                    }.getOrElse {
                        reindex(paragraphs)
                        buildBook(
                            title, uriStr, paragraphs, headingChapters, navChapters,
                            complete = true,
                            streamCurrent = nextSpine,
                            streamTotal = spine.size,
                            linkTargets = linkTargets,
                        )
                    }
                    if (!cancelled) onComplete(partial)
                } finally {
                    runCatching { zip.close() }
                }
            }, "EpubStreamer").also { it.start() }
        }

        override fun cancel() {
            cancelled = true
            thread?.interrupt()
        }
    }

    private fun resolveInlineImages(
        index: ZipIndex,
        zip: ZipFile,
        baseDir: String,
        refs: List<HtmlRichParser.InlineImageRef>,
        imgDir: File,
    ): List<InlineImage> {
        if (refs.isEmpty()) return emptyList()
        val out = ArrayList<InlineImage>(refs.size)
        for (r in refs) {
            val local = extractImage(index, zip, baseDir, r.src, imgDir) ?: continue
            out.add(
                InlineImage(
                    start = r.start,
                    end = r.end,
                    path = local.absolutePath,
                    displaySize = r.displaySize,
                ),
            )
        }
        return out
    }

    // ─── ZIP 索引 ───────────────────────────────────────────

    /**
     * 一次性索引：精确路径 / 小写路径 / 文件名 → entry 名。
     * 避免对每个 spine 做 O(n) 全表扫描（大 EPUB 可从数分钟降到数秒内）。
     */
    private class ZipIndex private constructor(
        private val exact: HashMap<String, String>,
        private val lower: HashMap<String, String>,
        private val byFileName: HashMap<String, String>,
        val size: Int,
    ) {
        fun resolve(path: String): String? {
            val target = normalizePath(path)
            if (target.isEmpty()) return null
            exact[target]?.let { return it }
            lower[target.lowercase(Locale.ROOT)]?.let { return it }
            // getEntry 风格：带/不带前导
            exact[target.trimStart('/')]?.let { return it }
            val file = target.substringAfterLast('/')
            if (file.isNotEmpty()) {
                byFileName[file.lowercase(Locale.ROOT)]?.let { return it }
            }
            return null
        }

        companion object {
            fun build(zip: ZipFile): ZipIndex {
                val exact = HashMap<String, String>(zip.size().coerceAtLeast(16))
                val lower = HashMap<String, String>(zip.size().coerceAtLeast(16))
                val byFile = HashMap<String, String>(zip.size().coerceAtLeast(16))
                val entries = zip.entries()
                var n = 0
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    n++
                    val name = e.name.replace('\\', '/')
                    val norm = normalizePath(name)
                    exact[norm] = name
                    exact[name] = name
                    lower[norm.lowercase(Locale.ROOT)] = name
                    val fn = norm.substringAfterLast('/').lowercase(Locale.ROOT)
                    if (fn.isNotEmpty() && !byFile.containsKey(fn)) {
                        byFile[fn] = name
                    }
                }
                return ZipIndex(exact, lower, byFile, n)
            }
        }
    }

    // ─── 解析缓存（二次打开） ───────────────────────────────

    private fun saveParsedCache(file: File, book: LoadedBook) {
        // 简易文本缓存：避免引入序列化库。大书可能 几十 MB，仍远快于重解析。
        // 格式：v1\n title \n uri \n chapterCount \n ... \n paraCount \n each para
        file.outputStream().buffered().use { out ->
            fun w(s: String) {
                val b = s.toByteArray(Charsets.UTF_8)
                writeInt(out, b.size)
                out.write(b)
            }
            out.write("EPUBv5".toByteArray(Charsets.US_ASCII))
            w(book.title)
            w(book.uri)
            w(book.encoding)
            writeInt(out, book.chapters.size)
            for (c in book.chapters) {
                w(c.title)
                writeInt(out, c.paragraphIndex)
            }
            writeInt(out, book.paragraphs.size)
            for (p in book.paragraphs) {
                w(p.text)
                writeInt(out, if (p.isChapter) 1 else 0)
                w(p.imagePath.orEmpty())
                // align: 0 start 1 center 2 end；pre bit in flags
                writeInt(out, when (p.align) {
                    com.whj.reader.model.TextAlign.START -> 0
                    com.whj.reader.model.TextAlign.CENTER -> 1
                    com.whj.reader.model.TextAlign.END -> 2
                })
                writeInt(out, if (p.preformatted) 1 else 0)
                // spans：数量 + 各字段
                writeInt(out, p.spans.size)
                for (s in p.spans) {
                    writeInt(out, s.start)
                    writeInt(out, s.end)
                    writeInt(out, (if (s.bold) 1 else 0) or (if (s.italic) 2 else 0) or (if (s.underline) 4 else 0))
                    writeInt(out, s.color ?: Int.MIN_VALUE)
                    writeInt(out, s.backgroundColor ?: Int.MIN_VALUE)
                    w(s.linkHref.orEmpty())
                }
                writeInt(out, p.inlineImages.size)
                for (im in p.inlineImages) {
                    writeInt(out, im.start)
                    writeInt(out, im.end)
                    w(im.path)
                }
            }
            writeInt(out, book.linkTargets.size)
            for ((k, v) in book.linkTargets) {
                w(k)
                writeInt(out, v)
            }
        }
    }

    private fun loadParsedCache(file: File, expectedUri: String): LoadedBook? {
        if (!file.isFile || file.length() < 16) return null
        return runCatching {
            file.inputStream().buffered().use { inp ->
                val magic = ByteArray(6)
                if (inp.read(magic) != 6) return null
                if (String(magic, Charsets.US_ASCII) != "EPUBv5") return null
                fun r(): String {
                    val n = readInt(inp)
                    if (n < 0 || n > 50_000_000) error("bad str len")
                    val b = ByteArray(n)
                    var off = 0
                    while (off < n) {
                        val k = inp.read(b, off, n - off)
                        if (k < 0) error("eof")
                        off += k
                    }
                    return String(b, Charsets.UTF_8)
                }
                val title = r()
                val uri = r()
                if (uri != expectedUri) return null
                val encoding = r()
                val chCount = readInt(inp)
                val chapters = ArrayList<Chapter>(chCount.coerceAtLeast(0))
                repeat(chCount) {
                    val ct = r()
                    val pi = readInt(inp)
                    chapters.add(Chapter(ct, pi))
                }
                val pCount = readInt(inp)
                if (pCount <= 0 || pCount > 5_000_000) return null
                val paras = ArrayList<Paragraph>(pCount)
                val spanBuf = ArrayList<com.whj.reader.model.TextSpanStyle>(8)
                val inlineBuf = ArrayList<InlineImage>(4)
                repeat(pCount) { i ->
                    val text = r()
                    val isCh = readInt(inp) == 1
                    val img = r().ifEmpty { null }
                    val alignCode = readInt(inp)
                    val pre = readInt(inp) == 1
                    val align = when (alignCode) {
                        1 -> com.whj.reader.model.TextAlign.CENTER
                        2 -> com.whj.reader.model.TextAlign.END
                        else -> com.whj.reader.model.TextAlign.START
                    }
                    val sc = readInt(inp).coerceAtLeast(0)
                    spanBuf.clear()
                    repeat(sc) {
                        val start = readInt(inp)
                        val end = readInt(inp)
                        val flags = readInt(inp)
                        val col = readInt(inp).let { if (it == Int.MIN_VALUE) null else it }
                        val bg = readInt(inp).let { if (it == Int.MIN_VALUE) null else it }
                        val href = r().ifEmpty { null }
                        spanBuf.add(
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
                    inlineBuf.clear()
                    repeat(ic) {
                        val start = readInt(inp)
                        val end = readInt(inp)
                        val path = r()
                        if (path.isNotEmpty()) {
                            inlineBuf.add(InlineImage(start, end, path))
                        }
                    }
                    paras.add(
                        Paragraph(
                            index = i,
                            text = text,
                            isChapter = isCh,
                            spans = if (spanBuf.isEmpty()) emptyList() else spanBuf.toList(),
                            imagePath = img,
                            inlineImages = if (inlineBuf.isEmpty()) emptyList() else inlineBuf.toList(),
                            align = align,
                            preformatted = pre,
                        ),
                    )
                }
                val ltCount = readInt(inp).coerceAtLeast(0)
                val linkTargets = LinkedHashMap<String, Int>(ltCount)
                repeat(ltCount) {
                    val k = r()
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
                )
            }
        }.onFailure { Log.w(TAG, "parse cache load fail: ${it.message}") }.getOrNull()
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

    // ─── OPF / 目录 ─────────────────────────────────────────

    private data class OpfData(
        val title: String?,
        val spineHrefs: List<String>,
        val manifest: Map<String, ManifestItem>,
        /** meta name=cover 的 content=id，或 properties=cover-image 的 item id */
        val coverId: String? = null,
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String = "",
    )

    private data class NavChapter(
        val title: String,
        /** 已相对 OPF/nav 解析的 zip 路径（不含 #） */
        val hrefPath: String,
        val fragment: String = "",
    )

    private fun parseOpf(xml: String): OpfData {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var title: String? = null
        val manifest = LinkedHashMap<String, ManifestItem>(512)
        val spineIds = ArrayList<String>(512)
        var coverId: String? = null
        var coverImageId: String? = null
        var inTitle = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = localName(parser)
                    when (local) {
                        "title" -> inTitle = true
                        "item" -> {
                            val id = attr(parser, "id")
                            val href = attr(parser, "href")
                            val mt = attr(parser, "media-type")
                            val props = attr(parser, "properties")
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                manifest[id] = ManifestItem(id, href, mt, props)
                                if (props.contains("cover-image")) {
                                    coverImageId = id
                                }
                            }
                        }
                        "itemref" -> {
                            val idref = attr(parser, "idref")
                            if (idref.isNotEmpty()) spineIds.add(idref)
                        }
                        "meta" -> {
                            val name = attr(parser, "name").ifEmpty { attr(parser, "property") }
                            val content = attr(parser, "content").ifEmpty { attr(parser, "id") }
                            if (name.equals("cover", ignoreCase = true) && content.isNotEmpty()) {
                                coverId = content
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTitle && title.isNullOrBlank()) {
                        val t = parser.text?.trim().orEmpty()
                        if (t.isNotEmpty()) title = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (localName(parser) == "title") inTitle = false
                }
            }
            event = parser.next()
        }
        val hrefs = ArrayList<String>(spineIds.size)
        for (id in spineIds) {
            manifest[id]?.href?.let { hrefs.add(it) }
        }
        val finalHrefs = if (hrefs.isNotEmpty()) {
            hrefs
        } else {
            manifest.values
                .filter {
                    it.mediaType.contains("html", true) ||
                        it.href.endsWith(".xhtml", true) ||
                        it.href.endsWith(".html", true) ||
                        it.href.endsWith(".htm", true)
                }
                .map { it.href }
        }
        return OpfData(title, finalHrefs, manifest, coverId = coverImageId ?: coverId)
    }

    /** 从 OPF 提取封面图到 imgDir */
    private fun extractCoverImage(
        index: ZipIndex,
        zip: ZipFile,
        opf: OpfData,
        opfDir: String,
        imgDir: File,
    ): File? {
        fun tryItem(item: ManifestItem?): File? {
            if (item == null) return null
            val mt = item.mediaType.lowercase(Locale.ROOT)
            if (mt.isNotEmpty() && !mt.startsWith("image/") &&
                !item.href.endsWith(".jpg", true) &&
                !item.href.endsWith(".jpeg", true) &&
                !item.href.endsWith(".png", true) &&
                !item.href.endsWith(".webp", true) &&
                !item.href.endsWith(".gif", true)
            ) {
                // 可能是封面 XHTML，不当作图
                return null
            }
            return extractImage(index, zip, opfDir, item.href, imgDir)
        }
        opf.coverId?.let { id -> tryItem(opf.manifest[id]) }?.let { return it }
        // 再扫 properties
        opf.manifest.values.firstOrNull { it.properties.contains("cover-image") }
            ?.let { tryItem(it) }?.let { return it }
        // id/href 含 cover 的图片
        opf.manifest.values.firstOrNull { item ->
            val id = item.id.lowercase(Locale.ROOT)
            val href = item.href.lowercase(Locale.ROOT)
            (id.contains("cover") || href.contains("cover")) &&
                (item.mediaType.contains("image", true) ||
                    href.endsWith(".jpg") || href.endsWith(".jpeg") ||
                    href.endsWith(".png") || href.endsWith(".webp"))
        }?.let { tryItem(it) }?.let { return it }
        return null
    }

    private fun localName(parser: XmlPullParser): String {
        val n = parser.name ?: return ""
        return n.substringAfter(':').lowercase(Locale.ROOT)
    }

    private fun attr(parser: XmlPullParser, name: String): String {
        // 无命名空间属性
        parser.getAttributeValue(null, name)?.let { return it }
        // 带命名空间时遍历
        for (i in 0 until parser.attributeCount) {
            val local = parser.getAttributeName(i)?.substringAfter(':') ?: continue
            if (local.equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i).orEmpty()
            }
        }
        return ""
    }

    private fun parseRootFilePath(containerXml: String): String? {
        val re = Regex(
            """full-path\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        return re.find(containerXml)?.groupValues?.get(1)
    }

    private fun parseNavChapters(
        index: ZipIndex,
        zip: ZipFile,
        opf: OpfData,
        opfDir: String,
    ): List<NavChapter> {
        val navItem = opf.manifest.values.firstOrNull {
            it.properties.contains("nav") ||
                it.href.contains("nav.", true) ||
                it.href.endsWith("nav.xhtml", true)
        }
        if (navItem != null) {
            val path = resolveZipPath(opfDir, navItem.href)
            val html = readZipText(index, zip, path)
            if (html != null) {
                val entry = index.resolve(path) ?: path
                return parseNavHtml(html, entry.substringBeforeLast('/', ""))
            }
        }
        val ncx = opf.manifest.values.firstOrNull {
            it.mediaType.contains("ncx", true) || it.href.endsWith(".ncx", true)
        }
        if (ncx != null) {
            val path = resolveZipPath(opfDir, ncx.href)
            val xml = readZipText(index, zip, path) ?: return emptyList()
            val entry = index.resolve(path) ?: path
            return parseNcx(xml, entry.substringBeforeLast('/', ""))
        }
        return emptyList()
    }

    private fun parseNavHtml(html: String, baseDir: String): List<NavChapter> {
        val out = ArrayList<NavChapter>(64)
        // 优先 toc nav
        val tocSlice = Regex(
            """(?is)<nav[^>]*epub:type\s*=\s*["'][^"']*toc[^"']*["'][^>]*>(.*?)</nav>""",
        ).find(html)?.groupValues?.get(1) ?: html
        val re = Regex(
            """(?is)<a[^>]+href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        )
        val strip = Regex("<[^>]+>")
        for (m in re.findAll(tocSlice)) {
            val rawHref = m.groupValues[1].trim()
            if (rawHref.startsWith("http", true) || rawHref.startsWith("mailto:", true)) continue
            val pathPart = rawHref.substringBefore('#').trim()
            val frag = rawHref.substringAfter('#', "").trim()
            val title = HtmlRichParser.decodeEntities(
                strip.replace(m.groupValues[2], "").replace(Regex("\\s+"), " ").trim(),
            )
            if (title.isEmpty()) continue
            if (pathPart.isEmpty() && frag.isEmpty()) continue
            val path = if (pathPart.isEmpty()) "" else resolveZipPath(baseDir, pathPart)
            out.add(NavChapter(title, path, frag))
            if (out.size >= 2000) break
        }
        return out
    }

    private fun parseNcx(xml: String, baseDir: String): List<NavChapter> {
        val out = ArrayList<NavChapter>(64)
        val re = Regex(
            """(?is)<navLabel>\s*<text>(.*?)</text>\s*</navLabel>.*?<content[^>]+src\s*=\s*["']([^"']+)["']""",
        )
        for (m in re.findAll(xml)) {
            val title = HtmlRichParser.decodeEntities(
                m.groupValues[1].replace(Regex("\\s+"), " ").trim(),
            )
            val rawHref = m.groupValues[2].trim()
            val pathPart = rawHref.substringBefore('#').trim()
            val frag = rawHref.substringAfter('#', "").trim()
            if (title.isEmpty()) continue
            val path = if (pathPart.isEmpty()) "" else resolveZipPath(baseDir, pathPart)
            out.add(NavChapter(title, path, frag))
            if (out.size >= 2000) break
        }
        return out
    }

    private fun extractImage(
        index: ZipIndex,
        zip: ZipFile,
        baseDir: String,
        src: String,
        imgDir: File,
    ): File? {
        var s = src.trim()
        if (s.startsWith("data:", ignoreCase = true)) return null
        s = s.substringBefore('#').substringBefore('?')
        // URL 解码（%20 等）
        s = runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
        val zipPath = resolveZipPath(baseDir, s)
        val entryName = index.resolve(zipPath) ?: index.resolve(s) ?: return null
        val name = entryName.substringAfterLast('/').ifBlank { "img.bin" }
        val safe = name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        val hash = shortHash(entryName)
        val out = File(imgDir, "${hash}_$safe")
        if (!out.exists() || out.length() == 0L) {
            val entry = zip.getEntry(entryName) ?: return null
            zip.getInputStream(entry).use { input ->
                FileOutputStream(out).use { output -> copyStream(input, output) }
            }
        }
        return out.takeIf { it.exists() && it.length() > 0 }
    }

    private fun resolveZipPath(baseDir: String, href: String): String {
        var h = href.replace('\\', '/').trim()
        if (h.startsWith("/")) h = h.trimStart('/')
        val base = baseDir.replace('\\', '/').trim().trimEnd('/')
        if (base.isEmpty()) return normalizePath(h)
        return normalizePath("$base/$h")
    }

    private fun normalizePath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val stack = ArrayList<String>(parts.size)
        for (p in parts) {
            when {
                p.isEmpty() || p == "." -> Unit
                p == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(p)
            }
        }
        return stack.joinToString("/")
    }

    private fun readZipText(index: ZipIndex, zip: ZipFile, path: String): String? {
        val name = index.resolve(path) ?: return null
        // 优先 getEntry（内部索引），比枚举快
        val entry: ZipEntry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { input ->
            val bytes = BufferedInputStream(input).readBytes()
            decodeHtmlBytes(bytes)
        }
    }

    private fun decodeHtmlBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrElse {
            runCatching { String(bytes, charset("GB18030")) }.getOrElse {
                String(bytes)
            }
        }
    }

    private fun copyStream(input: InputStream, out: FileOutputStream) {
        val bis = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = bis.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private fun cacheKeyFor(uri: String, name: String): String {
        val md = MessageDigest.getInstance("MD5")
        val dig = md.digest((uri + "|" + name).toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun shortHash(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
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
