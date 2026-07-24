package com.whj.reader.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** 桌面「继续阅读」小组件：最近 15 本书，可滚动，点击恢复阅读。 */
class ContinueReadingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        ContinueReadingWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        ContinueReadingWidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_ITEM_CLICK) {
            val uri = intent.getStringExtra(EXTRA_URI) ?: return
            val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            val isPdf = intent.getBooleanExtra(EXTRA_IS_PDF, false)
            val encoding = intent.getStringExtra(EXTRA_ENCODING)
            val open = ContinueReadingWidgetUpdater.buildOpenIntent(
                context,
                uri,
                title,
                isPdf,
                encoding,
            )
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(open)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_ITEM_CLICK = "com.whj.reader.widget.ACTION_ITEM_CLICK"
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_PDF = "is_pdf"
        const val EXTRA_ENCODING = "encoding"
    }
}
