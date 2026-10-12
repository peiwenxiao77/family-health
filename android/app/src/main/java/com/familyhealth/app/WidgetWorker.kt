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
        // v107：顺带检查 APP 新版本，发现则发系统通知（同版本只提醒一次）
        UpdateChecker.checkAndNotify(applicationContext)
        // v115：续接「跨天刷新链」——每次任务跑完，预约一个到明天 00:02 的一次性任务，
        // 到点自动再刷新小组件（不打开 APP 也会跨天更新预测/经期状态）
        scheduleMidnightRefresh(applicationContext)
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
                // v115：同时启动跨天刷新链（首次装组件即生效）
                scheduleMidnightRefresh(ctx)
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

        /**
         * v115：跨天自动刷新链。
         * 原有 3 小时周期任务跨天最多延迟 3 小时才更新；此链保证每天 00:02 必刷新一次，
         * 且 doWork 每次结束都会续接下一天（自续链，装好组件后永久生效，无需打开 APP）。
         * 与 onEnabled 里的 schedule(ctx) 一起调用，确保链首次被启动。
         */
        fun scheduleMidnightRefresh(ctx: Context) {
            try {
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, 1)
                c.set(Calendar.HOUR_OF_DAY, 0)
                c.set(Calendar.MINUTE, 2)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                val delay = c.timeInMillis - System.currentTimeMillis()
                if (delay <= 0) return
                val req = OneTimeWorkRequestBuilder<WidgetWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "menstrual_midnight", ExistingWorkPolicy.REPLACE, req
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
            var infoText = "打开APP设置→保存配置" // v89：仅错误/空状态显示，正常时隐藏
            var preText = "距下次月经还有"
            var dayText = "—"
            var dayColorRes = R.color.w_text_main
            var statusText = "" // v89：只在经期将至/已推迟时显示，「周期规律」不再显示
            var ringCycle = 28
            var ringDay = 0 // 0 = 不高亮任何点
            var ringPeriodDays = 5
            // v113：经期进度图（phase=月经期时启用）——圆点数=预测经期天数，从12点顺时针均布全红，每天走到下一个点
            var progDay = 0   // 经期内第几天（1 起）；0 = 用正常周期点阵
            var progTotal = 0 // 预测经期天数

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

                    // 按成员分组收集月经起点 + 该次经期天数，取「最近一次记录最新」的成员
                    val byMember = HashMap<String, MutableList<Pair<Long, Int>>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val p = o.optJSONObject("payload")
                        if (p != null && !p.optBoolean("is_period_start", true)) continue
                        val ts = normalizeTs(o.opt("ts")) ?: continue
                        val m = o.optString("member", "")
                        val dur = when (val d = p?.opt("duration_days")) {
                            is Number -> d.toInt()
                            is String -> d.toIntOrNull() ?: 1
                            else -> 1  // v111：单日记录默认 1 天（原默认 5 天会与连续合并逻辑冲突）
                        }.coerceIn(1, 14)
                        byMember.getOrPut(m) { mutableListOf() }.add(Pair(ts, dur))
                    }

                    if (byMember.isEmpty()) {
                        pillText = "暂无记录"
                        infoText = "在APP里记录一次月经"
                    } else {
                        val latest = byMember.values.maxByOrNull { it.maxOfOrNull { p -> p.first } ?: 0L }!!
                        // v111：合并连续/重叠记录（10.1、10.2、10.3-10.5 → 一次 5 天经期）
                        val merged = ArrayList<Pair<Long, Int>>()
                        latest.sortedBy { it.first }.forEach { (rawTs, d) ->
                            val s = startOfDay(rawTs)
                            val end = s + (d - 1) * DAY
                            val last = merged.lastOrNull()
                            if (last != null && s <= last.first + (last.second - 1) * DAY + DAY) {
                                val newEnd = maxOf(last.first + (last.second - 1) * DAY, end)
                                merged[merged.size - 1] = Pair(last.first, (((newEnd - last.first) / DAY).toInt() + 1).coerceIn(1, 14))
                            } else {
                                merged.add(Pair(s, d))
                            }
                        }
                        // 与网页同步——剔除 15~45 天外离群周期 + 最近 6 个有效周期加权平均(6:5:4:3:2:1)
                        val allGaps = merged.zipWithNext { a, b -> ((b.first - a.first) / DAY).toInt() }
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
                        val last0 = merged.last().first
                        val lastDur = merged.last().second
                        val today0 = startOfDay(System.currentTimeMillis())
                        val dayIn = ((today0 - last0) / DAY).toInt() + 1
                        val next0 = last0 + avg * DAY
                        val daysLeft = ((next0 - today0) / DAY).toInt()
                        val ovu = avg - 14
                        /* v112 预测规则（与网页同步）：
                           预存预测经期天数 = 历史经期天数加权平均（最近6次，权重同周期算法，回退5天）；
                           最新一次未记完的经期不计入 → 待定窗长用「不含本轮」的历史均值（predPrev）；
                           待定结束后：本轮记满 ≥ 预存天数 → 正常卵泡期/排卵日/排卵期/黄体期预测；
                           不足 → 只预测下次月经窗（无各期预测，中间显示「经后期」） */
                        fun avgDur(list: List<Int>): Int = when {
                            list.isEmpty() -> 5
                            list.size >= 3 -> {
                                var ws = 0.0; var vs = 0.0
                                list.forEachIndexed { i, v ->
                                    val w = (i + 1 + (6 - list.size)).toDouble()
                                    ws += w; vs += v * w
                                }
                                (vs / ws).toInt().coerceIn(1, 14)
                            }
                            else -> (list.sum().toDouble() / list.size).toInt().coerceIn(1, 14)
                        }
                        val predPrev = avgDur(merged.dropLast(1).map { it.second })  // 待定窗天数（不含本轮）
                        val PRED = avgDur(merged.map { it.second })                  // 下次预测窗天数（全部已判定历史）
                        val phase = when {
                            dayIn <= lastDur -> "月经期"                    // 已记录的经期
                            dayIn <= predPrev -> "月经期"                   // 待定窗内（本轮未判定结束）
                            lastDur < PRED && dayIn >= avg -> "月经期"       // 简化模式：预测的下次月经窗
                            lastDur < PRED -> "经后期"                       // 简化模式：经期外、预测窗前
                            dayIn == ovu -> "排卵日"
                            dayIn in (ovu - 2)..(ovu + 2) -> "排卵期"
                            dayIn < ovu - 2 -> "卵泡期"
                            dayIn < avg -> "黄体期"
                            else -> "月经期"                                 // 记满预存天数且已进入预测窗（推迟）
                        }
                        // 顶部药丸 = 时期（按时期配色）
                        pillText = phase
                        pillBgRes = when (phase) {
                            "月经期" -> R.drawable.pill_menstrual
                            "卵泡期" -> R.drawable.pill_follicular
                            "排卵日" -> R.drawable.pill_ovulation_day
                            "排卵期" -> R.drawable.pill_ovulation
                            else -> R.drawable.pill_luteal
                        }
                        pillTextColorRes = when (phase) {
                            "月经期" -> R.color.w_menstrual_dark
                            "卵泡期" -> R.color.w_follicular_dark
                            "排卵日" -> R.color.w_ovulation_day_dark
                            "排卵期" -> R.color.w_ovulation_dark
                            else -> R.color.w_luteal_dark
                        }
                        dayColorRes = when (phase) {
                            "月经期" -> R.color.w_menstrual
                            "卵泡期" -> R.color.w_follicular
                            "排卵日" -> R.color.w_ovulation_day
                            "排卵期" -> R.color.w_ovulation
                            else -> R.color.w_luteal
                        }
                        infoText = "" // v89：正常状态不再显示「上次·周期约」
                        ringCycle = avg
                        ringDay = dayIn
                        ringPeriodDays = lastDur
                        if (phase == "月经期") {
                            // v113：月经期/预测月经期中 → 天数位置显示「/」，点阵切为经期进度图
                            preText = "距下次月经还有"
                            dayText = "/"
                            val inPending = dayIn <= predPrev
                            progTotal = if (inPending) predPrev else PRED
                            progDay = if (inPending) dayIn else (dayIn - avg + 1).coerceAtLeast(1)
                        } else {
                            preText = if (daysLeft < 0) "月经已推迟" else "距下次月经还有"
                            dayText = abs(daysLeft).toString()
                        }
                        // 底部药丸：v89 只保留警告态，「周期规律」绿色药丸移除
                        if (daysLeft < 0) statusText = "该记录啦"
                        else if (daysLeft <= 2) statusText = "经期将至"
                    }
                } catch (e: Exception) {
                    pillText = "网络错误"
                    infoText = "重新打开APP即可重试"
                }
            }

            views.setTextViewText(R.id.tv_date, pillText)
            views.setInt(R.id.tv_date, "setBackgroundResource", pillBgRes)
            views.setTextColor(R.id.tv_date, context.getColor(pillTextColorRes))
            if (infoText.isNotEmpty()) {
                views.setTextViewText(R.id.tv_phase, infoText)
                views.setTextColor(R.id.tv_phase, context.getColor(R.color.w_text_soft))
                views.setViewVisibility(R.id.tv_phase, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_phase, View.GONE)
            }
            views.setTextViewText(R.id.tv_pre, preText)
            views.setTextViewText(R.id.tv_days, dayText)
            views.setTextColor(R.id.tv_days, context.getColor(dayColorRes))
            if (statusText.isNotEmpty()) {
                views.setTextViewText(R.id.tv_status, statusText)
                views.setTextColor(R.id.tv_status, context.getColor(android.R.color.white))
                views.setInt(R.id.tv_status, "setBackgroundResource", R.drawable.pill_red)
                views.setViewVisibility(R.id.tv_status, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_status, View.GONE)
            }
            // 环形点阵单独兜底：即使绘制失败也不影响文字更新
            try {
                views.setImageViewBitmap(R.id.iv_ring, buildRingBitmap(ringCycle, ringDay, pillText, ringPeriodDays, progDay, progTotal))
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
            views.setViewVisibility(R.id.tv_phase, View.GONE)
            views.setTextViewText(R.id.tv_pre, "")
            views.setTextViewText(R.id.tv_days, "—")
            views.setTextColor(R.id.tv_days, context.getColor(R.color.w_text_main))
            views.setViewVisibility(R.id.tv_status, View.GONE)
            manager.updateAppWidget(ComponentName(context, MenstrualWidget::class.java), views)
        }

        /**
         * 环形点阵 Bitmap（v91 配色统一）：
         * - 点数 = 预计周期天数（加权平均，18~45）
         * - 颜色与网页身体日历统一为莫兰迪配色：月经期红 → 卵泡期黄 → 排卵期橙（排卵日当天深橙）→ 黄体期绿
         *   （月经期天数取最近一次记录的实际经期天数，排卵窗口 = 排卵日±2，与顶部时期药丸同一套判定）
         * - 今天的位置放大高亮
         * - 圆环整体缩小（半径 0.40），点不再碰到边际
         * - v113：progDay/progTotal > 0 时切换为「经期进度图」——圆点数 = 当前预测经期天数，
         *   从 12 点方向顺时针均匀分布、全部红色，每过一天走到下一个点；经期结束后恢复正常点阵
         * 256px（≈256KB，远小于 Binder 1MB 限制）
         */
        private fun buildRingBitmap(cycleLen: Int, today: Int, phase: String, periodDays: Int, progDay: Int = 0, progTotal: Int = 0): Bitmap {
            val size = 256
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val radius = size * 0.40f
            val mensColor = 0xFFF06A92.toInt()     // 月经期 亮粉
            val todayMensColor = 0xFFD14D78.toInt() // 经期进度图·今天 深粉

            // v113：经期进度图模式
            if (progTotal >= 1 && progDay >= 1) {
                val m = progTotal.coerceIn(1, 14)
                val pr = size * (if (m > 8) 0.034f else 0.048f)
                val hi = progDay.coerceIn(1, m)
                for (d in 1..m) {
                    val ang = Math.toRadians(-90.0 + 360.0 * (d - 1) / m)
                    val x = cx + radius * cos(ang).toFloat()
                    val y = cy + radius * sin(ang).toFloat()
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    if (d == hi) {
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = pr * 0.8f
                        p.color = 0x2E000000.toInt()
                        canvas.drawCircle(x, y, pr * 1.9f, p)
                        p.style = Paint.Style.FILL
                        p.color = todayMensColor
                        canvas.drawCircle(x, y, pr * 1.5f, p)
                    } else {
                        p.color = mensColor
                        canvas.drawCircle(x, y, pr, p)
                    }
                }
                return bmp
            }

            val n = cycleLen.coerceIn(18, 45)
            val baseR = size * (if (n > 34) 0.017f else 0.021f)
            val pDays = periodDays.coerceIn(1, 14)
            val ovuStart = n - 14

            // v97：圆点恢复明亮配色——v93 的浅马卡龙（奶黄/开心果）在小组件浅色背景上几乎
            // 看不见（用户反馈"圆点全消失"）。日历格保持浅马卡龙，圆点单独用明亮马卡龙
            // （mensColor 已在函数开头声明，v113 经期进度图共用）
            val follicleColor = 0xFFF9D770.toInt() // 卵泡期 柠檬黄（v114：与网页日历统一 #F9D770）
            val ovuColor = 0xFFE8944A.toInt()      // 排卵期 亮橙
            val ovuDayColor = 0xFFD9712E.toInt()   // 排卵日 深亮橙
            val lutealColor = 0xFF7FB069.toInt()   // 黄体期 草绿
            // 今天高亮用深一档
            val todayColor = when (phase) {
                "月经期" -> 0xFFD14D78.toInt()
                "排卵日" -> 0xFFB85A1E.toInt()
                "排卵期" -> 0xFFD97B2E.toInt()
                "卵泡期" -> 0xFFB8892B.toInt()
                "黄体期" -> 0xFF5E9450.toInt()
                else -> 0xFF9C948B.toInt()
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
                        d <= pDays -> mensColor
                        d == ovuStart -> ovuDayColor
                        d in (ovuStart - 2)..(ovuStart + 2) -> ovuColor
                        d < ovuStart - 2 -> follicleColor
                        else -> lutealColor
                    }
                    canvas.drawCircle(x, y, baseR, p)
                }
            }
            return bmp
        }
    }
}
