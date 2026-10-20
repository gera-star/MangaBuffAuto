package com.example.myapplication.automation

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.os.Build
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonParser
import com.example.myapplication.data.LogEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

@SuppressLint("RestrictedApi")
class ProfileWebViewStore(
    private val context: Context,
    private val onLog: (LogEntry) -> Unit = {},
    private val onRendererGone: (accountId: String, webView: WebView) -> Unit = { _, _ -> }
) {
    private val activeWebViews = mutableMapOf<String, WebView>()
    private val attachDeferreds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val webViewRunIds = mutableMapOf<String, String>()

    /**
     * A WebView used by the foreground service may intentionally have no parent:
     * MainActivity only mounts one account's WebView for optional debugging.
     * Keep the detached WebView measured so Chromium does not fall back to a
     * 0x0/one-pixel layout viewport while JavaScript continues in the background.
     *
     * Must be called on the main thread, just like all WebView view operations.
     */
    private fun ensureHeadlessViewport(
        accountId: String,
        webView: WebView,
        reason: String
    ) {
        if (webView.isAttachedToWindow) return

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)

        try {
            webView.layoutParams = ViewGroup.LayoutParams(width, height)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, width, height)

            onLog(
                LogEntry(
                    username = accountId,
                    component = "WEBVIEW",
                    message = "HEADLESS_VIEWPORT_READY reason=$reason " +
                        "size=${webView.width}x${webView.height} " +
                        "measured=${webView.measuredWidth}x${webView.measuredHeight} " +
                        "attached=${webView.isAttachedToWindow}"
                )
            )
        } catch (e: Exception) {
            onLog(
                LogEntry(
                    username = accountId,
                    component = "WEBVIEW",
                    message = "HEADLESS_VIEWPORT_ERROR reason=$reason error=${e.message}",
                    isError = true
                )
            )
        }
    }

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

        ensureHeadlessViewport(accountId, webView, "INITIALIZE")

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
                    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNetwork = manager.activeNetwork
                    val capabilities = activeNetwork?.let(manager::getNetworkCapabilities)
                    val internetValidated =
                        capabilities != null &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val errorCode = error?.errorCode
                    val category = when {
                        !internetValidated && activeNetwork == null -> "NETWORK_UNAVAILABLE"
                        !internetValidated -> "NETWORK_INTERNET_UNVALIDATED"
                        errorCode == WebViewClient.ERROR_HOST_LOOKUP -> "SITE_OR_DNS_UNAVAILABLE"
                        errorCode == WebViewClient.ERROR_TIMEOUT -> "SITE_REQUEST_TIMEOUT"
                        errorCode == WebViewClient.ERROR_CONNECT -> "SITE_CONNECTION_FAILED"
                        else -> "SITE_LOAD_ERROR"
                    }
                    onLog(
                        LogEntry(
                            username = accountId,
                            component = "WEBVIEW",
                            message = "LOAD_ERROR category=$category errorCode=$errorCode url=${request.url} " +
                                "description=${error?.description}",
                            isError = true
                        )
                    )
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
                if (view != null) onRendererGone(accountId, view)

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
            override fun onViewDetachedFromWindow(v: View) {
                // Activity/Compose can detach the live WebView when the screen locks
                // or when another account is selected in the debug viewer.
                // Re-measure immediately so background DOM scrolling keeps a viewport.
                ensureHeadlessViewport(accountId, webView, "DETACHED")
            }
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
                val cookieItems = parseCookieHeader(cookiesJson)
                for (item in cookieItems) {
                    val trimmed = item.trim()
                    val separator = trimmed.indexOf('=')
                    // A request Cookie header contains name=value pairs separated by semicolons.
                    // Do not split commas: commas can be part of cookie values.
                    if (separator > 0) {
                        targetCookieManager.setCookie("https://mangabuff.ru", trimmed)
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

    private fun parseCookieHeader(raw: String): List<String> {
        val header = raw.trim()
        val text = if (header.startsWith("Cookie:", ignoreCase = true)) {
            header.substringAfter(':').trim()
        } else {
            header
        }
        if (text.isEmpty()) return emptyList()

        // Older versions sometimes stored the browser cookies as JSON. Accept that format
        // without exposing values to logs; otherwise treat the field as an HTTP Cookie header.
        if (text.startsWith("{") || text.startsWith("[")) {
            runCatching {
                val json = JsonParser.parseString(text)
                when {
                    json.isJsonObject -> json.asJsonObject.entrySet().mapNotNull { (name, value) ->
                        if (name.isBlank() || value.isJsonNull || value.isJsonObject || value.isJsonArray) {
                            null
                        } else {
                            "$name=" + if (value.isJsonPrimitive) value.asJsonPrimitive.asString else value.toString()
                        }
                    }
                    json.isJsonArray -> json.asJsonArray.mapNotNull { value ->
                        when {
                            value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                            value.isJsonObject -> {
                                val item = value.asJsonObject
                                val name = item.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                                val cookieValue = item.get("value")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                                if (name.isNotBlank() && cookieValue != null) "$name=$cookieValue" else null
                            }
                            else -> null
                        }
                    }
                    else -> emptyList()
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        return text.split(';').map { it.trim() }.filter { it.isNotEmpty() && it.indexOf('=') > 0 }
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
            // Flush the account's own persistent WebView profile before destroying its view.
            // This preserves current login cookies and never clears the profile.
            runCatching {
                ProfileStore.getInstance().getProfile("mb_$accountId")?.cookieManager?.flush()
            }.onFailure { error ->
                onLog(
                    LogEntry(
                        username = accountId,
                        component = "PROFILE",
                        message = "COOKIE_FLUSH_FAILED reason=${error.message}",
                        isError = true
                    )
                )
            }

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
