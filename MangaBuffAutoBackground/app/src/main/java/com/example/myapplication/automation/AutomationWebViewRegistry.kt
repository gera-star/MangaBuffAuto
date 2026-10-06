package com.example.myapplication.automation

import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local bridge from the background automation service to the visible Activity.
 *
 * The registry is only for attaching the already-running account WebView to the UI.
 * Automation ownership and lifecycle remain inside AutomationRuntime/ProfileWebViewStore.
 */
object AutomationWebViewRegistry {
    private val _webViewsByAccount =
        MutableStateFlow<Map<String, WebView>>(emptyMap())

    val webViewsByAccount: StateFlow<Map<String, WebView>> =
        _webViewsByAccount.asStateFlow()

    fun assigned(accountId: String, webView: WebView) {
        _webViewsByAccount.value = _webViewsByAccount.value.toMutableMap().apply {
            put(accountId, webView)
        }
    }

    fun cleared(accountId: String, webView: WebView? = null) {
        val current = _webViewsByAccount.value[accountId]
        if (webView == null || current === webView) {
            _webViewsByAccount.value = _webViewsByAccount.value.toMutableMap().apply {
                remove(accountId)
            }
        }
    }
}
