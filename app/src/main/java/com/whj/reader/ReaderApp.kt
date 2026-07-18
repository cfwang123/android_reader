package com.whj.reader

import android.app.Application
import com.whj.reader.data.LocaleHelper

class ReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 在界面创建前恢复用户选择的中/英文
        LocaleHelper.applyFromSettings(this)
    }
}
