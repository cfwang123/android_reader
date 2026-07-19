package com.whj.reader.data

import android.content.Context

/**
 * 按文件 URI 记忆简繁转换模式。默认 [ChineseConvert.Mode.OFF]。
 */
object BookChineseModeStore {
    private const val PREF = "reader_book_chinese_mode"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun key(uri: String) = "zh_${uri.hashCode()}"

    fun get(ctx: Context, uri: String): ChineseConvert.Mode {
        if (uri.isBlank()) return ChineseConvert.Mode.OFF
        return ChineseConvert.fromStore(prefs(ctx).getString(key(uri), null))
    }

    fun set(ctx: Context, uri: String, mode: ChineseConvert.Mode) {
        if (uri.isBlank()) return
        val ed = prefs(ctx).edit()
        val code = ChineseConvert.toStore(mode)
        if (code == null) ed.remove(key(uri)) else ed.putString(key(uri), code)
        ed.apply()
    }
}
