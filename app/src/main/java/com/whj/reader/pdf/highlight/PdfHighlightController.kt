package com.whj.reader.pdf.highlight

import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.data.BookNotesFileStore
import com.whj.reader.model.BookNotesDocument
import com.whj.reader.model.Highlight
import com.whj.reader.model.HighlightColorPresets
import com.whj.reader.model.HighlightKind
import com.whj.reader.model.HighlightMode
import com.whj.reader.model.HighlightStyle
import com.whj.reader.model.TextAnchor
import com.whj.reader.ui.PdfNoteBubbleOverlay
import com.whj.reader.ui.PdfSelectionOverlay
import com.whj.reader.ui.TocItem
import com.whj.reader.util.Toasts

/**
 * PDF 阅读高亮/笔记（锚点：页码 + 页内字符索引，存入 [TextAnchor] 的 paragraph/offset 字段）。
 */
class PdfHighlightController(
    private val activity: PdfReadingActivity,
) {

    private val b get() = activity.binding
    private val textSel get() = activity.textSelCtrl.state

    var bookHighlights: List<Highlight> = emptyList()
        private set
    private var notesMirrorWarned = false

    fun reloadBookHighlights() {
        if (activity.fileKey.isBlank()) {
            bookHighlights = emptyList()
            refreshOverlays()
            return
        }
        bookHighlights = sortHighlights(
            BookNotesFileStore.load(activity, activity.fileKey).highlights
                .filter { it.kind == HighlightKind.PDF },
        )
        refreshOverlays()
    }

    fun sortHighlights(highlights: List<Highlight>): List<Highlight> =
        highlights.sortedWith(
            compareBy(
                { it.anchor.startParagraph },
                { it.anchor.startOffset },
                { it.createdAt },
            ),
        )

    fun highlightProgressPercent(hl: Highlight): Float {
        val page = hl.anchor.startParagraph.coerceAtLeast(0)
        val total = activity.pageCount
        if (total <= 1) return 0f
        return (page.toFloat() / (total - 1).toFloat() * 100f).coerceIn(0f, 100f)
    }

    fun highlightTocItems(): List<TocItem.HighlightItem> {
        val sorted = sortHighlights(bookHighlights)
        return sorted.mapIndexed { index, hl ->
            TocItem.HighlightItem(
                highlight = hl,
                sequence = index + 1,
                progressPercent = highlightProgressPercent(hl),
            )
        }
    }

    fun applyHighlightList(highlights: List<Highlight>) {
        bookHighlights = sortHighlights(highlights)
    }

    fun saveBookHighlights() {
        if (activity.fileKey.isBlank()) return
        val loc = BookNotesFileStore.save(
            activity,
            BookNotesDocument(bookUri = activity.fileKey, highlights = bookHighlights),
        )
        if (loc.isMirror && !notesMirrorWarned) {
            notesMirrorWarned = true
            Toasts.show(activity, R.string.highlight_mirror_hint)
        }
        refreshOverlays()
    }

    fun defaultHighlightStyle(): HighlightStyle {
        val color = if (activity.night) {
            HighlightColorPresets.defaultForDarkText()
        } else {
            HighlightColorPresets.defaultForLightText()
        }
        return HighlightStyle(
            mode = HighlightMode.BACKGROUND,
            colorArgb = color,
            opacity = 80,
        )
    }

    fun addHighlightFromSelection() {
        if (!activity.hasTextSelection()) return
        val text = activity.selectedText()
        if (text.isBlank()) return
        val anchor = TextAnchor(
            startParagraph = textSel.startPage,
            startOffset = textSel.startChar,
            endParagraph = textSel.endPage,
            endOffset = textSel.endChar,
        )
        val hl = Highlight.create(
            kind = HighlightKind.PDF,
            anchor = anchor,
            selectedText = text,
            style = defaultHighlightStyle(),
        )
        applyHighlightList(bookHighlights + hl)
        saveBookHighlights()
        activity.prepareBottomChromeForBlockingModal()
        activity.clearTextSelection()
        showHighlightEdit(hl.id)
        b.pdfContainer.post {
            activity.syncPdfContentBottomInset()
            refreshOverlays()
        }
    }

    private fun onHighlightModalDismiss() {
        activity.refreshBottomChromeAfterModal("highlightDialog")
        b.pdfContainer.post {
            activity.syncPdfContentBottomInset()
            refreshOverlays()
        }
    }

    fun showHighlightView(highlightId: String) {
        val hl = bookHighlights.find { it.id == highlightId } ?: return
        activity.prepareBottomChromeForBlockingModal()
        com.whj.reader.ui.HighlightNotePopup.showView(
            activity,
            hl,
            onEdit = { showHighlightEdit(it.id) },
            onDelete = { deleted ->
                applyHighlightList(bookHighlights.filter { it.id != deleted.id })
                saveBookHighlights()
                Toasts.show(activity, R.string.highlight_deleted)
            },
            onDismiss = { onHighlightModalDismiss() },
        )
    }

    fun showHighlightEdit(highlightId: String) {
        val hl = bookHighlights.find { it.id == highlightId } ?: return
        activity.prepareBottomChromeForBlockingModal()
        com.whj.reader.ui.HighlightNotePopup.showEdit(
            activity,
            hl,
            onSave = { updated ->
                applyHighlightList(
                    bookHighlights.map { if (it.id == updated.id) updated else it },
                )
                saveBookHighlights()
                Toasts.show(activity, R.string.highlight_saved)
            },
            onDelete = { deleted ->
                applyHighlightList(bookHighlights.filter { it.id != deleted.id })
                saveBookHighlights()
                Toasts.show(activity, R.string.highlight_deleted)
            },
            onDismiss = { onHighlightModalDismiss() },
        )
    }

    fun scrollToHighlight(hl: Highlight) {
        val page = hl.anchor.startParagraph.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0))
        activity.navBookmarkController.navigateToPageWithHistory(page)
        b.pdfContainer.post { refreshOverlays() }
    }

    fun hitTestHighlight(containerX: Float, containerY: Float): String? {
        for (hl in bookHighlights) {
            val rects = rectsFor(hl) ?: continue
            if (rects.any { it.contains(containerX, containerY) }) return hl.id
        }
        return null
    }

    fun refreshOverlays() {
        refreshPersistentHighlightOverlay()
        refreshBubbleOverlay()
    }

    fun refreshPersistentHighlightOverlay() {
        if (!activity.isBindingReady()) return
        val items = bookHighlights.mapNotNull { hl ->
            val rects = rectsFor(hl) ?: return@mapNotNull null
            if (rects.isEmpty()) return@mapNotNull null
            PdfSelectionOverlay.PersistentHighlightDraw(hl.id, rects, hl.style)
        }
        b.pdfSelectionOverlay.setPersistentHighlights(items)
        b.pdfSelectionOverlay.bringToFront()
    }

    fun refreshBubbleOverlay() {
        if (!activity.isBindingReady()) return
        val bubbles = bookHighlights.mapNotNull { hl ->
            val rects = rectsFor(hl) ?: return@mapNotNull null
            val cy = rects.fold(0f) { acc, r -> acc + r.centerY() } / rects.size
            PdfNoteBubbleOverlay.BubbleItem(
                id = hl.id,
                centerY = containerYToBubbleOverlayY(cy),
                tintColor = bubbleColor(hl.style),
            )
        }
        b.pdfNoteBubbleOverlay.setBubbles(bubbles)
        b.pdfNoteBubbleOverlay.bringToFront()
        b.pdfFastScroll.bringToFront()
    }

    private fun containerYToBubbleOverlayY(containerY: Float): Float {
        val overlay = b.pdfNoteBubbleOverlay
        if (overlay.height <= 0) return containerY
        val cLoc = IntArray(2)
        val oLoc = IntArray(2)
        b.pdfContainer.getLocationOnScreen(cLoc)
        overlay.getLocationOnScreen(oLoc)
        return containerY + (cLoc[1] - oLoc[1]).toFloat()
    }

    private fun rectsFor(hl: Highlight): List<android.graphics.RectF>? {
        val a = hl.anchor
        val rects = activity.multiPageCharRangeToContainerRects(
            a.startParagraph,
            a.startOffset,
            a.endParagraph,
            a.endOffset,
        )
        return rects.takeIf { it.isNotEmpty() }
    }

    private fun bubbleColor(style: HighlightStyle): Int =
        when (style.mode) {
            HighlightMode.UNDERLINE -> style.colorArgb or 0xFF000000.toInt()
            HighlightMode.BACKGROUND -> {
                val a = ((style.opacity.coerceIn(0, 100) / 100f) * 255f).toInt().coerceIn(0, 255)
                (style.colorArgb and 0x00FFFFFF) or (a shl 24)
            }
        }
}
