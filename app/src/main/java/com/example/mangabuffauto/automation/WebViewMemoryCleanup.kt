package com.example.mangabuffauto.automation

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.example.mangabuffauto.AndroidBotLog

object WebViewMemoryCleanup {
    private const val CLEANUP_INTERVAL_MS = 30L * 60L * 1000L
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var provider: (() -> WebView?)? = null
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            cleanupNow("scheduled")
            handler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }
    fun start(webViewProvider: () -> WebView?) {
        stop()
        provider = webViewProvider
        running = true
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
        AndroidBotLog.log("MEMORY: automatic WebView cleanup enabled interval=30m")
    }
    fun stop() {
        running = false
        handler.removeCallbacks(cleanupRunnable)
        provider = null
    }
    fun cleanupNow(reason: String = "manual") {
        val view = provider?.invoke() ?: return
        try {
            view.clearCache(false)
            view.clearMatches()
            AndroidBotLog.log("MEMORY: WebView soft cleanup reason=$reason")
        } catch (e: Exception) {
            AndroidBotLog.log("MEMORY: cleanup failed reason=$reason")
        }
    }
}
