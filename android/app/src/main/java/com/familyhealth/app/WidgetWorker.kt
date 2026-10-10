package com.familyhealth.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
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
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val DAY = 86400000L

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

        // v80：任何异常都不允许「静默失败」——失败也更新出可见的错误状态
        fun refresh(context: Context) {
            try {
                refreshInternal(context)
            } catch (e: Exception) {
                try {
                    showErrorState(context, "更新失败")
                } catch (_: Exception) {}
            }
        }

        private fun startOfDay(ms: Long): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        // ts 归一化——Number/String 兼容 + 秒级时间戳(<10^11)自动转毫秒
        private fun normalizeTs(v: Any?): Long? {
            val raw = when (v) {
                is Number -> v.toLong()
                is String -> v.toDoubleOrNull()?.toLong() ?: return null
                else -> return null
            }
            return if (raw in 1..99999999999L) raw * 1000 else raw
        }

        private fun fmtDate(ms: Long): String {
            val c = Calendar.getInstance()
            c.timeInMillis = ms
            return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
        }

        private fun refreshInternal(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_menstrual)
            val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val url = prefs.getString("url", "") ?: ""
            val key = prefs.getString("key", "") ?: ""

            // 界面状态
            var pillText = "未配置"
            var pillBgRes = R.drawable.pill_gray
            var pillTextColorRes = R.color.w_text_main
            var infoText = "打开APP设置→保存配置"
            var preText = "距下次月经还有"
            var dayText = "—"
            var dayColorRes = R.color.w_text_main
            var statusText = ""
            var statusGreen = false
            var ringCycle = 28
            var ringDay = 0 // 0 = 不高亮任何点

            if (url.isNotBlank() && key.isNotBlank()) {
                try {
                    val api = url.trimEnd('/') +
                        "/rest/v1/body_events?category=eq.menstrual&select=ts,member,payload&order=ts.asc"
                    val conn = URL(api).openConnection() as HttpURLConnection
                    conn.setRequestProperty("apikey", key)
                    conn.setRequestProperty("Authorization", "Bearer $key")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val arr = JSONArray(body)

                    // 按成员分组收集月经起点，取「最近一次记录最新」的成员
                    val byMember = HashMap<String, MutableList<Long>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val p = o.optJSONObject("payload")
                        if (p != null && !p.optBoolean("is_period_start", true)) continue
                        val ts = normalizeTs(o.opt("ts")) ?: continue
                        val m = o.optString("member", "")
                        byMember.getOrPut(m) { mutableListOf() }.add(ts)
                    }

                    if (byMember.isEmpty()) {
                        pillText = "暂无记录"
                        infoText = "在APP里记录一次月经"
                    } else {
                        val latest = byMember.values.maxByOrNull { it.maxOrNull() ?: 0L }!!
                        val sorted = latest.map { startOfDay(it) }.sorted()
                        // 与网页同步——剔除 15~45 天外离群周期 + 最近 6 个有效周期加权平均(6:5:4:3:2:1)
                        val allGaps = sorted.zipWithNext { a, b -> ((b - a) / DAY).toInt() }
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
                        val last0 = sorted.last()
                        val today0 = startOfDay(System.currentTimeMillis())
                        val dayIn = ((today0 - last0) / DAY).toInt() + 1
                        val next0 = last0 + avg * DAY
                        val daysLeft = ((next0 - today0) / DAY).toInt()
                        val ovu = avg - 14
                        val phase = when {
                            dayIn <= 5 -> "月经期"
                            dayIn in (ovu - 2)..(ovu + 2) -> "排卵期"
                            dayIn < ovu - 2 -> "卵泡期"
                            else -> "黄体期"
                        }
                        // 顶部药丸 = 时期（按时期配色）
                        pillText = phase
                        pillBgRes = when (phase) {
                            "月经期" -> R.drawable.pill_menstrual
                            "卵泡期" -> R.drawable.pill_follicular
                            "排卵期" -> R.drawable.pill_ovulation
                            else -> R.drawable.pill_luteal
                        }
                        pillTextColorRes = when (phase) {
                            "月经期" -> R.color.w_menstrual_dark
                            "卵泡期" -> R.color.w_follicular_dark
                            "排卵期" -> R.color.w_ovulation_dark
                            else -> R.color.w_luteal_dark
                        }
                        dayColorRes = when (phase) {
                            "月经期" -> R.color.w_menstrual
                            "卵泡期" -> R.color.w_follicular
                            "排卵期" -> R.color.w_ovulation
                            else -> R.color.w_luteal
                        }
                        infoText = "上次${fmtDate(last0)} · 周期约${avg}天"
                        preText = if (daysLeft < 0) "月经已推迟" else "距下次月经还有"
                        dayText = abs(daysLeft).toString()
                        ringCycle = avg
                        ringDay = dayIn
                        // 底部状态药丸
                        when {
                            daysLeft < 0 -> { statusText = "该记录啦"; statusGreen = false }
                            daysLeft <= 2 -> { statusText = "经期将至"; statusGreen = false }
                            else -> { statusText = "周期规律"; statusGreen = true }
                        }
                    }
                } catch (e: Exception) {
                    pillText = "网络错误"
                    infoText = "重新打开APP即可重试"
                }
            }

            views.setTextViewText(R.id.tv_date, pillText)
            views.setInt(R.id.tv_date, "setBackgroundResource", pillBgRes)
            views.setTextColor(R.id.tv_date, context.getColor(pillTextColorRes))
            views.setTextViewText(R.id.tv_phase, infoText)
            views.setTextColor(R.id.tv_phase, context.getColor(R.color.w_text_soft))
            views.setTextViewText(R.id.tv_pre, preText)
            views.setTextViewText(R.id.tv_days, dayText)
            views.setTextColor(R.id.tv_days, context.getColor(dayColorRes))
            if (statusText.isNotEmpty()) {
                views.setTextViewText(R.id.tv_status, statusText)
                views.setTextColor(
                    R.id.tv_status,
                    context.getColor(if (statusGreen) R.color.w_status_green_dark else android.R.color.white)
                )
                views.setInt(
                    R.id.tv_status, "setBackgroundResource",
                    if (statusGreen) R.drawable.pill_green else R.drawable.pill_red
                )
                views.setViewVisibility(R.id.tv_status, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_status, View.INVISIBLE)
            }
            // 环形点阵单独兜底：即使绘制失败也不影响文字更新
            try {
                views.setImageViewBitmap(R.id.iv_ring, buildRingBitmap(ringCycle, ringDay, pillText))
            } catch (_: Exception) {}

            // 点击小组件打开 APP
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            manager.updateAppWidget(ComponentName(context, MenstrualWidget::class.java), views)
        }

        /** 失败兜底：只更新文字状态，让用户知道小组件活着、错在哪 */
        private fun showErrorState(context: Context, msg: String) {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_menstrual)
            views.setTextViewText(R.id.tv_date, msg)
            views.setInt(R.id.tv_date, "setBackgroundResource", R.drawable.pill_gray)
            views.setTextColor(R.id.tv_date, context.getColor(R.color.w_text_main))
            views.setTextViewText(R.id.tv_phase, "")
            views.setTextViewText(R.id.tv_pre, "")
            views.setTextViewText(R.id.tv_days, "—")
            views.setTextColor(R.id.tv_days, context.getColor(R.color.w_text_main))
            views.setViewVisibility(R.id.tv_status, View.INVISIBLE)
            manager.updateAppWidget(ComponentName(context, MenstrualWidget::class.java), views)
        }

        /**
         * 环形点阵 Bitmap：点数 = 周期天数（18~45 均匀排一圈，第1天从顶部顺时针），
         * 颜色按时期分段，今天的位置放大高亮。
         * 256px（≈256KB，远小于 Binder 1MB 限制——v79 的 480px 超限导致整次更新失败）
         */
        private fun buildRingBitmap(cycleLen: Int, today: Int, phase: String): Bitmap {
            val size = 256
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val radius = size * 0.46f
            val n = cycleLen.coerceIn(18, 45)
            val baseR = size * (if (n > 34) 0.019f else 0.023f)
            val ovuStart = n - 14

            val mensColor = 0xFFF7A6C6.toInt()
            val ovuColor = 0xFFF8B84E.toInt()
            val follicle = intArrayOf(0xFF9FDD8C.toInt(), 0xFFC8E86E.toInt(), 0xFFF2E36B.toInt())
            val luteal = intArrayOf(0xFFC9AFFB.toInt(), 0xFFEFB1EF.toInt(), 0xFFF9A8C0.toInt())
            val todayColor = when {
                phase.startsWith("月经期") -> 0xFFEC4899.toInt()
                phase.startsWith("排卵期") -> 0xFFF59E0B.toInt()
                phase.startsWith("卵泡期") -> 0xFF84CC16.toInt()
                phase.startsWith("黄体期") -> 0xFFA855F7.toInt()
                else -> 0xFFB9AFA7.toInt()
            }
            val highlight = if (today >= 1) ((today - 1) % n) + 1 else 0

            for (d in 1..n) {
                val ang = Math.toRadians(-90.0 + 360.0 * (d - 1) / n)
                val x = cx + radius * cos(ang).toFloat()
                val y = cy + radius * sin(ang).toFloat()
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                if (d == highlight) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = baseR * 0.9f
                    p.color = 0x2E000000.toInt()
                    canvas.drawCircle(x, y, baseR * 2.0f, p)
                    p.style = Paint.Style.FILL
                    p.color = todayColor
                    canvas.drawCircle(x, y, baseR * 1.6f, p)
                } else {
                    p.color = when {
                        d <= 5 -> mensColor
                        d in (ovuStart - 2)..(ovuStart + 2) -> ovuColor
                        d < ovuStart - 2 -> follicle[(d - 1) % follicle.size]
                        else -> luteal[(d - 1) % luteal.size]
                    }
                    canvas.drawCircle(x, y, baseR, p)
                }
            }
            return bmp
        }
    }
}
