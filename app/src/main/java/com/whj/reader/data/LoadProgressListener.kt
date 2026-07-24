package com.whj.reader.data

/**
 * 书籍加载进度回调（函数类型，非 interface）。
 * @param message 阶段说明
 * @param current 当前步（从 1 或 0 起）
 * @param total 总步数；≤0 表示不确定进度（转圈）
 */
typealias LoadProgressListener = (message: String, current: Int, total: Int) -> Unit
