package com.whj.reader.pdf.render

/**
 * PDF 渲染 / 缓存相关常量（原 PdfReadingActivity companion）。
 */
object PdfRenderConfig {
    /** 连续模式 bitmap 页数上限（仅矮页整图） */
    const val BITMAP_CACHE_PAGES = 5

    /** 当前页前后各保留几页 */
    const val CACHE_KEEP_RADIUS = 2

    /** 渲染相对源页最大放大倍数 */
    const val RENDER_MAX_SCALE = 2.2f

    /**
     * 矮页整图像素上限（ARGB≈4B/px → 约 24MB）。
     */
    const val RENDER_MAX_PIXELS = 6_000_000f

    /** 单边最大像素 */
    const val RENDER_MAX_DIM = 8192

    /** 单页超长图整页渲染像素上限（按宽铺满时允许更高） */
    const val SINGLE_TALL_MAX_PIXELS = 20_000_000L

    const val SINGLE_TALL_MAX_HEIGHT = 16_384

    /**
     * 连续模式逻辑显示高度超过此值 → 长图分块渲染。
     * max(2.2×屏高, 4000px)
     */
    const val TALL_PAGE_MIN_FACTOR = 2.2f

    const val TALL_PAGE_MIN_PX = 4000

    /** 长图单块高度 ≈ 屏高比例 */
    const val TILE_HEIGHT_FACTOR = 0.85f

    /** 可见块上下各预渲染几块 */
    const val TILE_PREFETCH = 3

    /**
     * tile 缓存总字节上限（按 bitmap.byteCount 计）。
     */
    const val TILE_CACHE_MAX_BYTES = 64 * 1024 * 1024

    /** 单块最大像素（宽×高），约 2.5MP → ARGB≈10MB */
    const val TILE_MAX_PIXELS = 2_500_000f

    /** 滚动中预览宽度相对目标宽的比例 */
    const val PREVIEW_WIDTH_FACTOR = 0.5f

    /** 主线程每帧最多贴图张数 */
    const val MAX_BITMAP_ATTACH_PER_FRAME = 2
}
