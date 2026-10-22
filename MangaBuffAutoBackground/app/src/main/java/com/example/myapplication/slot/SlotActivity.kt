package com.example.myapplication.slot

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.myapplication.automation.WebViewContainerPrivacy

/**
 * Одна вкладка-контейнер. Работает в своём процессе (:slotN), поэтому её прокси-override
 * не влияет на другие вкладки. Маршрут фиксируется при запуске и не меняется.
 *
 * Принцип fail-closed: пока прокси не применён (или применить его не удалось), страница
 * НЕ загружается, чтобы реальный IP не утёк при сбое.
 */
@SuppressLint("RestrictedApi")
open class SlotActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var bridge: Socks5ConnectBridge? = null
    private lateinit var status: TextView

    // Данные, которыми WebView отвечает на 407 от прокси/моста.
    private var proxyUser = ""
    private var proxyPass = ""
    private var proxyAuthority = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(24, 16, 24, 16); textSize = 12f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        val tabId = intent.getStringExtra(SlotLauncher.EXTRA_TAB_ID).orEmpty()
        val url = normalizeSlotUrl(intent.getStringExtra(SlotLauncher.EXTRA_URL).orEmpty())
        val type = runCatching { SlotProxyType.valueOf(intent.getStringExtra(SlotLauncher.EXTRA_PROXY_TYPE).orEmpty()) }
            .getOrNull()
        val route = type?.let {
            SlotRoute(
                type = it,
                host = intent.getStringExtra(SlotLauncher.EXTRA_HOST).orEmpty(),
                port = intent.getIntExtra(SlotLauncher.EXTRA_PORT, 0),
                username = intent.getStringExtra(SlotLauncher.EXTRA_USER).orEmpty(),
                password = intent.getStringExtra(SlotLauncher.EXTRA_PASS).orEmpty()
            )
        }
        if (!SlotLauncher.isValidTabId(tabId) || url == null || route == null) return fail("Некорректные параметры слота.")
        route.validate()?.let { return fail(it) }

        val slot = SlotLauncher.slotIndexOf(currentProcessName())
            ?: return fail("Слот запущен вне своего процесса — изоляция невозможна.")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            return fail("WebView не поддерживает профили (MULTI_PROFILE).")
        }
        if (!WebViewFeature.isStartupFeatureSupported(WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX, this)) {
            return fail("WebView не поддерживает отдельный каталог данных процесса.")
        }
        title = "Слот $slot — ${route.describe()}"
        status.text = "Слот $slot • ${route.describe()} • профиль ${SlotLauncher.profileName(tabId)}"

        val wv = WebView(this)
        try {
            WebViewCompat.setProfile(wv, SlotLauncher.profileName(tabId))
        } catch (e: Exception) {
            wv.destroy()
            return fail("Не удалось привязать профиль: ${e.message}")
        }
        val cookies = ProfileStore.getInstance().getProfile(SlotLauncher.profileName(tabId))?.cookieManager
        if (cookies == null) {
            wv.destroy()
            return fail("Профиль вкладки не найден.")
        }
        WebViewContainerPrivacy.apply(this, wv, cookies)
        wv.webViewClient = SlotClient()
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        webView = wv

        applyRouteThenLoad(route, url, wv)
    }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else null

    private fun applyRouteThenLoad(route: SlotRoute, url: String, wv: WebView) {
        val executor = ContextCompat.getMainExecutor(this)
        when (route.type) {
            SlotProxyType.NONE -> {
                // Прямое подключение — явный выбор пользователя для этого слота.
                wv.loadUrl(url)
            }
            SlotProxyType.HTTP, SlotProxyType.SOCKS5 -> {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    return fail("WebView не поддерживает прокси. Страница не загружена, чтобы не раскрыть реальный IP.")
                }
                val rule: String
                if (route.type == SlotProxyType.HTTP) {
                    rule = "http://${route.authorityHost()}:${route.port}"
                    proxyUser = route.username
                    proxyPass = route.password
                    proxyAuthority = route.host.trim()
                } else {
                    // Chromium не умеет SOCKS5 с логином: идём через локальный мост на loopback.
                    val b = try {
                        Socks5ConnectBridge(route.host.trim(), route.port, route.username, route.password).start()
                    } catch (e: Exception) {
                        return fail("Не удалось запустить SOCKS5-мост: ${e.message}")
                    }
                    bridge = b
                    rule = "http://127.0.0.1:${b.port}"
                    proxyUser = Socks5ConnectBridge.AUTH_USER
                    proxyPass = b.accessToken
                    proxyAuthority = "127.0.0.1"
                }
                val config = try {
                    ProxyConfig.Builder().addProxyRule(rule).build()
                } catch (e: Exception) {
                    return fail("Некорректное правило прокси: ${e.message}")
                }
                ProxyController.getInstance().setProxyOverride(config, executor, Runnable {
                    // Загружаем только после подтверждения, что override действует.
                    wv.loadUrl(url)
                })
            }
        }
    }

    private fun fail(message: String) {
        status.text = message
        webView?.stopLoading()
    }

    private inner class SlotClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme?.lowercase()
            return scheme != "https" && scheme != "http" // intent:, file:, content: и т.п. блокируются
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            status.text = "${status.text.toString().substringBefore('\n')}\n$url"
        }

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
            // Отвечаем только прокси/мосту этого слота, а не произвольному сайту.
            if (handler != null && proxyUser.isNotEmpty() && host.equals(proxyAuthority, ignoreCase = true)
            ) {
                handler.proceed(proxyUser, proxyPass)
            } else {
                handler?.cancel()
            }
        }
    }

    override fun onDestroy() {
        webView?.let {
            runCatching { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }
        }
        webView = null
        runCatching { bridge?.close() }
        bridge = null
        super.onDestroy()
    }
}

class Slot0Activity : SlotActivity()
class Slot1Activity : SlotActivity()
class Slot2Activity : SlotActivity()
