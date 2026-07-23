package com.whj.reader.pdf.render

import android.graphics.Bitmap
import android.util.LruCache
import com.whj.reader.ui.PdfPageSurface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 连续模式矮页整图缓存 + 长图 tile 缓存（含 pin，避免淘汰时 recycle 仍在绘制的图）。
 */
class PdfRenderCache {

    /**
     * 仍被某个 PdfPageSurface 握着的 tile，淘汰时禁止 recycle（否则白屏）。
     */
    private val tilePinned = Collections.newSetFromMap(ConcurrentHashMap<Bitmap, Boolean>())

    /**
     * 全局 tile 缓存：key 见 [tileCacheKey]，size = byteCount。
     */
    val tileCache = object : LruCache<Long, Bitmap>(PdfRenderConfig.TILE_CACHE_MAX_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int =
            if (value.isRecycled) 1 else value.byteCount.coerceAtLeast(1)

        override fun entryRemoved(
            evicted: Boolean,
            key: Long,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (oldValue === newValue || oldValue.isRecycled) return
            if (tilePinned.contains(oldValue)) return
            runCatching { oldValue.recycle() }
        }
    }

    /**
     * 连续模式仅保留附近几页矮页整图。
     *
     * **禁止在 entryRemoved 里 recycle**：Surface 仍可能握着同一张 Bitmap 在画。
     */
    val bitmapCache = object : LruCache<Int, Bitmap>(PdfRenderConfig.BITMAP_CACHE_PAGES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }

    /**
     * tile 缓存键：页 + 块 + 目标宽档。
     * 必须带宽度，否则横屏渲染的块在竖屏复用会被横向压扁。
     */
    fun tileCacheKey(pageIndex: Int, tileIndex: Int, targetWidth: Int = 0): Long {
        val twBucket = (targetWidth.coerceAtLeast(0) / 16).coerceIn(0, 0x3FF)
        return (pageIndex.toLong() shl 26) or
            ((tileIndex.toLong() and 0x3FF) shl 16) or
            twBucket.toLong()
    }

    fun pinTileBitmap(bmp: Bitmap?) {
        if (bmp != null && !bmp.isRecycled) tilePinned.add(bmp)
    }

    fun unpinTileBitmap(bmp: Bitmap?) {
        if (bmp == null) return
        tilePinned.remove(bmp)
    }

    /** 把 tile 交给 Surface，并维护 pin */
    fun deliverTile(
        surface: PdfPageSurface,
        tileIndex: Int,
        bmp: Bitmap,
        bindGen: Long,
    ) {
        if (bmp.isRecycled) return
        val old = surface.setTile(tileIndex, bmp, bindGen, owned = false)
        pinTileBitmap(bmp)
        if (old != null) unpinTileBitmap(old)
    }

    fun hydrateTilesFromCache(surface: PdfPageSurface, pageIndex: Int, targetWidth: Int) {
        val n = surface.tileCount
        if (n <= 0) return
        val gen = surface.bindGeneration
        for (i in 0 until n) {
            val bmp = tileCache.get(tileCacheKey(pageIndex, i, targetWidth)) ?: continue
            if (bmp.isRecycled) continue
            deliverTile(surface, i, bmp, gen)
        }
    }

    fun trimBitmapCacheAround(center: Int, keepRadius: Int = PdfRenderConfig.CACHE_KEEP_RADIUS) {
        val keys = bitmapCache.snapshot().keys.toList()
        for (k in keys) {
            if (kotlin.math.abs(k - center) > keepRadius) {
                bitmapCache.remove(k)
            }
        }
    }

    fun isBitmapFullQuality(bmp: Bitmap, targetWidth: Int): Boolean =
        bmp.width >= targetWidth * 0.82f

    fun clearTileCache() {
        tileCache.evictAll()
        tilePinned.clear()
    }

    fun evictAll() {
        bitmapCache.evictAll()
        clearTileCache()
    }
}
