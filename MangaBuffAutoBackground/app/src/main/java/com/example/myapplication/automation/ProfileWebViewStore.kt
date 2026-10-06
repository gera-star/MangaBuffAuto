package com.example.myapplication.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.graphics.Color
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.myapplication.data.LogEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

@SuppressLint("RestrictedApi")
class ProfileWebViewStore(
    private val context: Context,
    private val onLog: (LogEntry) -> Unit = {},
    private val onRendererGone: (accountId: String) -> Unit = {}
) {
    private val activeWebViews = mutableMapOf<String, WebView>()
    private val attachDeferreds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val webViewRunIds = mutableMapOf<String, String>()

    @Synchronized
    fun getOrCreateWebView(accountId: String, cookiesJson: String = ""): Pair<String, WebView> {
        val profileName = "mb_$accountId"
        onLog(LogEntry(username = accountId, component = "PROFILE", message = "selected profile: $profileName"))
        println("PROFILE: CREATE accountId=$accountId profile=$profileName")

        val isMultiProfileSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        onLog(LogEntry(username = accountId, component = "PROFILE", message = "MULTI_PROFILE_SUPPORTED=$isMultiProfileSupported"))
        println("PROFILE: MULTI_PROFILE_SUPPORTED=$isMultiProfileSupported")

        if (!isMultiProfileSupported) {
            onLog(LogEntry(username = accountId, component = "PROFILE", message = "MULTI_PROFILE_UNSUPPORTED", isError = true))
            throw IllegalStateException("PROFILE: MULTI_PROFILE_UNSUPPORTED accountId=$accountId")
        }

        val existing = activeWebViews[accountId]
        val runId = webViewRunIds.getOrPut(accountId) { UUID.randomUUID().toString() }

        if (existing != null) {
            onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "GET existing instance=${existing.hashCode()} runId=$runId"))
            syncCookiesForAccount(accountId, profileName, cookiesJson)
            return Pair(profileName, existing)
        }

        onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "CREATED profile=$profileName"))

        val webView = WebView(context)
        
        try {
            WebViewCompat.setProfile(webView, profileName)
            onLog(LogEntry(username = accountId, component = "PROFILE", message = "PROFILE BOUND profile=$profileName"))
        } catch (e: Exception) {
            onLog(LogEntry(username = accountId, component = "PROFILE", message = "SET_PROFILE_FAIL error=${e.message}", isError = true))
            throw IllegalStateException("PROFILE: SET_PROFILE_FAIL accountId=$accountId profile=$profileName error=${e.message}")
        }

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT,
                    false
                )
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "PAGE_STARTED url=$url"))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "PAGE_FINISHED url=$url"))
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "LOAD_ERROR url=${request.url} error=${error?.description}", isError = true))
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                onLog(
                    LogEntry(
                        username = accountId,
                        component = "WEBVIEW",
                        message = "RENDERER_GONE didCrash=${detail?.didCrash()} priority=IMPORTANT",
                        isError = true
                    )
                )

                synchronized(this@ProfileWebViewStore) {
                    if (view != null && activeWebViews[accountId] === view) {
                        activeWebViews.remove(accountId)
                        attachDeferreds.remove(accountId)
                        webViewRunIds.remove(accountId)
                    }
                }

                try {
                    view?.destroy()
                } catch (_: Exception) {
                }

                onLog(
                    LogEntry(
                        username = accountId,
                        component = "WEBVIEW",
                        message = "RENDERER_GONE_INVALIDATED create_new_instance_next_run=true",
                        isError = true
                    )
                )
                onRendererGone(accountId)

                return true
            }
        }

        syncCookiesForAccount(accountId, profileName, cookiesJson)

        val deferred = CompletableDeferred<Unit>()
        attachDeferreds[accountId] = deferred

        webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "ATTACHED instance=${webView.hashCode()} runId=$runId"))
                if (!deferred.isCompleted) {
                    deferred.complete(Unit)
                }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        activeWebViews[accountId] = webView
        onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "CREATE instance=${webView.hashCode()} runId=$runId"))

        return Pair(profileName, webView)
    }

    fun syncCookiesForAccount(accountId: String, profileName: String, cookiesJson: String) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                onLog(LogEntry(username = accountId, component = "PROFILE", message = "COOKIE_PROFILE_FAIL MULTI_PROFILE_UNSUPPORTED", isError = true))
                throw IllegalStateException("PROFILE: COOKIE_PROFILE_FAIL accountId=$accountId error=MULTI_PROFILE_UNSUPPORTED")
            }

            val profileStore = ProfileStore.getInstance()
            val profile = profileStore.getProfile(profileName)
            val targetCookieManager: CookieManager = profile?.cookieManager ?: run {
                onLog(LogEntry(username = accountId, component = "PROFILE", message = "COOKIE_PROFILE_FAIL Profile_not_found", isError = true))
                throw IllegalStateException("PROFILE: COOKIE_PROFILE_FAIL accountId=$accountId error=Profile_not_found")
            }

            onLog(LogEntry(username = accountId, component = "PROFILE", message = "COOKIE_MANAGER profile=$profileName"))

            targetCookieManager.setAcceptCookie(true)
            var syncedCookieCount = 0
            if (cookiesJson.isNotBlank()) {
                val cookieItems = cookiesJson.split(";", ",")
                for (item in cookieItems) {
                    if (item.contains("=")) {
                        targetCookieManager.setCookie("https://mangabuff.ru", item.trim())
                        syncedCookieCount++
                    }
                }
                targetCookieManager.flush()
            }
            onLog(
                LogEntry(
                    username = accountId,
                    component = "PROFILE",
                    message = "COOKIES_SYNC_SUCCESS profile=$profileName count=$syncedCookieCount"
                )
            )
        } catch (e: Exception) {
            onLog(LogEntry(username = accountId, component = "PROFILE", message = "COOKIE_PROFILE_FAIL error=${e.message}", isError = true))
            throw e
        }
    }

    suspend fun awaitAttached(accountId: String, webView: WebView) {
        val deferred = attachDeferreds[accountId] ?: CompletableDeferred(Unit).also { it.complete(Unit) }
        onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "WAIT_ATTACH instance=${webView.hashCode()}"))

        if (webView.isAttachedToWindow) {
            onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "ALREADY_ATTACHED instance=${webView.hashCode()}"))
            return
        }

        try {
            withTimeout(10_000L) {
                deferred.await()
            }
            onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "WAIT_ATTACH_DONE"))
        } catch (e: Exception) {
            onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "ATTACH_TIMEOUT profile=mb_$accountId", isError = true))
            throw IllegalStateException("WEBVIEW: ATTACH_TIMEOUT accountId=$accountId")
        }
    }

    @Synchronized
    fun releaseWebView(accountId: String) {
        val webView = activeWebViews.remove(accountId)
        attachDeferreds.remove(accountId)
        webViewRunIds.remove(accountId)
        if (webView != null) {
            try {
                onLog(LogEntry(username = accountId, component = "WEBVIEW", message = "DESTROYED profile=mb_$accountId"))
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    @Synchronized
    fun releaseAll() {
        val keys = activeWebViews.keys.toList()
        for (key in keys) {
            releaseWebView(key)
        }
    }
}
