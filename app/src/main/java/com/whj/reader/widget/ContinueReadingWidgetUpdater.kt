package com.whj.reader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.ReadingActivity

object ContinueReadingWidgetUpdater {

    fun updateAll(ctx: Context) {
        val appCtx = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(appCtx)
        val cn = ComponentName(appCtx, ContinueReadingWidget::class.java)
        val ids = mgr.getAppWidgetIds(cn)
        if (ids.isEmpty()) return
        update(appCtx, mgr, ids)
    }

    fun update(ctx: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_continue_reading)
            bindList(ctx, views, id)
            views.setOnClickPendingIntent(R.id.tvEmpty, openMainPendingIntent(ctx, id))
            mgr.updateAppWidget(id, views)
        }
        mgr.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.listBooks)
    }

    private fun bindList(ctx: Context, views: RemoteViews, widgetId: Int) {
        val serviceIntent = Intent(ctx, ContinueReadingWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.listBooks, serviceIntent)
        views.setEmptyView(R.id.listBooks, R.id.tvEmpty)

        val clickIntent = Intent(ctx, ContinueReadingWidget::class.java).apply {
            action = ContinueReadingWidget.ACTION_ITEM_CLICK
        }
        val template = PendingIntent.getBroadcast(
            ctx,
            widgetId,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        views.setPendingIntentTemplate(R.id.listBooks, template)
        views.setViewVisibility(R.id.headerBar, View.VISIBLE)
    }

    private fun openMainPendingIntent(ctx: Context, requestCode: Int): PendingIntent {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildOpenIntent(ctx: Context, snap: ContinueReadingResolver.Snapshot): Intent =
        buildOpenIntent(ctx, snap.uri, snap.title, snap.isPdf, snap.encoding)

    fun buildOpenIntent(
        ctx: Context,
        uri: String,
        title: String,
        isPdf: Boolean,
        encoding: String?,
    ): Intent {
        if (uri.startsWith("asset://")) {
            val path = uri.removePrefix("asset://")
            return Intent(ctx, ReadingActivity::class.java)
                .putExtra(ReadingActivity.EXTRA_ASSET, path)
                .putExtra(ReadingActivity.EXTRA_TITLE, title)
                .putExtra(ReadingActivity.EXTRA_ENCODING, encoding)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (isPdf) {
            return Intent(ctx, PdfReadingActivity::class.java)
                .putExtra(PdfReadingActivity.EXTRA_URI, uri)
                .putExtra(PdfReadingActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return Intent(ctx, ReadingActivity::class.java)
            .putExtra(ReadingActivity.EXTRA_URI, uri)
            .putExtra(ReadingActivity.EXTRA_TITLE, title)
            .putExtra(ReadingActivity.EXTRA_ENCODING, encoding)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
