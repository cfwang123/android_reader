package com.whj.reader.util

import android.content.Context
import android.graphics.Typeface
import com.whj.reader.R
import com.whj.reader.data.CustomFontStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读字体：系统预设 + 已安装自定义 TTF/OTF。
 */
object ReaderFonts {
    const val ID_DEFAULT = "default"
    const val ID_SANS = "sans"
    const val ID_SERIF = "serif"
    const val ID_MONO = "mono"

    const val CUSTOM_PREFIX = "custom:"

    val ALL_IDS = listOf(ID_DEFAULT, ID_SANS, ID_SERIF, ID_MONO)

    private val customCache = ConcurrentHashMap<String, Typeface>()

    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)

    fun label(ctx: Context, id: String): String {
        if (isCustom(id)) {
            return CustomFontStore.find(ctx, id)?.name
                ?: ctx.getString(R.string.font_custom_unknown)
        }
        return when (id) {
            ID_SANS -> ctx.getString(R.string.font_sans)
            ID_SERIF -> ctx.getString(R.string.font_serif)
            ID_MONO -> ctx.getString(R.string.font_mono)
            else -> ctx.getString(R.string.font_default)
        }
    }

    /**
     * 解析 Typeface。自定义字体需 [Context] 读私有目录；失败回退系统默认。
     */
    fun resolve(ctx: Context, id: String, bold: Boolean = false): Typeface {
        val base = if (isCustom(id)) {
            loadCustom(ctx, id) ?: Typeface.DEFAULT
        } else {
            when (id) {
                ID_SANS -> Typeface.SANS_SERIF
                ID_SERIF -> Typeface.SERIF
                ID_MONO -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
        }
        return if (bold) {
            Typeface.create(base, Typeface.BOLD) ?: Typeface.DEFAULT_BOLD
        } else {
            base
        }
    }

    /** 兼容旧调用：无 Context 时仅系统预设 */
    fun resolve(id: String, bold: Boolean = false): Typeface {
        if (isCustom(id)) {
            val cached = customCache[id]
            if (cached != null) {
                return if (bold) {
                    Typeface.create(cached, Typeface.BOLD) ?: Typeface.DEFAULT_BOLD
                } else {
                    cached
                }
            }
            return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val base = when (id) {
            ID_SANS -> Typeface.SANS_SERIF
            ID_SERIF -> Typeface.SERIF
            ID_MONO -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        return if (bold) {
            Typeface.create(base, Typeface.BOLD) ?: Typeface.DEFAULT_BOLD
        } else {
            base
        }
    }

    fun invalidate(id: String) {
        customCache.remove(id)
    }

    fun invalidateAll() {
        customCache.clear()
    }

    private fun loadCustom(ctx: Context, id: String): Typeface? {
        customCache[id]?.let { return it }
        val file = CustomFontStore.fileForId(ctx, id) ?: return null
        val tf = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        customCache[id] = tf
        return tf
    }
}
