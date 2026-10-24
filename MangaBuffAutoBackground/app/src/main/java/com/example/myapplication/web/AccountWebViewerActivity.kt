package com.example.myapplication.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.example.myapplication.ContainerApp
import com.example.myapplication.automation.ProfileWebViewFactory
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.network.AppProxyConfig
import com.google.gson.JsonParser

/**
 * Account WEB viewer for an account whose automation WebView lives in a separate worker process.
 * Its own process has an independent proxy override, so the viewer cannot change automation routes.
 */
@SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
class AccountWebViewerActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var pageHost: LinearLayout
    private var webView: WebView? = null
    private var currentProxy = AppProxyConfig()
    private var currentAccountId = ""
    private var currentUsername = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the browser controls below the status bar and camera cutout on edge-to-edge devices.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView
                if (current?.canGoBack() == true) current.goBack() else finish()
            }
        })
        openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tool(label: String, action: () -> Unit) {
            toolbar.addView(
                Button(this).apply { text = label; setOnClickListener { action() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        tool("Назад") { if (webView?.canGoBack() == true) webView?.goBack() else finish() }
        tool("Вперёд") { if (webView?.canGoForward() == true) webView?.goForward() }
        tool("Обновить") { webView?.reload() }
        tool("Закрыть") { finish() }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Подготовка WebView…"
            setPadding(12, 6, 12, 6)
            textSize = 12f
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                systemInsets.left,
                systemInsets.top,
                systemInsets.right,
                systemInsets.bottom
            )
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun openFromIntent(intent: Intent?) {
        currentAccountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        currentUsername = intent?.getStringExtra(EXTRA_USERNAME).orEmpty()
        val url = normalizeWebUrl(intent?.getStringExtra(EXTRA_URL).orEmpty())
        val cookies = intent?.getStringExtra(EXTRA_COOKIES).orEmpty()
        val userAgent = intent?.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        currentProxy = AppProxyConfig(
            enabled = intent?.getBooleanExtra(EXTRA_PROXY_ENABLED, false) ?: false,
            host = intent?.getStringExtra(EXTRA_PROXY_HOST).orEmpty(),
            port = intent?.getIntExtra(EXTRA_PROXY_PORT, 8080) ?: 8080,
            username = intent?.getStringExtra(EXTRA_PROXY_USER).orEmpty(),
            password = intent?.getStringExtra(EXTRA_PROXY_PASS).orEmpty()
        )

        destroyCurrentWebView()
        if (currentAccountId.isBlank() || url == null) {
            showError("Не удалось открыть WEB: неверные параметры аккаунта или адреса.")
            return
        }
        if ((application as? ContainerApp)?.isolatedWebViewProcessReady != true) {
            showError("Не поддерживается отдельный каталог WebView. Страница не загружена.")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showError("WebView не поддерживает отдельные профили. Обновите Android System WebView.")
            return
        }

        val profileName = "mb_$currentAccountId"
        val wv = WebView(this)
        // Track the view before profile binding/settings can fail.
        webView = wv
        val cookieManager: CookieManager = try {
            ProfileWebViewFactory.bindProfileAndApply(
                context = this,
                webView = wv,
                profileName = profileName
            )
        } catch (e: Exception) {
            val destroyed = runCatching {
                wv.stopLoading()
                wv.destroy()
            }.isSuccess
            if (destroyed) {
                if (webView === wv) webView = null
            } else {
                retainFailedWebView(wv)
            }
            showError(
                if (destroyed) {
                    "Не удалось открыть изолированный профиль аккаунта: ${e.message ?: "ошибка WebView"}."
                } else {
                    "Не удалось открыть профиль, а очистка WebView не подтверждена. Перезапусти контейнер."
                }
            )
            return
        }
        if (userAgent.isNotBlank()) wv.settings.userAgentString = userAgent
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                if (scheme != "https") {
                    status.text = "Переход заблокирован: контейнер открывает только HTTPS-адреса."
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                status.text = "${currentUsername.ifBlank { currentAccountId }} • ${url.orEmpty()}"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                status.text = "${currentUsername.ifBlank { currentAccountId }} • ${url.orEmpty()}"
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler,
                host: String?,
                realm: String?
            ) {
                val proxyHost = currentProxy.host.trim()
                if (
                    currentProxy.enabled &&
                    currentProxy.username.isNotBlank() &&
                    (host.equals(proxyHost, ignoreCase = true) ||
                        host.equals("${proxyHost}:${currentProxy.port}", ignoreCase = true))
                ) {
                    handler.proceed(currentProxy.username, currentProxy.password)
                } else {
                    handler.cancel()
                }
            }
        }
        pageHost.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        webView = wv
        status.text = "Подключение • ${currentUsername.ifBlank { currentAccountId }}"
        applyProxyThenLoad(currentProxy, cookieManager, cookies, wv, url)
    }

    private fun applyProxyThenLoad(
        proxy: AppProxyConfig,
        cookieManager: CookieManager,
        cookies: String,
        target: WebView,
        url: String
    ) {
        if (proxy.enabled && !proxy.isValid()) {
            showError("Проверь настройки прокси этого аккаунта.")
            return
        }
        val proxySupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        if (proxy.enabled && !proxySupported) {
            showError("Эта версия WebView не поддерживает прокси. Страница не загружена, чтобы не раскрыть IP.")
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val afterProxy = Runnable {
            if (webView === target) seedCookiesThenLoad(cookieManager, cookies, target, url)
        }
        try {
            if (proxy.enabled) {
                val config = ProxyConfig.Builder()
                    .addProxyRule("${proxy.host.trim()}:${proxy.port}")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, executor, afterProxy)
            } else if (proxySupported) {
                ProxyController.getInstance().clearProxyOverride(executor, afterProxy)
            } else {
                afterProxy.run()
            }
        } catch (e: Exception) {
            showError("Не удалось применить прокси: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    private fun seedCookiesThenLoad(cookieManager: CookieManager, raw: String, target: WebView, url: String) {
        cookieManager.setAcceptCookie(true)
        val cookies = parseCookies(raw)
        if (cookies.isEmpty()) {
            target.loadUrl(url)
            return
        }
        var index = 0
        var accepted = 0
        fun next() {
            if (webView !== target) return
            if (index >= cookies.size) {
                cookieManager.flush()
                if (accepted == 0) {
                    showError("Не удалось установить сессию аккаунта. Проверь вход и попробуй обновить профиль.")
                } else {
                    status.text = "Сессия готова • ${currentUsername.ifBlank { currentAccountId }}"
                    target.loadUrl(url)
                }
                return
            }
            val item = cookies[index++]
            try {
                cookieManager.setCookie("https://mangabuff.ru", item) { success ->
                    if (success) accepted++
                    mainHandler.post { next() }
                }
            } catch (_: Exception) {
                mainHandler.post { next() }
            }
        }
        next()
    }

    private fun parseCookies(raw: String): List<String> {
        var text = raw.trim()
        if (text.startsWith("Cookie:", ignoreCase = true)) text = text.substringAfter(':').trim()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("{") || text.startsWith("[")) {
            val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
            if (parsed != null) {
                val items = when {
                    parsed.isJsonObject -> parsed.asJsonObject.entrySet().mapNotNull { (name, value) ->
                        if (name.isBlank() || value.isJsonNull || !value.isJsonPrimitive) null
                        else "$name=${value.asJsonPrimitive.asString}"
                    }
                    parsed.isJsonArray -> parsed.asJsonArray.mapNotNull { value ->
                        when {
                            value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                            value.isJsonObject -> {
                                val obj = value.asJsonObject
                                val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                                val v = obj.get("value")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                                if (name.isNotBlank() && v != null) "$name=$v" else null
                            }
                            else -> null
                        }
                    }
                    else -> emptyList()
                }
                if (items.isNotEmpty()) return items
            }
        }
        return text.split(';').map { it.trim() }.filter { it.indexOf('=') > 0 }
    }

    private fun showError(message: String) { status.text = message }

    private fun retainFailedWebView(view: WebView) {
        synchronized(failedWebViews) {
            if (!failedWebViews.contains(view)) failedWebViews.add(view)
        }
    }

    private fun destroyCurrentWebView() {
        val old = webView ?: return
        (old.parent as? ViewGroup)?.removeView(old)
        val cleaned = runCatching {
            old.stopLoading()
            old.destroy()
        }.isSuccess
        if (cleaned) {
            if (webView === old) webView = null
        } else {
            retainFailedWebView(old)
            showError("Очистка WebView не подтверждена. Контейнер нельзя считать свободным.")
        }
    }

    override fun onDestroy() {
        destroyCurrentWebView()
        super.onDestroy()
    }

    private fun normalizeWebUrl(raw: String): String? =
        AccountWebUrlPolicy.normalize(raw)

    companion object {
        // Intentionally retain WebViews whose destroy() failed; do not falsely report clean state.
        private val failedWebViews = java.util.Collections.synchronizedList(mutableListOf<WebView>())
        private const val EXTRA_ACCOUNT_ID = "web.account.id"
        private const val EXTRA_USERNAME = "web.account.username"
        private const val EXTRA_URL = "web.account.url"
        private const val EXTRA_COOKIES = "web.account.cookies"
        private const val EXTRA_USER_AGENT = "web.account.ua"
        private const val EXTRA_PROXY_ENABLED = "web.proxy.enabled"
        private const val EXTRA_PROXY_HOST = "web.proxy.host"
        private const val EXTRA_PROXY_PORT = "web.proxy.port"
        private const val EXTRA_PROXY_USER = "web.proxy.user"
        private const val EXTRA_PROXY_PASS = "web.proxy.pass"

        fun launch(context: Context, account: MangaBuffAccount, cookies: String, proxy: AppProxyConfig) {
            val url = account.getSafeActiveMangaUrl()
                .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                ?: "https://mangabuff.ru/"
            val intent = Intent(context, AccountWebViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ACCOUNT_ID, account.id)
                .putExtra(EXTRA_USERNAME, account.username)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_COOKIES, cookies)
                .putExtra(EXTRA_USER_AGENT, account.getSafeUserAgent())
                .putExtra(EXTRA_PROXY_ENABLED, proxy.enabled)
                .putExtra(EXTRA_PROXY_HOST, proxy.host)
                .putExtra(EXTRA_PROXY_PORT, proxy.port)
                .putExtra(EXTRA_PROXY_USER, proxy.username)
                .putExtra(EXTRA_PROXY_PASS, proxy.password)
            context.startActivity(intent)
        }
    }
}
