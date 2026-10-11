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
 * v105b：1×1 爱心小组件（双击确认版）
 * 单击 → 组件上亮「再点一次」提示；窗口内再点一次 → 记录当日一次同房
 * （category=intercourse，member=文文，ts=当前时刻）→ 数据实时同步到 APP 日历。
 *
 * v105b 修复双击不生效：
 * 1) 窗口从 600ms 放宽到 1500ms——小组件 PendingIntent 广播有系统投递延迟
 *    （尤其冷启动），两次物理点击的送达时间差可能远大于点击间隔
 * 2) @Synchronized 串行化双击判定——两个广播各起一个线程，并发读写 prefs 有竞态
 * 3) 判定用「送达时刻」差值，两次投递延迟近似抵消
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
            val pending = goAsync()  // broadcast 10s 限制，网络请求放后台线程
            Thread {
                try {
                    handleTap(context)
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

    /** 双击判定（串行化）：窗口内第二次点击 = 记录；第一击亮提示 */
    @Synchronized
    private fun handleTap(context: Context) {
        val now = System.currentTimeMillis()
        val last = lastTapMs
        if (last > 0 && now - last <= DOUBLE_CLICK_MS) {
            // 第二击 → 记录
            lastTapMs = 0L
            context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_TAP, 0L).apply()
            setHint(context, "")
            recordIntercourse(context)
        } else {
            // 第一击 → 亮提示，等待第二击
            lastTapMs = now
            context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_TAP, now).apply()
            setHint(context, "再点一次")
            toast(context, "再点一次确认记录 ❤️")
            // 窗口过后若没等到第二击，自动复位提示
            Thread {
                Thread.sleep(DOUBLE_CLICK_MS + 800)
                if (lastTapMs != 0L && System.currentTimeMillis() - lastTapMs > DOUBLE_CLICK_MS) {
                    lastTapMs = 0L
                    setHint(context, "")
                }
            }.start()
        }
    }

    /** 提示直接改小组件文字（不依赖 Toast，部分 ROM 会静默吞掉后台 toast） */
    private fun setHint(context: Context, text: String) {
        try {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_intercourse)
            views.setTextViewText(R.id.tv_hint, text)
            views.setTextViewText(R.id.tv_heart, if (text.isEmpty()) "❤️" else "💗")
            manager.updateAppWidget(ComponentName(context, IntercourseWidget::class.java), views)
        } catch (_: Exception) {}
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
        private const val KEY_LAST_TAP = "ic_last_tap"
        private const val DOUBLE_CLICK_MS = 1500L
        private val JSONArrayEmpty = org.json.JSONArray()
        /** 进程内存活的上次点击送达时刻（比 prefs 更可靠，避免并发读写竞态） */
        @Volatile
        private var lastTapMs: Long = 0L
    }
}
