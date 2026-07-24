package com.whj.reader.util

/** 屏幕左右各 1/4 为翻页区，中间 1/2 为菜单/选区等。 */
object ReaderTapZones {
    private const val EDGE_FRACTION = 0.25f

    fun isLeft(x: Float, width: Float): Boolean = x < width * EDGE_FRACTION

    fun isRight(x: Float, width: Float): Boolean = x > width * (1f - EDGE_FRACTION)

    fun isSide(x: Float, width: Float): Boolean = isLeft(x, width) || isRight(x, width)

    /** @return 0 左翻页，1 中部，2 右翻页 */
    fun zone(x: Float, width: Float): Int = when {
        isLeft(x, width) -> 0
        isRight(x, width) -> 2
        else -> 1
    }
}
