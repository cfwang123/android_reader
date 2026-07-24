package com.whj.reader.pdf.render
import com.whj.reader.PdfReadingActivity

import android.view.Choreographer
import com.whj.reader.ui.PdfPageSurface

/**
 * 主线程贴图限流：每帧最多 [PdfRenderConfig.MAX_BITMAP_ATTACH_PER_FRAME] 张。
 * 解决快速滑时 onBind + 多任务同时 setFullBitmap 导致 UI 卡死。
 */
class PdfUiAttachQueue(
    private val activity: PdfReadingActivity,
) {

    private val pending = ArrayDeque<PdfUiAttach>()
    private var frameScheduled = false

    fun enqueue(attach: PdfUiAttach) {
        synchronized(pending) {
            pending.removeAll {
                it.surface === attach.surface && it.isTile == attach.isTile &&
                    (!it.isTile || it.tileIndex == attach.tileIndex)
            }
            pending.addLast(attach)
        }
        scheduleFlush()
    }

    fun clear() {
        synchronized(pending) { pending.clear() }
    }

    private fun scheduleFlush() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback {
            frameScheduled = false
            if (!activity.isAlive()) {
                synchronized(pending) { pending.clear() }
                return@postFrameCallback
            }
            var n = 0
            while (n < PdfRenderConfig.MAX_BITMAP_ATTACH_PER_FRAME) {
                val a = synchronized(pending) {
                    if (pending.isEmpty()) null else pending.removeFirst()
                } ?: break
                if (a.bmp.isRecycled) continue
                if (a.surface.pageIndex != a.page || a.surface.bindGeneration != a.bindGen) {
                    if (a.isTile) activity.unpinTileBitmap(a.bmp)
                    continue
                }
                if (a.isTile) {
                    activity.deliverTile(a.surface, a.tileIndex, a.bmp, a.bindGen)
                    a.surface.setNightMode(activity.night)
                } else {
                    a.surface.setFullBitmap(a.bmp)
                }
                n++
            }
            val more = synchronized(pending) { pending.isNotEmpty() }
            if (more) scheduleFlush()
        }
    }
}
