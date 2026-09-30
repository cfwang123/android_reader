package com.whj.reader

import android.app.Application
import com.whj.reader.data.LocaleHelper
import com.whj.reader.util.AutoCloseController

class ReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 在界面创建前恢复用户选择的中/英文
        LocaleHelper.applyFromSettings(this)
        // 无操作自动关闭（默认 1 小时）
        AutoCloseController.init(this)
        // 删掉以前复制进缓存的整本 MOBI/EPUB 和抽出的图片
        Thread {
            runCatching { com.whj.reader.data.EbookCacheCleaner.dropCopiedBooks(this) }
        }.apply {
            name = "ebook-cache-clean"
            isDaemon = true
            start()
        }
    }
}
