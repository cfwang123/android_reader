package com.whj.reader.data

/**
 * 书籍加载进度。
 * @param message 阶段说明
 * @param current 当前步（从 1 或 0 起）
 * @param total 总步数；≤0 表示不确定进度（转圈）
 */
fun interface LoadProgressListener {
    fun invoke(message: String, current: Int, total: Int)
}
