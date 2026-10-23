package com.example.myapplication.slot

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.example.myapplication.ContainerApp
import com.example.myapplication.automation.WebViewContainerPrivacy

/**
 * One container process owns one immutable route and one data-directory suffix.
 * An Activity lifecycle event is not treated as proof that Chromium's networking has drained.
 */
@SuppressLint("RestrictedApi")
open class SlotActivity : ComponentActivity() {
    private var webView: WebView? = null
    private lateinit var status: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeTabId: String? = null
    private var activeRouteKey: String? = null
    private var activeBinding: SlotProcessRouteBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(24, 16, 24, 16); textSize = 12f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        val tabId = intent.getStringExtra(SlotLauncher.EXTRA_TAB_ID).orEmpty()
        val rawUrl = intent.getStringExtra(SlotLauncher.EXTRA_URL).orEmpty()
        val type = runCatching {
            SlotProxyType.valueOf(intent.getStringExtra(SlotLauncher.EXTRA_PROXY_TYPE).orEmpty())
        }.getOrNull()
        val route = type?.let {
            SlotRoute(
                type = it,
                host = intent.getStringExtra(SlotLauncher.EXTRA_HOST).orEmpty(),
                port = intent.getIntExtra(SlotLauncher.EXTRA_PORT, 0),
                username = intent.getStringExtra(SlotLauncher.EXTRA_USER).orEmpty(),
                password = intent.getStringExtra(SlotLauncher.EXTRA_PASS).orEmpty()
            )
        }
        if (!SlotLauncher.isValidTabId(tabId) || route == null) {
            return fail("Некорректные параметры слота.")
        }
        route.validate()?.let { return fail(it) }
        val url = normalizeSlotUrlForRoute(rawUrl, route) ?: return fail(
            if (rawUrl.trim().startsWith("http://", ignoreCase = true))
                "HTTP без TLS отключён политикой безопасности. Открой HTTPS-адрес."
            else "Недопустимый URL: разрешены только корректные HTTPS-адреса."
        )

        val slot = SlotLauncher.slotIndexOf(currentProcessName())
            ?: return fail("Слот запущен вне своего процесса — изоляция невозможна.")
        val containerApp = application as? ContainerApp
        if (!SlotProcessAdmissionPolicy.isReady(
                processName = currentProcessName(),
                isolatedWebViewProcessReady = containerApp?.isolatedWebViewProcessReady == true,
                isolatedWebViewSuffix = containerApp?.isolatedWebViewSuffix
            )
        ) {
            return fail("Не подтверждена изоляция каталога WebView для этого слота. Страница не загружена.")
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            return fail("WebView не поддерживает профили (MULTI_PROFILE).")
        }
        if (!WebViewFeature.isStartupFeatureSupported(this, WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX)) {
            return fail("WebView не поддерживает отдельный каталог данных процесса.")
        }
        if (route.type != SlotProxyType.NONE &&
            !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        ) {
            return fail("WebView не поддерживает прокси. Страница не загружена.")
        }

        val reservation = SlotProcessRouteOwnerRegistry.owner.reserve(route)
        val accepted = reservation as? SlotRouteReservation.Accepted
            ?: return fail((reservation as SlotRouteReservation.Rejected).message)
        val binding = accepted.binding
        activeBinding = binding
        activeTabId = tabId
        activeRouteKey = route.bindingKey()

        title = "Слот ${slot + 1} из ${SlotLauncher.SLOT_COUNT} — ${route.describe()}"
        status.text = "Слот ${slot + 1}/${SlotLauncher.SLOT_COUNT} • ${route.describe()} • профиль ${SlotLauncher.profileName(tabId)}"

        val wv = WebView(this)
        try {
            // Profile assignment precedes all settings and navigation.
            WebViewCompat.setProfile(wv, SlotLauncher.profileName(tabId))
        } catch (_: Exception) {
            wv.destroy()
            SlotProcessRouteOwnerRegistry.owner.quarantine(binding, "PROFILE_BIND_FAILED")
            return fail("Не удалось привязать профиль WebView. Слот заблокирован до перезапуска приложения.")
        }
        val cookies = ProfileStore.getInstance().getProfile(SlotLauncher.profileName(tabId))?.cookieManager
        if (cookies == null) {
            wv.destroy()
            SlotProcessRouteOwnerRegistry.owner.quarantine(binding, "PROFILE_COOKIE_MANAGER_MISSING")
            return fail("Профиль вкладки не найден. Слот заблокирован до перезапуска приложения.")
        }
        WebViewContainerPrivacy.apply(this, wv, cookies)
        wv.webViewClient = SlotClient(binding)
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        webView = wv

