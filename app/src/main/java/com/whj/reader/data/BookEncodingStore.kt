package com.whj.reader.data

import android.content.Context

/**
 * 按文件 URI 记忆文本编码。
 * 未设置或 [ENCODING_AUTO] 表示打开时自动判断。
 */
object BookEncodingStore {
    const val ENCODING_AUTO = "auto"

    private const val PREF = "reader_book_encoding"

    /** 可选编码 id（含自动）；界面文案由 Activity 解析 */
    val OPTION_IDS: List<String> = listOf(
        ENCODING_AUTO,
        "UTF-8",
        "GB18030",
        "GBK",
        "Big5",
        "UTF-16LE",
        "UTF-16BE",
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun key(uri: String) = "enc_${uri.hashCode()}"

    /** null 或 auto = 自动 */
    fun get(ctx: Context, uri: String): String? {
        if (uri.isBlank()) return null
        val v = prefs(ctx).getString(key(uri), null) ?: return null
        return if (v.isBlank() || v == ENCODING_AUTO) null else v
    }

    fun set(ctx: Context, uri: String, encoding: String?) {
        if (uri.isBlank()) return
        val ed = prefs(ctx).edit()
        if (encoding.isNullOrBlank() || encoding == ENCODING_AUTO) {
            ed.remove(key(uri))
        } else {
            ed.putString(key(uri), encoding)
        }
        ed.apply()
    }

    fun setMany(ctx: Context, uris: Collection<String>, encoding: String?) {
        val ed = prefs(ctx).edit()
        for (uri in uris) {
            if (uri.isBlank()) continue
            if (encoding.isNullOrBlank() || encoding == ENCODING_AUTO) {
                ed.remove(key(uri))
            } else {
                ed.putString(key(uri), encoding)
            }
        }
        ed.apply()
    }

    fun label(ctx: Context, uri: String): String {
        val enc = get(ctx, uri)
        return if (enc == null) {
            ctx.getString(com.whj.reader.R.string.encoding_auto)
        } else {
            enc
        }
    }
}
