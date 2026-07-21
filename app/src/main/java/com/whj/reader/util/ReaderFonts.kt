package com.whj.reader.util

import android.content.Context
import android.graphics.Typeface
import com.whj.reader.R
import com.whj.reader.data.CustomFontStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读字体：系统预设 + 用户安装的自定义 TTF/OTF。
 * 不含内嵌商业字体（避免再分发许可问题）。
 */
object ReaderFonts {
    /** 默认 = 系统默认字体 */
    const val ID_DEFAULT = "default"
    const val ID_SANS = "sans"
    const val ID_SERIF = "serif"
    const val ID_MONO = "mono"

    /**
     * 旧版内置方正 id，仅作兼容：解析时等同 [ID_DEFAULT]。
     * 新保存的样式不再写入此 id。
     */
    const val ID_FZ_ZHENGXIAN = "builtin:fz_zhengxianhei"

    const val CUSTOM_PREFIX = "custom:"

    val ALL_IDS = listOf(ID_DEFAULT, ID_SANS, ID_SERIF, ID_MONO)

    private val customCache = ConcurrentHashMap<String, Typeface>()

    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)

    fun isBuiltin(id: String): Boolean =
        id == ID_DEFAULT ||
            id == ID_SANS ||
            id == ID_SERIF ||
            id == ID_MONO ||
            id == ID_FZ_ZHENGXIAN ||
            id.startsWith("builtin:")

    /** 归一化：废弃内置 id → 默认 */
    fun normalizeId(id: String): String =
        when {
            id == ID_FZ_ZHENGXIAN || id.startsWith("builtin:") -> ID_DEFAULT
            else -> id
        }

    fun label(ctx: Context, id: String): String {
        val nid = normalizeId(id)
        if (isCustom(nid)) {
            return CustomFontStore.find(ctx, nid)?.name
                ?: ctx.getString(R.string.font_custom_unknown)
        }
        return when (nid) {
            ID_SANS -> ctx.getString(R.string.font_sans)
            ID_SERIF -> ctx.getString(R.string.font_serif)
            ID_MONO -> ctx.getString(R.string.font_mono)
            else -> ctx.getString(R.string.font_default)
        }
    }

    /**
     * 解析 Typeface。系统预设 / 自定义文件；失败回退系统默认。
     */
    fun resolve(ctx: Context, id: String, bold: Boolean = false): Typeface {
        val nid = normalizeId(id)
        val base = when {
            isCustom(nid) -> loadCustom(ctx, nid) ?: Typeface.DEFAULT
            else -> when (nid) {
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

    /** 兼容旧调用：无 Context 时仅系统预设 / 已缓存自定义 */
    fun resolve(id: String, bold: Boolean = false): Typeface {
        val nid = normalizeId(id)
        if (isCustom(nid)) {
            val cached = customCache[nid]
            if (cached != null) {
                return if (bold) {
                    Typeface.create(cached, Typeface.BOLD) ?: Typeface.DEFAULT_BOLD
                } else {
                    cached
                }
            }
            return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val base = when (nid) {
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
