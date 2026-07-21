package com.whj.reader.model

/**
 * 段内富文本样式区间 [start, end)。
 * 颜色/背景仅一层（解析时栈顶覆盖，不做多层叠加合成）。
 */
data class TextSpanStyle(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** ARGB，null 表示沿用阅读主题字色 */
    val color: Int? = null,
    /** ARGB 背景，null 无背景 */
    val backgroundColor: Int? = null,
    /**
     * 超链接目标（HTML href 原值，可能是 #id / 相对路径 / http…）。
     * 非空时绘制为链接样式，点击由阅读页解析跳转。
     */
    val linkHref: String? = null,
)

/**
 * HTML 图片显示尺寸（来自 width/height 属性或 style）。
 * - [widthEm]/[heightEm]：相对正文字号
 * - [widthPx]/[heightPx]：逻辑像素（已按 density 换算前的 CSS px，绘制时再乘 density）
 * - [widthPercent]：相对正文宽 0–100
 * 均为 null 时由阅读器用默认策略（行内≈1em，块图适配宽）。
 */
data class ImageDisplaySize(
    val widthEm: Float? = null,
    val heightEm: Float? = null,
    val widthPx: Float? = null,
    val heightPx: Float? = null,
    val widthPercent: Float? = null,
    val heightPercent: Float? = null,
) {
    val hasAny: Boolean
        get() = widthEm != null || heightEm != null ||
            widthPx != null || heightPx != null ||
            widthPercent != null || heightPercent != null
}

/**
 * 段内行内图：占 [start, end)（通常为单字符 U+FFFC），影响行高与占位宽度。
 */
data class InlineImage(
    val start: Int,
    val end: Int,
    /** 本地绝对路径 */
    val path: String,
    val displaySize: ImageDisplaySize? = null,
)

/** 段落水平对齐（HTML text-align / align） */
enum class TextAlign {
    START,
    CENTER,
    END,
}

data class Paragraph(
    val index: Int,
    val text: String,
    val isChapter: Boolean = false,
    /** 富文本区间（TXT 为空） */
    val spans: List<TextSpanStyle> = emptyList(),
    /**
     * **整行/块级**图片：独占一段、居中（或漫画满宽）。
     * [text] 通常为空（TTS 跳过）。
     */
    val imagePath: String? = null,
    /** 块图 HTML 指定尺寸 */
    val imageDisplaySize: ImageDisplaySize? = null,
    /** 行内图片（与 [text] 中的 U+FFFC 对应） */
    val inlineImages: List<InlineImage> = emptyList(),
    /** 水平对齐 */
    val align: TextAlign = TextAlign.START,
    /**
     * 预格式化（&lt;pre&gt;）：保留空白与换行，等宽绘制。
     */
    val preformatted: Boolean = false,
) {
    /** 是否为整行图片段 */
    val isBlockImage: Boolean get() = !imagePath.isNullOrBlank()
    /** 兼容旧调用：整行图 */
    val isImage: Boolean get() = isBlockImage
    val hasInlineImages: Boolean get() = inlineImages.isNotEmpty()
}

data class Chapter(
    val title: String,
    val paragraphIndex: Int,
    /**
     * EPUB spine 下标（0-based）；-1 表示未知。
     * 用于按需跳转时优先解析到该章，不必先扫完整本。
     */
    val spineIndex: Int = -1,
)

data class RecentFile(
    val uri: String,
    val displayName: String,
    val lastParagraph: Int = 0,
    val lastOpened: Long = System.currentTimeMillis(),
    val pathHint: String = "",
)

/** 顶层条目类型：虚拟书架 vs 绑定外部文件夹 vs 系统虚拟夹 */
enum class ShelfFolderKind {
    /** 应用内书架：书目存本地，仅 1 层 */
    SHELF,
    /** 绑定 SD/外部目录：实时读 txt/pdf，可进子文件夹 */
    LINKED,
    /** 系统虚拟：阅读历史（不入库） */
    HISTORY,
}

/**
 * 顶层「书架」或「绑定文件夹」。
 * - [ShelfFolderKind.SHELF]：虚拟分组，书写入 [ShelfBook.folderId]
 * - [ShelfFolderKind.LINKED]：绑定 [treeUri]，内容实时扫描
 * - [ShelfFolderKind.HISTORY]：阅读历史（固定入口，不持久化）
 */
data class ShelfFolder(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val kind: ShelfFolderKind = ShelfFolderKind.SHELF,
    /** LINKED 时 SAF tree URI */
    val treeUri: String? = null,
) {
    val isLinked: Boolean get() = kind == ShelfFolderKind.LINKED
    val isHistory: Boolean get() = kind == ShelfFolderKind.HISTORY
}

