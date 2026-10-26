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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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

        // ===== v131：离线缓存——断网时用上次成功拉取的经期列表在本地重算 =====
        private const val CACHE_KEY = "widget_cache_merged"

        /** v111：合并连续/重叠记录（10.1、10.2、10.3-10.5 → 一次 5 天经期） */
        private fun mergePeriods(raw: List<Pair<Long, Int>>): ArrayList<Pair<Long, Int>> {
            val merged = ArrayList<Pair<Long, Int>>()
            raw.sortedBy { it.first }.forEach { (rawTs, d) ->
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
            return merged
        }

        private fun saveMergedCache(prefs: android.content.SharedPreferences, merged: List<Pair<Long, Int>>) {
            try {
                val arr = JSONArray()
                merged.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
                prefs.edit().putString(CACHE_KEY, arr.toString())
                    .putLong("widget_cache_time", System.currentTimeMillis()).apply()
            } catch (_: Exception) {}
        }

        /** null = 从未成功联网过；空列表 = 云端确认当前无月经记录 */
        private fun loadMergedCache(prefs: android.content.SharedPreferences): ArrayList<Pair<Long, Int>>? {
            return try {
                val s = prefs.getString(CACHE_KEY, null) ?: return null
                val arr = JSONArray(s)
                val out = ArrayList<Pair<Long, Int>>()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    out.add(Pair(a.getLong(0), a.getInt(1)))
                }
                out
            } catch (_: Exception) { null }
        }

        /** v131：失败后 15 分钟自动重试一次（要求有网络才触发），恢复联网后缓存即自动更新 */
        fun scheduleRetry(ctx: Context) {
            try {
                val req = OneTimeWorkRequestBuilder<WidgetWorker>()
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(ctx).enqueueUniqueWork("menstrual_retry", ExistingWorkPolicy.KEEP, req)
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
            var ringOvuStart = 15  // v143：环形点阵排卵日位置（周期第几天），与日历一致
            // v113：经期进度图（phase=月经期时启用）——圆点数=预测经期天数，从12点顺时针均布全红，每天走到下一个点
            var progDay = 0   // 经期内第几天（1 起）；0 = 用正常周期点阵
            var progTotal = 0 // 预测经期天数

            if (url.isNotBlank() && key.isNotBlank()) {
                // v131：由合并经期列表推导全部界面状态——联网成功与离线缓存两条路共用。
                // 全部数值用「当前时刻」现算（dayIn 基于今天 0 点），断网时预测随日期自动推进；
                // 月经期小红点进度图（progDay/progTotal）同样每天前移，与在线渲染完全一致
                fun applyPeriods(merged: List<Pair<Long, Int>>) {
                    // 与网页同步——剔除 15~45 天外离群周期 + 最近 6 个有效周期加权平均(6:5:4:3:2:1)
                    val allGaps = merged.zipWithNext { a, b -> ((b.first - a.first) / DAY).toInt() }
                    val validGaps = allGaps.filter { it in 15..45 }
                    // v143：均值保留小数（网页 computeAvgCycle 用 24.5 这类小数推日期）——
                    // 此前四舍五入取整，出现 .5 时下次月经/排卵日/倒计时整体漂移一天（2026-10-26 实证）
                    val avgFrac = when {
                        validGaps.isEmpty() -> 28.0
                        validGaps.size >= 3 -> {
                            val recent = validGaps.takeLast(6)
                            var ws = 0.0; var vs = 0.0
                            recent.forEachIndexed { i, v ->
                                val w = (i + 1 + (6 - recent.size)).toDouble()
                                ws += w; vs += v * w
                            }
                            vs / ws
                        }
                        else -> validGaps.sum().toDouble() / validGaps.size
                    }.coerceIn(15.0, 45.0)
                    val last0 = merged.last().first
                    val lastDur = merged.last().second
                    val today0 = startOfDay(System.currentTimeMillis())
                    val dayIn = ((today0 - last0) / DAY).toInt() + 1
                    /* v112 预测规则（与网页同步）：
                       预存预测经期天数 = 历史经期天数加权平均（最近6次，权重同周期算法，回退5天）；
                       最新一次未记完的经期不计入 → 待定窗长用「不含本轮」的历史均值（predPrev）；
                       待定结束后：一律给出完整四期预测（v129 与网页 v114 对齐）；
                       v144：预测窗日期到达仍无新记录 → 视为该周期按预测结束，锚点前移一个周期，
                       预测无限滚动（无「已推迟/真空」态）；虚拟周期只在计算里，真实记录一票否决 */
                    fun avgDur(list: List<Int>): Int = when {
                        list.isEmpty() -> 5
                        list.size >= 3 -> {
                            var ws = 0.0; var vs = 0.0
                            list.forEachIndexed { i, v ->
                                val w = (i + 1 + (6 - list.size)).toDouble()
                                ws += w; vs += v * w
                            }
                            // v126：四舍五入对齐网页 Math.round（原 toInt() 截断导致红点数偏少）
                            Math.round(vs / ws).toInt().coerceIn(1, 14)
                        }
                        else -> Math.round(list.sum().toDouble() / list.size).toInt().coerceIn(1, 14)
                    }
                    val predPrev = avgDur(merged.dropLast(1).map { it.second })  // 待定窗天数（不含本轮）
                    // v143：对齐网页 v114——上次实际经期 <5 天时，下次预测窗按实际天数（原一律用加权均值）
                    val PRED = if (lastDur < 5) lastDur else avgDur(merged.map { it.second })
                    // 待定窗：真实记录未判定结束（今天落在 [起点, max(真实结束, 起点+predPrev-1)] 区间）
                    val pendEnd = maxOf(last0 + (lastDur - 1) * DAY, last0 + (predPrev - 1) * DAY)
                    val inPending = today0 >= last0 && today0 <= pendEnd
                    // v144：滚动锚点——下一个预测经期日期到达即前移一个周期（日期级比较，与网页同款）
                    var anchor = last0
                    var rolls = 0
                    while (startOfDay(anchor + (avgFrac * DAY).toLong()) <= today0) {
                        anchor += (avgFrac * DAY).toLong()
                        rolls++
                    }
                    val next0 = anchor + (avgFrac * DAY).toLong()
                    val daysLeft = Math.floor((next0 - today0) / DAY.toDouble()).toInt()
                    val anchorDay0 = startOfDay(anchor)
                    // 本周期经期窗结束（日期级）：第一圈=真实经期结束；假定圈=锚点日起 PRED 天
                    val periodEnd = if (rolls == 0) last0 + (lastDur - 1) * DAY else anchorDay0 + (PRED - 1) * DAY
                    val ovuDay = startOfDay(next0 - 14 * DAY)   // v116：排卵日 = 下次月经前 14 天
                    val phase = when {
                        inPending -> "月经期"                            // 真实记录未判定结束
                        today0 >= anchorDay0 && today0 <= periodEnd -> "月经期"   // 预测窗内（假定经期中）
                        today0 == ovuDay -> "排卵日"
                        today0 >= ovuDay - 5 * DAY && today0 < ovuDay -> "排卵期"   // 排卵窗=排卵日前5天到前1天
                        today0 < ovuDay - 5 * DAY -> "卵泡期"
                        else -> "黄体期"                                  // 排卵日后到下次经期前
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
                    val cycleDay = ((today0 - anchorDay0) / DAY).toInt() + 1   // 周期内第几天（滚动锚点起）
                    ringCycle = Math.round(avgFrac).toInt().coerceIn(18, 45)
                    ringOvuStart = (((ovuDay - anchorDay0) / DAY).toInt() + 1).coerceIn(1, ringCycle)
                    ringDay = cycleDay
                    ringPeriodDays = if (rolls == 0) lastDur else PRED
                    if (phase == "月经期") {
                        // v113：月经期/预测月经期中 → 天数位置显示「/」，点阵切为经期进度图
                        preText = "距下次月经还有"
                        dayText = "/"
                        progTotal = if (inPending) predPrev else PRED
                        // 红点位置：待定窗=真实经期第几天；假定窗=周期内第几天（锚点日起）
                        progDay = if (inPending) dayIn else cycleDay
                    } else {
                        // v144：滚动预测后倒计时永远为正，「已推迟」态不复存在
                        preText = "距下次月经还有"
                        dayText = abs(daysLeft).toString()
                    }
                    // 底部药丸：滚动后 daysLeft 恒 ≥1，「该记录啦」不再触发；经期将至保留
                    if (daysLeft <= 2) statusText = "经期将至"
                }

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
                        saveMergedCache(prefs, emptyList())  // v131：同步清空缓存，离线时同样显示「暂无记录」
                    } else {
                        val latest = byMember.values.maxByOrNull { it.maxOfOrNull { p -> p.first } ?: 0L }!!
                        // v111：合并连续/重叠记录（10.1、10.2、10.3-10.5 → 一次 5 天经期）
                        val merged = mergePeriods(latest)
                        applyPeriods(merged)
                        // v131：缓存合并后的经期列表（起点=当天0点 + 天数），断网时离线重算
                        saveMergedCache(prefs, merged)
                    }
                } catch (e: Exception) {
                    // v131：断网/超时 → 用缓存的经期列表在本地完整重算（预测随日期自动推进），
                    // 只有从未成功联网过才显示「网络错误」；失败后 15 分钟自动重试（有网才触发）
                    val cached = loadMergedCache(prefs)
                    when {
                        cached == null -> { pillText = "网络错误"; infoText = "重新打开APP即可重试" }
                        cached.isEmpty() -> { pillText = "暂无记录"; infoText = "在APP里记录一次月经" }
                        else -> applyPeriods(cached)
                    }
                    scheduleRetry(context)
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
                views.setImageViewBitmap(R.id.iv_ring, buildRingBitmap(ringCycle, ringDay, pillText, ringPeriodDays, progDay, progTotal, ringOvuStart))
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
         *   （v116 分区与日历完全一致：月经期取实际经期天数 → 卵泡期 → 排卵窗=排卵日前5天到前1天 → 排卵日 → 黄体期；
         *    排卵日 = 下次月经前14天 = 周期第 n-13 天）
         * - 今天的位置放大高亮
         * - 圆环整体缩小（半径 0.40），点不再碰到边际
         * - v113：progDay/progTotal > 0 时切换为「经期进度图」——圆点数 = 当前预测经期天数，
         *   从 12 点方向顺时针均匀分布、全部红色，每过一天走到下一个点；经期结束后恢复正常点阵
         * 256px（≈256KB，远小于 Binder 1MB 限制）
         */
        private fun buildRingBitmap(cycleLen: Int, today: Int, phase: String, periodDays: Int, progDay: Int = 0, progTotal: Int = 0, ovuStartIn: Int = 0): Bitmap {
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
            // v143：排卵日位置改为上游传入（predStartDayIn-14），与网页日历逐日一致
            //（原硬编码 n-13，均值取整漂移时点阵跟着错位）
            val ovuStart = if (ovuStartIn in 1..n) ovuStartIn else n - 13

            // v97：圆点恢复明亮配色——v93 的浅马卡龙（奶黄/开心果）在小组件浅色背景上几乎
            // 看不见（用户反馈"圆点全消失"）。日历格保持浅马卡龙，圆点单独用明亮马卡龙
            // （mensColor 已在函数开头声明，v113 经期进度图共用）
            val follicleColor = 0xFFF9D770.toInt() // 卵泡期 柠檬黄（v114：与网页日历统一 #F9D770）
            val ovuColor = 0xFFE8944A.toInt()      // 排卵期 亮橙
            val ovuDayColor = 0xFFD9712E.toInt()   // 排卵日 深亮橙
            val lutealColor = 0xFF7FB069.toInt()   // 黄体期 草绿
            // 今天高亮用深一档（v129：phase 上游已穷尽五态，无未知兜底——else 语义上=黄体期）
            val todayColor = when (phase) {
                "月经期" -> 0xFFD14D78.toInt()
                "排卵日" -> 0xFFB85A1E.toInt()
                "排卵期" -> 0xFFD97B2E.toInt()
                "卵泡期" -> 0xFFB8892B.toInt()
                else -> 0xFF5E9450.toInt()   // 黄体期
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
                        d in (ovuStart - 5)..(ovuStart - 1) -> ovuColor
                        d < ovuStart - 5 -> follicleColor
                        else -> lutealColor
                    }
                    canvas.drawCircle(x, y, baseR, p)
                }
            }
            return bmp
        }
    }
}
