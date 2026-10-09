package com.familyhealth.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        refresh(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<WidgetWorker>(3, TimeUnit.HOURS).build()
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    "menstrual_widget", ExistingPeriodicWorkPolicy.KEEP, req
                )
            } catch (_: Exception) {}
        }

        fun scheduleNow(ctx: Context) {
            try {
                val req = OneTimeWorkRequestBuilder<WidgetWorker>().build()
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "menstrual_now", ExistingWorkPolicy.REPLACE, req
                )
            } catch (_: Exception) {}
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_menstrual)
            val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val url = prefs.getString("url", "") ?: ""
            val key = prefs.getString("key", "") ?: ""

            var daysText = "—"
            var subText = "距下次月经"
            var phaseText = "请先打开APP配置"

            if (url.isNotBlank() && key.isNotBlank()) {
                try {
                    val api = url.trimEnd('/') +
                        "/rest/v1/body_events?category=eq.menstrual&select=ts,payload&order=ts.asc"
                    val conn = URL(api).openConnection() as HttpURLConnection
                    conn.setRequestProperty("apikey", key)
                    conn.setRequestProperty("Authorization", "Bearer $key")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val arr = JSONArray(body)
                    val starts = mutableListOf<Long>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val p = o.optJSONObject("payload")
                        // is_period_start === false 的不是月经起点，跳过（与网页逻辑一致）
                        if (p != null && !p.optBoolean("is_period_start", true)) continue
                        starts.add(o.getLong("ts"))
                    }

                    val day = 86400000L
                    if (starts.isEmpty()) {
                        subText = "距下次月经"
                        phaseText = "暂无月经记录"
                    } else {
                        val sorted = starts.sorted()
                        val gaps = sorted.zipWithNext { a, b -> ((b - a) / day).toInt() }
                        val avg = if (gaps.isNotEmpty()) (gaps.sum() / gaps.size).coerceIn(20, 45) else 28
                        val last = sorted.last()
                        val next = last + avg * day
                        val now = System.currentTimeMillis()
                        val days = ceil((next - now).toDouble() / day).toInt()
                        val dayIn = ((now - last) / day).toInt() + 1
                        val ovu = avg - 14
                        phaseText = when {
                            dayIn <= 5 -> "月经期"
                            dayIn in (ovu - 2)..(ovu + 2) -> "排卵期"
                            dayIn < ovu - 2 -> "卵泡期"
                            else -> "黄体期"
                        }
                        if (days > 0) {
                            daysText = days.toString()
                            subText = "天 · 距下次月经"
                        } else {
                            daysText = "0"
                            subText = "经期将至 · 该记录啦"
                        }
                    }
                } catch (e: Exception) {
                    daysText = "—"
                    subText = "距下次月经"
                    phaseText = "网络错误"
                }
            }

            views.setTextViewText(R.id.tv_days, daysText)
            views.setTextViewText(R.id.tv_sub, subText)
            views.setTextViewText(R.id.tv_phase, phaseText)

            // 点击小组件打开 APP
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            manager.updateAppWidget(ComponentName(context, MenstrualWidget::class.java), views)
        }
    }
}
