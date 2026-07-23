package com.whj.reader.pdf.render

import android.graphics.Bitmap
import com.whj.reader.ui.PdfPageSurface

/** 主线程贴图限流队列条目 */
data class PdfUiAttach(
    val surface: PdfPageSurface,
    val page: Int,
    val bindGen: Long,
    val bmp: Bitmap,
    val isTile: Boolean,
    val tileIndex: Int = 0,
)
