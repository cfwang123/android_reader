package com.whj.reader.model

import java.util.UUID

enum class HighlightKind { TXT, PDF }

enum class HighlightMode { BACKGROUND, UNDERLINE }

enum class UnderlineShape { SOLID, DASHED, WAVY }

data class HighlightStyle(
    val mode: HighlightMode = HighlightMode.BACKGROUND,
    val underlineShape: UnderlineShape = UnderlineShape.SOLID,
    /** #AARRGGBB */
    val colorArgb: Int = 0x66FFE082.toInt(),
    val opacity: Int = 80,
)

data class TextAnchor(
    val startParagraph: Int,
    val startOffset: Int,
    val endParagraph: Int,
    val endOffset: Int,
)

data class Highlight(
    val id: String,
    val kind: HighlightKind,
    val anchor: TextAnchor,
    val selectedText: String,
    val note: String,
    val style: HighlightStyle,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun create(
            kind: HighlightKind,
            anchor: TextAnchor,
            selectedText: String,
            style: HighlightStyle,
            note: String = "",
        ): Highlight {
            val now = System.currentTimeMillis()
            return Highlight(
                id = UUID.randomUUID().toString(),
                kind = kind,
                anchor = anchor,
                selectedText = selectedText,
                note = note,
                style = style,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    fun withUpdates(
        note: String = this.note,
        style: HighlightStyle = this.style,
    ): Highlight = copy(
        note = note,
        style = style,
        updatedAt = System.currentTimeMillis(),
    )
}

data class SelectionRange(
    val startParagraph: Int,
    val startOffset: Int,
    val endParagraph: Int,
    val endOffset: Int,
    val text: String,
)

data class BookNotesDocument(
    val version: Int = 1,
    val bookUri: String,
    val highlights: List<Highlight>,
)