/** 书架上的书 */
data class ShelfBook(
    val id: String,
    val uri: String,
    val displayName: String,
    /** null 表示顶层；仅对 SHELF 类文件夹有效 */
    val folderId: String? = null,
    val pathHint: String = "",
    val lastParagraph: Int = 0,
    val lastOpened: Long = System.currentTimeMillis(),
    /** 自定义排序下标（越小越靠前） */
    val sortOrder: Int = 0,
)

/** 绑定文件夹内的一层浏览结果（不入库） */
data class LinkedDirEntry(
    val name: String,
    val uri: String,
    /** SAF documentId，用于在 tree 下继续 list */
    val documentId: String = "",
    /** 直接子项数量（子文件夹 + txt/pdf），未知为 -1 */
    val childCount: Int = -1,
)

data class LinkedFileEntry(
    val name: String,
    val displayName: String,
    val uri: String,
    val isPdf: Boolean,
    /** 列表时一并查出的大小；未知为 -1 */
    val sizeBytes: Long = -1L,
    /** 相对绑定根的路径，如 a/b/c.txt；搜索结果展示用 */
    val relativePath: String = "",
    val documentId: String = "",
)

/** 书架列表项 */
sealed class ShelfItem {
    data class Folder(
        val folder: ShelfFolder,
        /** 直接子项数：虚拟书架为书本数；绑定文件夹为子目录+书文件数 */
        val childCount: Int,
    ) : ShelfItem()

    data class Book(val book: ShelfBook) : ShelfItem()

    /** 绑定目录下的子文件夹 */
    data class LinkedDir(val entry: LinkedDirEntry) : ShelfItem()

    /** 绑定目录下的 txt/pdf（进度可从书架 URI 匹配） */
    data class LinkedFile(
        val entry: LinkedFileEntry,
        val progress: ShelfBook? = null,
    ) : ShelfItem()
}

data class Bookmark(
    val fileKey: String,
    val paragraphIndex: Int,
    val preview: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 书签位置进度 0~100；旧数据可能为 -1（展示时按段索引估算） */
    val progressPercent: Float = -1f,
)

enum class ReadTheme {
    DEFAULT,
    /** 纯白背景 */
    WHITE,
    GREEN,
    BLUE,
    PURPLE,
    SEPIA,
    NIGHT,
    /** 自定义背景色，见 [ReadStyle.customBgColor] */
    CUSTOM,
}

data class ReadStyle(
    /** 兼容旧版；界面已改为纹理 + 字色，夜间快捷仍可能写 NIGHT */
    val theme: ReadTheme = ReadTheme.DEFAULT,
    val fontSizeSp: Float = 18f,
    val lineSpacingMult: Float = 1.4f,
    val paraSpacingDp: Int = 8,
    val letterSpacing: Float = 0f,
    /**
     * 字体 id：default / sans / serif / mono，或 custom:&lt;uuid&gt;（已安装 TTF/OTF）
     * @see com.whj.reader.util.ReaderFonts
     */
    val fontFamily: String = "default",
    /** 无纹理/无自定义图时的纯色背景 */
    val customBgColor: Int = 0xFFF7F4ED.toInt(),
    /**
     * 背景：空=纯色 [customBgColor]；预设纹理 id；[BgTextures.IMPORT]=导入图
     * @see com.whj.reader.util.BgTextures
     */
    val bgTextureId: String = "",
    /** 正文颜色 ARGB */
    val textColor: Int = 0xFF2C2C2C.toInt(),
    /** 导入背景图文件名（位于 app filesDir/bg/），仅 [bgTextureId]=import 时有效 */
    val customBgImageFile: String = "",
)

/** 阅读页屏幕常亮 */
enum class KeepScreenMode {
    /** 不常亮（默认，省电） */
    OFF,
    /** 阅读页始终请求常亮（可配合空闲熄屏） */
    ALWAYS,
    /** 仅 TTS 朗读中常亮 */
    TTS_ONLY,
}

/** 阅读页屏幕方向 */
enum class OrientationMode {
    PORTRAIT,
    LANDSCAPE,
    AUTO,
}

/** 左/右边缘上下滑动对应的调节项 */
enum class EdgeSwipeAction {
    RATE,
    FONT,
    NONE,
}

/** 应用界面语言 */
enum class AppLanguage {
    ZH,
    EN,
}

/** 书架排序 */
enum class ShelfSort {
    /** 按上次阅读时间，新→旧（默认） */
    LAST_OPENED,
    /** 按文件名 */
    NAME,
    /** 用户拖动自定义顺序 */
    CUSTOM,
}

/** PDF 翻页/浏览模式（与 TXT 排版无关） */
enum class PdfPageMode {
    /** 连续滚动（默认），页间细黑线 */
    CONTINUOUS,
    /** 单页模式，左右点按翻页 */
    SINGLE,
}
