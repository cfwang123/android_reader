package com.whj.reader.widget

import android.content.Intent
import android.widget.RemoteViewsService

class ContinueReadingWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ContinueReadingWidgetFactory(applicationContext, intent)
}
