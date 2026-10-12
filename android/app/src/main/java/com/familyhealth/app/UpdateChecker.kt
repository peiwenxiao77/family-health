package com.familyhealth.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v107：APP 更新推送
 * 发布链路：每次 APK 构建成功后，GitHub Actions 自动把 latest.apk + version.json
 * 提交到仓库 releases/ 目录（GitHub Pages 同步发布）——github.io 是 APP 网页的加载域，
 * 用户手机上实测可达（Supabase/GitHub Release 在国内直连都不稳，故不用）。
 * 检查时机：①APP 启动（弹窗，主通道）②WidgetWorker 每 3 小时（系统通知，同版本只提醒一次）。
 * 版本判定：远端 version_code > 本地 BuildConfig.VERSION_CODE。
 */
object UpdateChecker {

    private const val VERSION_URL =
        "https://peiwenxiao77.github.io/family-health/releases/version.json"
    // 下载源：主源 github.io（与网页同域）；备用源 jsDelivr CDN（国内通常可达）。
    // 注意 jsDelivr 对分支内容可能有数小时缓存，仅在主源完全不可达时兜底
    private val APK_SOURCES = listOf(
        "https://peiwenxiao77.github.io/family-health/releases/latest.apk",
        "https://fastly.jsdelivr.net/gh/peiwenxiao77/family-health@main/releases/latest.apk"
    )
    private const val KEY_NOTIFIED = "update_notified_code"
    private const val CHANNEL_UPDATE = "app_update"

