package com.whj.reader.util

import android.content.Context
import android.graphics.Typeface
import com.whj.reader.R

/**
 * TXT 阅读系统字体预设（不打包 ttf）。
 */
object ReaderFonts {
    const val ID_DEFAULT = "default"
    const val ID_SANS = "sans"
    const val ID_SERIF = "serif"
    const val ID_MONO = "mono"

    val ALL_IDS = listOf(ID_DEFAULT, ID_SANS, ID_SERIF, ID_MONO)

    fun label(ctx: Context, id: String): String = when (id) {
        ID_SANS -> ctx.getString(R.string.font_sans)
        ID_SERIF -> ctx.getString(R.string.font_serif)
        ID_MONO -> ctx.getString(R.string.font_mono)
        else -> ctx.getString(R.string.font_default)
    }

    fun resolve(id: String, bold: Boolean = false): Typeface {
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
}
