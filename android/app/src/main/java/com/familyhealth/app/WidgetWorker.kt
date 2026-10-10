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

        // v79：对齐到本地零点——带时刻的 ts 直接相减会因截断偏一天
        private fun startOfDay(ms: Long): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        // v79：ts 归一化——Number/String 兼容 + 秒级时间戳(<10^11)自动转毫秒
        private fun normalizeTs(v: Any?): Long? {
            val raw = when (v) {
                is Number -> v.toLong()
                is String -> v.toDoubleOrNull()?.toLong() ?: return null
                else -> return null
            }
            return if (raw in 1..99999999999L) raw * 1000 else raw
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_menstrual)
            val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val url = prefs.getString("url", "") ?: ""
            val key = prefs.getString("key", "") ?: ""

            var dayText = "—"
            var phaseText = "请先打开APP配置"
            var statusText = ""
            var statusGreen = false
            var ringCycle = 28
            var ringDay = 0 // 0 = 不高亮任何点

            // 今天日期（无论有无数据都显示，修复「不显示日期」）
            val cal = Calendar.getInstance()
            val dateText = "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"

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

                    // v79：按成员分组收集月经起点，取「最近一次记录最新」的成员，
                    // 避免多成员数据混在一起把周期算乱
                    val byMember = HashMap<String, MutableList<Long>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val p = o.optJSONObject("payload")
                        // is_period_start === false 的不是月经起点，跳过（与网页逻辑一致）
                        if (p != null && !p.optBoolean("is_period_start", true)) continue
                        val ts = normalizeTs(o.opt("ts")) ?: continue
                        val m = o.optString("member", "")
                        byMember.getOrPut(m) { mutableListOf() }.add(ts)
                    }

                    if (byMember.isEmpty()) {
                        phaseText = "暂无月经记录"
                    } else {
                        val latest = byMember.values.maxByOrNull { it.maxOrNull() ?: 0L }!!
                        val sorted = latest.map { startOfDay(it) }.sorted()
                        // v66：与网页同步——剔除 15~45 天外离群周期 + 最近 6 个有效周期加权平均(6:5:4:3:2:1)
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
                        phaseText = when {
                            dayIn <= 5 -> "月经期"
                            dayIn in (ovu - 2)..(ovu + 2) -> "排卵期"
                            dayIn < ovu - 2 -> "卵泡期"
                            else -> "黄体期"
                        }
                        dayText = dayIn.coerceAtLeast(1).toString()
                        ringCycle = avg
                        ringDay = dayIn
                        when {
                            daysLeft < 0 -> { statusText = "已推迟 ${-daysLeft} 天"; statusGreen = false }
                            daysLeft <= 2 -> { statusText = "经期将至"; statusGreen = false }
                            else -> { statusText = "距下次 $daysLeft 天"; statusGreen = true }
                        }
                    }
                } catch (e: Exception) {
                    phaseText = "网络错误"
                }
            }

            // 配色：中心数字/时期文字按时期主题色；状态药丸绿=正常 红=临近/推迟
            val isPhase = phaseText in listOf("月经期", "卵泡期", "排卵期", "黄体期")
            val dayColorRes = when (phaseText) {
                "月经期" -> R.color.w_menstrual
                "卵泡期" -> R.color.w_follicular
                "排卵期" -> R.color.w_ovulation
                "黄体期" -> R.color.w_luteal
                else -> R.color.w_text_main
            }
            val phaseColorRes = if (isPhase) dayColorRes else R.color.w_text_soft

            views.setTextViewText(R.id.tv_date, dateText)
            views.setTextViewText(R.id.tv_phase, phaseText)
            views.setTextColor(R.id.tv_phase, context.getColor(phaseColorRes))
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
            // v79：环形点阵（参考周期圆环设计）
            views.setImageViewBitmap(R.id.iv_ring, buildRingBitmap(ringCycle, ringDay, phaseText))

            // 点击小组件打开 APP
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            manager.updateAppWidget(ComponentName(context, MenstrualWidget::class.java), views)
        }

        /**
         * v79：环形点阵 Bitmap——参考周期圆环图：
         * 点数 = 周期天数（18~45 均匀排一圈，从顶部顺时针 = 第1天），
         * 颜色按时期分段（月经期粉 / 卵泡期绿黄 / 排卵期琥珀 / 黄体期紫粉），
         * 今天的位置放大高亮（灰环 + 时期主题色实心点）。
         */
        private fun buildRingBitmap(cycleLen: Int, today: Int, phase: String): Bitmap {
            val size = 480
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val radius = size * 0.45f
            val n = cycleLen.coerceIn(18, 45)
            val baseR = size * (if (n > 34) 0.019f else 0.023f)
            val ovuStart = n - 14

            val mensColor = 0xFFF7A6C6.toInt()
            val ovuColor = 0xFFF8B84E.toInt()
            val follicle = intArrayOf(0xFF9FDD8C.toInt(), 0xFFC8E86E.toInt(), 0xFFF2E36B.toInt())
            val luteal = intArrayOf(0xFFC9AFFB.toInt(), 0xFFEFB1EF.toInt(), 0xFFF9A8C0.toInt())
            val todayColor = when (phase) {
                "月经期" -> 0xFFEC4899.toInt()
                "排卵期" -> 0xFFF59E0B.toInt()
                "卵泡期" -> 0xFF84CC16.toInt()
                "黄体期" -> 0xFFA855F7.toInt()
                else -> 0xFFB9AFA7.toInt()
            }
            // 今天落在环上的位置（dayIn 超过周期长度时取模回到环上）
            val highlight = if (today >= 1) ((today - 1) % n) + 1 else 0

            for (d in 1..n) {
                val ang = Math.toRadians(-90.0 + 360.0 * (d - 1) / n)
                val x = cx + radius * cos(ang).toFloat()
                val y = cy + radius * sin(ang).toFloat()
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                if (d == highlight) {
                    // 今天：柔和高亮环 + 放大实心点
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
