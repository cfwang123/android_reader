package com.whj.reader.model

data class Paragraph(
    val index: Int,
    val text: String,
    val isChapter: Boolean = false,
)

data class Chapter(
    val title: String,
    val paragraphIndex: Int,
)

data class RecentFile(
    val uri: String,
    val displayName: String,
    val lastParagraph: Int = 0,
    val lastOpened: Long = System.currentTimeMillis(),
    val pathHint: String = "",
)

/** 书架文件夹（仅 1 层，不可嵌套） */
data class ShelfFolder(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 书架上的书 */
data class ShelfBook(
    val id: String,
    val uri: String,
    val displayName: String,
    /** null 表示顶层 */
    val folderId: String? = null,
    val pathHint: String = "",
    val lastParagraph: Int = 0,
    val lastOpened: Long = System.currentTimeMillis(),
)

/** 书架列表项：文件夹或书 */
sealed class ShelfItem {
    data class Folder(
        val folder: ShelfFolder,
        val bookCount: Int,
    ) : ShelfItem()

    data class Book(val book: ShelfBook) : ShelfItem()
}

data class Bookmark(
    val fileKey: String,
    val paragraphIndex: Int,
    val preview: String,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class ReadTheme {
    DEFAULT,
    GREEN,
    BLUE,
    PURPLE,
    SEPIA,
    NIGHT,
}

data class ReadStyle(
    val theme: ReadTheme = ReadTheme.DEFAULT,
    val fontSizeSp: Float = 18f,
    val lineSpacingMult: Float = 1.4f,
    val paraSpacingDp: Int = 8,
    val letterSpacing: Float = 0f,
)

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
