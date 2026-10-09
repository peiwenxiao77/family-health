package com.familyhealth.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        web.webViewClient = WebViewClient()
        // v67：必须设置 WebChromeClient 并实现 onJsAlert/onJsConfirm，
        // 否则 WebView 默认压制 JS 原生弹窗（confirm 静默返回 false），
        // 网页里的删除确认等在 APP 内全部无反应（v66 用户反馈的 bug 根因）
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.loadUrl("https://peiwenxiao77.github.io/family-health/")
        // 打开 APP 时顺手刷新一次小组件
        WidgetWorker.scheduleNow(this)
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    inner class Bridge {
        @JavascriptInterface
        fun saveConfig(url: String, key: String) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("url", url)
                .putString("key", key)
                .apply()
            WidgetWorker.scheduleNow(this@MainActivity)
        }
    }
}