        applyRouteThenLoad(binding, accepted.applyProxyOverride, url, wv)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tabId = intent.getStringExtra(SlotLauncher.EXTRA_TAB_ID).orEmpty()
        val rawUrl = intent.getStringExtra(SlotLauncher.EXTRA_URL).orEmpty()
        val type = runCatching {
            SlotProxyType.valueOf(intent.getStringExtra(SlotLauncher.EXTRA_PROXY_TYPE).orEmpty())
        }.getOrNull() ?: run {
            status.text = "Новый запрос слота отклонён: неизвестный тип маршрута."
            return
        }
        val requestedRoute = SlotRoute(
            type = type,
            host = intent.getStringExtra(SlotLauncher.EXTRA_HOST).orEmpty(),
            port = intent.getIntExtra(SlotLauncher.EXTRA_PORT, 0),
            username = intent.getStringExtra(SlotLauncher.EXTRA_USER).orEmpty(),
            password = intent.getStringExtra(SlotLauncher.EXTRA_PASS).orEmpty()
        )
        requestedRoute.validate()?.let {
            status.text = "Новый запрос слота отклонён: $it"
            return
        }
        val url = normalizeSlotUrlForRoute(rawUrl, requestedRoute)
        if (tabId != activeTabId || requestedRoute.bindingKey() != activeRouteKey || url == null) {
            status.text = "Запрос отклонён: процесс закреплён за текущим профилем и маршрутом. Используй другой слот."
            return
        }
        // Same tab and same immutable route: navigation may change without changing ProxyController.
        webView?.loadUrl(url)
    }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else null

    private fun applyRouteThenLoad(
        binding: SlotProcessRouteBinding,
        applyProxyOverride: Boolean,
        url: String,
        target: WebView
    ) {
        val rule = binding.proxyRule
        if (rule == null) {
            // A fresh process bound to NONE has no custom override. The route owner rejects proxies
            // later in this process instead of trying an unsafe reset while Chromium may be active.
            target.loadUrl(url)
            return
        }
        if (!applyProxyOverride) {
            target.loadUrl(url)
            return
        }

        val config = try {
            ProxyConfig.Builder().addProxyRule(rule).build()
        } catch (_: Exception) {
            SlotProcessRouteOwnerRegistry.owner.quarantine(binding, "PROXY_CONFIG_BUILD_FAILED")
            return fail("Не удалось подготовить правило прокси. Слот заблокирован до перезапуска приложения.")
        }
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                SlotProcessRouteOwnerRegistry.owner.quarantine(binding, "PROXY_APPLY_TIMEOUT")
                fail("Применение прокси не подтверждено. Слот заблокирован; прямое соединение не используется.")
            }
        }
        mainHandler.postDelayed(timeout, PROXY_APPLY_TIMEOUT_MS)
        try {
            ProxyController.getInstance().setProxyOverride(
                config,
                ContextCompat.getMainExecutor(this),
                Runnable {
                    if (!completed.compareAndSet(false, true)) return@Runnable
                    mainHandler.removeCallbacks(timeout)
                    if (!SlotProcessRouteOwnerRegistry.owner.markProxyApplied(binding)) {
                        fail("Состояние прокси не удалось подтвердить. Страница не загружена.")
                        return@Runnable
                    }
                    if (!isFinishing && !isDestroyed && webView === target) target.loadUrl(url)
                }
            )
        } catch (_: Exception) {
            mainHandler.removeCallbacks(timeout)
            if (completed.compareAndSet(false, true)) {
                SlotProcessRouteOwnerRegistry.owner.quarantine(binding, "PROXY_APPLY_THROWN")
            }
            fail("Ошибка применения прокси. Слот заблокирован; прямое соединение не используется.")
        }
    }

    private fun fail(message: String) {
        status.text = message
        webView?.stopLoading()
    }

    private inner class SlotClient(private val binding: SlotProcessRouteBinding) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme?.lowercase()
            if (scheme != "https") {
                if (scheme == "http") {
                    status.text = "HTTP без TLS отключён политикой безопасности. Переход заблокирован."
                }
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            status.text = "${status.text.toString().substringBefore('\n')}\n$url"
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView?,
            handler: HttpAuthHandler?,
            host: String?,
            realm: String?
        ) {
            if (handler != null && binding.proxyUser.isNotEmpty() &&
                binding.matchesProxyAuthHost(host)
            ) {
                handler.proceed(binding.proxyUser, binding.proxyPassword)
            } else {
                handler?.cancel()
            }
        }
    }

    override fun onDestroy() {
        val view = webView
        if (view != null) {
            var cleanupFailed = false
            try {
                view.stopLoading()
            } catch (_: Exception) {
                cleanupFailed = true
            }
            try {
                view.destroy()
            } catch (_: Exception) {
                cleanupFailed = true
            }
            if (cleanupFailed) {
                // A failed renderer teardown is not a clean stop. Keep the view strongly
                // referenced and quarantine this process so it cannot host another tab.
                failedWebViews.add(view)
                activeBinding?.let {
                    SlotProcessRouteOwnerRegistry.owner.quarantine(it, "WEBVIEW_DESTROY_FAILED")
                }
            }
        }
        webView = null
        // Keep process-owned route and bridge. Activity destruction cannot prove that Chromium's
        // sockets or requests have drained.
        super.onDestroy()
    }

    private companion object {
        const val PROXY_APPLY_TIMEOUT_MS = 15_000L
        val failedWebViews: MutableList<WebView> =
            java.util.Collections.synchronizedList(mutableListOf())
    }
}

class Slot0Activity : SlotActivity()
class Slot1Activity : SlotActivity()
class Slot2Activity : SlotActivity()
