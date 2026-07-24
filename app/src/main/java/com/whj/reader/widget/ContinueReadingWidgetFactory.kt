package com.whj.reader.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.whj.reader.R

class ContinueReadingWidgetFactory(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") intent: Intent,
) : android.widget.RemoteViewsService.RemoteViewsFactory {

    private var items: List<ContinueReadingResolver.Snapshot> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        items = ContinueReadingResolver.resolveList(
            context,
            ContinueReadingResolver.DEFAULT_SLOT_COUNT,
        )
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val snap = items[position]
        val rv = RemoteViews(context.packageName, R.layout.widget_continue_reading_item)
        rv.setTextViewText(R.id.tvTitle, snap.title)
        rv.setTextViewText(R.id.tvProgress, snap.progressPercent)
        rv.setInt(
            R.id.itemRoot,
            "setBackgroundResource",
            if (position % 2 == 0) R.drawable.widget_row_bg_even else R.drawable.widget_row_bg_odd,
        )
        val fillIn = Intent().apply {
            putExtra(ContinueReadingWidget.EXTRA_URI, snap.uri)
            putExtra(ContinueReadingWidget.EXTRA_TITLE, snap.title)
            putExtra(ContinueReadingWidget.EXTRA_IS_PDF, snap.isPdf)
            putExtra(ContinueReadingWidget.EXTRA_ENCODING, snap.encoding)
        }
        rv.setOnClickFillInIntent(R.id.itemRoot, fillIn)
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.uri?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
