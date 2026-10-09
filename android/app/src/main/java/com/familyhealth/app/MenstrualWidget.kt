package com.familyhealth.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class MenstrualWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetWorker.schedule(context)
        WidgetWorker.scheduleNow(context)
    }

    override fun onEnabled(context: Context) {
        WidgetWorker.schedule(context)
        WidgetWorker.scheduleNow(context)
    }
}
