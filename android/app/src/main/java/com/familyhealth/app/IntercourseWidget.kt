package com.familyhealth.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v104：1×1 同房快捷记录小组件
 * 点击 → 向 Supabase body_events 插入一条当日同房记录（category=intercourse，
 * member=文文，ts=点击时刻）→ Toast 提示已记录；数据实时同步到 APP 日历。
 */
class IntercourseWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) bindClick(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        // 与月经小组件共用保活
        WidgetWorker.schedule(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_RECORD) {
            // goAsync：broadcast 有 10s 限制，网络请求放后台线程
            val pending = goAsync()
            Thread {
                try {
                    recordIntercourse(context)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    private fun bindClick(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_intercourse)
        val intent = Intent(context, IntercourseWidget::class.java).apply { action = ACTION_RECORD }
        val pi = PendingIntent.getBroadcast(
            context, appWidgetId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.intercourse_root, pi)
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun recordIntercourse(context: Context) {
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val url = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = (prefs.getString("key", "") ?: "").trim()
        if (url.isBlank() || key.isBlank()) {
            toast(context, "请先打开 APP 完成配置")
            return
        }
        try {
            val body = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("end_ts", JSONObject.NULL)
                put("member", MEMBER)
                put("category", "intercourse")
                put("severity", 3)
                put("title", "❤️")
                put("payload", JSONObject())
                put("tags", JSONArrayEmpty)
                put("note", "")
            }
            val conn = URL("$url/rest/v1/body_events").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                toast(context, "✓ 已记录一次同房 ❤️")
            } else {
                toast(context, "记录失败 (HTTP $code)，请打开 APP 检查")
            }
        } catch (e: Exception) {
            toast(context, "网络错误，记录失败")
        }
    }

    private fun toast(context: Context, msg: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ACTION_RECORD = "com.familyhealth.app.ACTION_RECORD_INTERCOURSE"
        private const val MEMBER = "文文"
        private val JSONArrayEmpty = org.json.JSONArray()
    }
}
