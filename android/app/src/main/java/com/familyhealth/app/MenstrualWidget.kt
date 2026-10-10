package com.familyhealth.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class MenstrualWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetWorker.schedule(context)
        // v80：添加/更新小组件时立即后台刷新一次，不等 WorkManager 排队
        Thread { WidgetWorker.refresh(context) }.start()
    }

    override fun onEnabled(context: Context) {
        WidgetWorker.schedule(context)
        Thread { WidgetWorker.refresh(context) }.start()
    }
}
