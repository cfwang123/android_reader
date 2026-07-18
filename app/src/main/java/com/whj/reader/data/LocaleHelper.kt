package com.whj.reader.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.whj.reader.model.AppLanguage

object LocaleHelper {

    fun apply(language: AppLanguage) {
        val tags = when (language) {
            AppLanguage.ZH -> "zh-CN"
            AppLanguage.EN -> "en"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    }

    fun applyFromSettings(ctx: Context) {
        apply(AppSettings.appLanguage(ctx))
    }

    fun current(): AppLanguage {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tag.startsWith("en", ignoreCase = true) -> AppLanguage.EN
            tag.startsWith("zh", ignoreCase = true) -> AppLanguage.ZH
            else -> AppLanguage.ZH
        }
    }
}
