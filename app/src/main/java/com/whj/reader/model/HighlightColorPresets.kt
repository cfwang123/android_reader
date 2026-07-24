package com.whj.reader.model

/** 高亮预设色（RGB 纯色，alpha 由 [HighlightStyle.opacity] 控制）。 */
object HighlightColorPresets {
    val colors: IntArray = intArrayOf(
        0xFFFF0000.toInt(), // 红
        0xFF00CC00.toInt(), // 绿
        0xFF0066FF.toInt(), // 蓝
        0xFFFFD600.toInt(), // 黄
        0xFF9C27B0.toInt(), // 紫
        0xFFFF9800.toInt(), // 橙
        0xFF424242.toInt(), // 黑
        0xFFFF4081.toInt(), // 粉
    )

    fun rgbOf(colorArgb: Int): Int = colorArgb and 0xFFFFFF

    fun indexOf(colorArgb: Int): Int =
        colors.indexOfFirst { rgbOf(it) == rgbOf(colorArgb) }

    fun isPreset(colorArgb: Int): Boolean = indexOf(colorArgb) >= 0

    fun defaultForLightText(): Int = colors[3] // 黄

    fun defaultForDarkText(): Int = 0xFF4A90C0.toInt() // 浅蓝，深底可读
}