    /** APP 启动时检查（主线程调用，网络在后台线程） */
    fun checkAsync(activity: Activity, fromUser: Boolean) {
        val localCode = localVersionCode(activity)
        Thread {
            val info = fetchRemoteVersion()
            Handler(Looper.getMainLooper()).post {
                if (!activity.isFinishing) {
                    if (info != null && info.optInt("version_code", 0) > localCode) {
                        showUpdateDialog(activity, info)
                    } else if (fromUser) {
                        Toast.makeText(activity, "已是最新版本 ✓", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    /** 后台定期检查（WidgetWorker）：发现新版发系统通知，同一版本只提醒一次 */
    fun checkAndNotify(context: Context) {
        try {
            val info = fetchRemoteVersion() ?: return
            val code = info.optInt("version_code", 0)
            if (code <= localVersionCode(context)) return
            val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_NOTIFIED, 0) >= code) return
            prefs.edit().putInt(KEY_NOTIFIED, code).apply()
            notifyNewVersion(context, info)
        } catch (_: Exception) {}
    }

    /** 本地版本号：经 PackageManager 读取（AGP 8 默认不生成 BuildConfig） */
    private fun localVersionCode(context: Context): Int {
        return try {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pm.longVersionCode.toInt() else pm.versionCode
        } catch (_: Exception) {
            0
        }
    }

    private fun fetchRemoteVersion(): JSONObject? {
        return try {
            val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(body)
        } catch (_: Exception) {
            null // 网络不通/尚无版本文件 → 视作无更新
        }
    }

    private fun showUpdateDialog(activity: Activity, info: JSONObject) {
        val name = info.optString("version_name", "")
        val notes = info.optString("notes", "")
        val msg = buildString {
            append("新版本 v").append(name)
            if (notes.isNotBlank()) append("\n\n更新说明：").append(notes)
            append("\n\n点击「立即更新」将自动下载并安装（约 2.5MB）。")
        }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 🎉")
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ ->
                Thread { downloadAndInstall(activity, info) }.start()
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    /**
     * v117：带进度条的多源重试下载
     * 旧版失败原因：①github.io 国内时断时续，2.4MB 大文件易中途断开 ②无重试无备用源
     * ③不检查 HTTP 状态码（新版本发布后 CDN 有 1~3 分钟缓存延迟，version.json 先更新
     * 而 latest.apk 仍 404，此时点击必失败）。现在：进度弹窗 + 每源重试 3 次 +
     * 状态码检查 + 文件大小完整性校验 + jsDelivr 备用源兜底
     */
    private fun downloadAndInstall(activity: Activity, info: JSONObject) {
        val dir = File(activity.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update_v${info.optString("version_name", "new")}.apk")
        val cancelled = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())

        // 进度弹窗（主线程创建）
        var dialog: AlertDialog? = null
        var bar: ProgressBar? = null
        var tvStatus: TextView? = null
        main.post {
            if (activity.isFinishing) return@post
            val density = activity.resources.displayMetrics.density
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val p = (20 * density).toInt()
                setPadding(p, (8 * density).toInt(), p, 0)
            }
            val t = TextView(activity).apply { textSize = 13f; text = "正在连接下载源…" }
            val b = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                max = 10000
            }
            box.addView(t)
            box.addView(b)
            dialog = AlertDialog.Builder(activity)
                .setTitle("下载更新中")
                .setView(box)
                .setNegativeButton("取消") { d, _ ->
                    cancelled.set(true)
                    d.dismiss()
                }
                .show()
            tvStatus = t
            bar = b
        }

        fun setUi(status: String?, pct: Int) {
            main.post {
                if (activity.isFinishing) return@post
                tvStatus?.let { if (status != null) it.text = status }
                bar?.let {
                    if (pct >= 0) {
                        it.isIndeterminate = false
                        it.progress = pct
                    }
                }
            }
        }

        Thread {
            var lastErrMsg = "网络连接不稳定，请稍后重试"
            var success = false
            outer@ for (src in APK_SOURCES) {
                for (attempt in 1..3) {
                    if (cancelled.get()) return@Thread
                    try {
                        val conn = URL(src).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 30000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("Cache-Control", "no-cache")
                        val code = conn.responseCode
                        if (code != 200) {
                            conn.disconnect()
                            lastErrMsg = if (code == 404)
                                "安装包还没发布好（404），新版本发布后请等 1~2 分钟再试"
                            else
                                "下载源响应异常（HTTP $code）"
                            break // 404/异常重试同源无意义 → 换备用源
                        }
                        val total = conn.contentLengthLong
                        var done = 0L
                        var lastPct = -1
                        conn.inputStream.use { input ->
                            file.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (cancelled.get()) break
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = ((done * 10000L) / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            setUi(fmtSize(done, total), pct)
                                        }
                                    } else if (done % (512 * 1024) == 0L) {
                                        setUi(fmtSize(done, -1), -1)
                                    }
                                }
                            }
                        }
                        conn.disconnect()
                        if (cancelled.get()) {
                            file.delete()
                            return@Thread
                        }
                        // 完整性校验：正常 APK 约 2.4MB，太小=中途断开或拿到错误页
                        if (file.length() < 500_000L) {
                            lastErrMsg = "下载不完整（${fmtSize(file.length(), -1)}），正在重试…"
                            continue
                        }
                        success = true
                        break@outer
                    } catch (e: Exception) {
                        lastErrMsg = "网络不稳定（${e.javaClass.simpleName}）"
                        try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                    }
                }
            }
            main.post {
                dialog?.dismiss()
                when {
                    cancelled.get() -> {}
                    success -> install(activity, file)
                    else -> uiToast(activity, "下载失败：$lastErrMsg")
                }
            }
        }.start()
    }

    private fun fmtSize(done: Long, total: Long): String {
        val d = String.format(Locale.US, "%.1fMB", done / 1048576.0)
        return if (total > 0) "$d / ${String.format(Locale.US, "%.1fMB", total / 1048576.0)}" else d
    }

    /** 拉起系统安装器；若未授予「安装未知应用」权限则引导去设置页开启 */
    private fun install(activity: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
                uiToast(activity, "请先允许「安装未知应用」，再重新点击更新")
            } catch (_: Exception) {}
        }
    }

    private fun notifyNewVersion(context: Context, info: JSONObject) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_UPDATE, "应用更新", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 1001, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(context, CHANNEL_UPDATE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("龙龙文文小家有新版本 v${info.optString("version_name", "")}")
                .setContentText("点按打开应用即可更新")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            manager.notify(2001, n)
        } catch (_: Exception) {}
    }

    private fun uiToast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
