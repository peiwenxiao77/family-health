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
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    private const val APK_URL =
        "https://peiwenxiao77.github.io/family-health/releases/latest.apk"
    private const val KEY_NOTIFIED = "update_notified_code"
    private const val CHANNEL_UPDATE = "app_update"

    /** APP 启动时检查（主线程调用，网络在后台线程） */
    fun checkAsync(activity: Activity, fromUser: Boolean) {
        Thread {
            val info = fetchRemoteVersion()
            Handler(Looper.getMainLooper()).post {
                if (!activity.isFinishing) {
                    if (info != null && info.optInt("version_code", 0) > BuildConfig.VERSION_CODE) {
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
            if (code <= BuildConfig.VERSION_CODE) return
            val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_NOTIFIED, 0) >= code) return
            prefs.edit().putInt(KEY_NOTIFIED, code).apply()
            notifyNewVersion(context, info)
        } catch (_: Exception) {}
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

    private fun downloadAndInstall(activity: Activity, info: JSONObject) {
        try {
            uiToast(activity, "正在下载更新…")
            val dir = File(activity.filesDir, "updates").apply { mkdirs() }
            // 先清掉旧更新包，避免堆积
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "update_v${info.optString("version_name", "new")}.apk")
            val conn = URL(APK_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            install(activity, file)
        } catch (_: Exception) {
            uiToast(activity, "下载失败，请检查网络后重试")
        }
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
