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
import androidx.work.WorkManager
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
                        // v75：兼容 ts 为字符串的情况（Supabase/PostgREST 可能将 int8 序列化为字符串，
                        // o.getLong 会抛异常导致整次刷新失败 → 小组件一直显示初始文案）
                        val tsVal = o.opt("ts")
                        val ts = when (tsVal) {
                            is Number -> tsVal.toLong()
                            is String -> tsVal.toDoubleOrNull()?.toLong() ?: continue
                            else -> continue
                        }
                        starts.add(ts)
                    }

                    val day = 86400000L
                    if (starts.isEmpty()) {
                        subText = "距下次月经"
                        phaseText = "暂无月经记录"
                    } else {
                        val sorted = starts.sorted()
                        // v66：与网页同步——剔除 15~45 天外离群周期 + 最近 6 个有效周期加权平均(6:5:4:3:2:1)
                        val allGaps = sorted.zipWithNext { a, b -> ((b - a) / day).toInt() }
                        val validGaps = allGaps.filter { it in 15..45 }
                        val avg = when {
                            validGaps.isEmpty() -> 28
                            validGaps.size >= 3 -> {
                                val recent = validGaps.takeLast(6)
                                var ws = 0.0; var vs = 0.0
                                recent.forEachIndexed { i, v ->
                                    val w = (i + 1 + (6 - recent.size)).toDouble()
                                    ws += w; vs += v * w
                                }
                                (vs / ws).toInt().coerceIn(15, 45)
                            }
                            else -> (validGaps.sum().toDouble() / validGaps.size).toInt().coerceIn(15, 45)
                        }
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

            // v75：配色重设计——背景渐变 + 每个时期专属配色（玫瑰红/紫/琥珀/青绿 ≥3 色）
            val (daysColorRes, pillBgRes, pillTextRes) = when (phaseText) {
                "月经期" -> Triple(R.color.w_menstrual, R.drawable.phase_pill_menstrual, R.color.w_menstrual_dark)
                "卵泡期" -> Triple(R.color.w_follicular, R.drawable.phase_pill_follicular, R.color.w_follicular_dark)
                "排卵期" -> Triple(R.color.w_ovulation, R.drawable.phase_pill_ovulation, R.color.w_ovulation_dark)
                "黄体期" -> Triple(R.color.w_luteal, R.drawable.phase_pill_luteal, R.color.w_luteal_dark)
                else -> Triple(R.color.w_text_main, R.drawable.phase_pill, R.color.w_phase_text)
            }
            views.setTextViewText(R.id.tv_days, daysText)
            views.setTextColor(R.id.tv_days, context.getColor(daysColorRes))
            views.setTextViewText(R.id.tv_sub, subText)
            views.setTextViewText(R.id.tv_phase, phaseText)
            views.setTextColor(R.id.tv_phase, context.getColor(pillTextRes))
            views.setInt(R.id.tv_phase, "setBackgroundResource", pillBgRes)
            views.setTextColor(R.id.tv_title, context.getColor(R.color.w_title_pink))

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
