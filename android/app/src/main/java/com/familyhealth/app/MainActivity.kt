package com.familyhealth.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
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
