package com.example.mangabuffauto.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.mangabuffauto.AndroidBotLog
import com.example.mangabuffauto.task.AutomationTask
import androidx.compose.runtime.mutableIntStateOf

/**
 * Process-wide runtime.
 *
 * Engine и WebView живут дольше Activity. Поэтому блокировка экрана,
 * пересоздание Activity и переход UI в background не уничтожают текущую
 * WebView-сессию, пока жив Foreground Service.
 */
object AutomationRuntime {

    private var appContext: Context? = null
    private var configuredProfile: String? = null
    private var scheduler: AutomationScheduler? = null
    private var persistentState: PersistentAutomationState? = null
    private var recoveryManager: RecoveryManager? = null

    // Incremented whenever a WebView instance is replaced (renderer crash/recovery).
    // Activity observes it and recreates its AndroidView wrapper around the new WebView.
    private val _webViewVersion = mutableIntStateOf(0)
    val webViewVersion: Int get() = _webViewVersion.intValue

    // Temporary CPU wake lock used only while the reading task is actively running.
    // It is intentionally not a permanent lock and is released on task stop/finish.
    private var automationWakeLock: PowerManager.WakeLock? = null

    // Keeps the CPU available while the user's persistent automation switch is ON.
    // The foreground service remains the primary keep-alive; this partial wake lock
    // prevents aggressive Doze/OEM power management from suspending the native
    // scheduler and WebView heartbeat while the screen is off.
    private var keepAliveWakeLock: PowerManager.WakeLock? = null

    // True after a main-frame HTTP 403. While blocked, no code path in the runtime
    // performs an automatic reload. A new request is allowed only after an explicit
    // manual refresh by the user.
    @Volatile
    private var blockedByHttp403: Boolean = false

    @Volatile
    private var appVisible: Boolean = false

    @Volatile
    private var screenOn: Boolean = true

    var engine: AutomationEngine? = null
        private set

    var webView: WebView? = null
        private set

    var onBackStateChanged: ((Boolean) -> Unit)? = null
    var onPageStarted: ((WebView, String) -> Unit)? = null
    var onPageFinished: ((WebView, String) -> Unit)? = null

    fun ensure(context: Context, profileName: String): WebView {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        persistentState = persistentState ?: PersistentAutomationState(applicationContext)
        recoveryManager = recoveryManager ?: RecoveryManager(applicationContext)

        val current = webView
        if (current != null && configuredProfile == profileName) {
            return current
        }

        if (current != null && configuredProfile != profileName) {
            switchProfile(profileName)
            return requireNotNull(webView)
        }

        val runtimeEngine = engine ?: AutomationEngine().also { created ->
            engine = created
        }

        if (scheduler == null) {
            scheduler = AutomationScheduler(
                applicationContext,
                object : AutomationScheduler.AutomationRuntimeFacade {
                    override fun engine(): AutomationEngine? = AutomationRuntime.engine
                    override fun startTask(task: AutomationTask) = AutomationRuntime.startTask(task)
                    override fun activeProfile(): String = configuredProfile ?: profileName
                }
            )
        }

        recoveryManager?.inspect(profileName)?.let { interrupted ->
            AndroidBotLog.log("RECOVERY: interrupted task=${interrupted.taskId} attempt=${interrupted.taskAttempt} run=${interrupted.taskRunId}")
            // Never resume an old JS execution after process death. Scheduler decides the next task.
            recoveryManager?.acknowledgeInterruption(profileName)
        }

        runtimeEngine.onTaskExecutionActiveChanged = { task, active ->
            if (active) {
                acquireAutomationWakeLock(applicationContext)
            } else {
                releaseAutomationWakeLock()
            }
        }

        blockedByHttp403 = false
        val savedSpeed = persistentState?.readingSpeed(profileName) ?: 1.0f
        runtimeEngine.setReadingSpeed(savedSpeed)

        val view = createWebView(applicationContext, profileName, runtimeEngine)
        webView = view
        configuredProfile = profileName
        runtimeEngine.start(view, profileName, persistentState)
        WebViewMemoryCleanup.start { webView }

        // Every fresh application start opens the MangaBuff home page.
        // The previously saved page is not used as the initial launch URL.
        val initialUrl = "https://mangabuff.ru/"
        AndroidBotLog.log("START: LOAD MangaBuff home")
        view.loadUrl(initialUrl)
        return view
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        engine?.setBackgroundMode(!visible || !screenOn)
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        engine?.setBackgroundMode(!appVisible || !on)
    }

    fun startTask(task: AutomationTask) {
        engine?.startTask(task)
    }

    fun isAutoModeEnabled(): Boolean = scheduler?.isEnabled() == true

    fun setAutoModeEnabled(enabled: Boolean) {
        persistentState?.saveAutoMode(enabled)
        if (enabled) {
            appContext?.let { acquireKeepAliveWakeLock(it) }
        } else {
            releaseKeepAliveWakeLock()
        }
        scheduler?.setEnabled(enabled)
    }

    fun restartTask(task: AutomationTask) {
        engine?.restartTask(task)
    }

    fun hasTaskInFlight(): Boolean {
        return engine?.hasTaskInFlight() == true
    }

    fun stop(reason: String = "Manual") {
        engine?.stop(reason)
        releaseAutomationWakeLock()
    }

    /**
     * Keeps the CPU awake only while a real automation task is executing.
     * A foreground service alone does not guarantee that CPU/JS timers continue
     * while the screen is off on aggressive Android/HyperOS builds.
     */
    private fun acquireKeepAliveWakeLock(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return
        if (keepAliveWakeLock?.isHeld == true) return

        keepAliveWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MangaBuffAuto:KeepAlive"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        AndroidBotLog.log("POWER: KEEPALIVE_WAKELOCK_ACQUIRED")
    }

    private fun releaseKeepAliveWakeLock() {
        val lock = keepAliveWakeLock ?: return
        if (lock.isHeld) lock.release()
        keepAliveWakeLock = null
        AndroidBotLog.log("POWER: KEEPALIVE_WAKELOCK_RELEASED")
    }

    private fun acquireAutomationWakeLock(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return
        val existing = automationWakeLock
        if (existing?.isHeld == true) return

        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MangaBuffAuto:Automation"
        ).apply {
            setReferenceCounted(false)
        }
        automationWakeLock = lock
        lock.acquire()
        AndroidBotLog.log("POWER: AUTOMATION_WAKELOCK_ACQUIRED")
    }

    private fun releaseAutomationWakeLock() {
        val lock = automationWakeLock ?: return
        if (lock.isHeld) {
            lock.release()
            AndroidBotLog.log("POWER: AUTOMATION_WAKELOCK_RELEASED")
        }
        automationWakeLock = null
    }

    /**
     * Returns true when the main MangaBuff document was rejected with HTTP 403.
     * This is intentionally a local safety latch, not a bypass mechanism.
     */
    fun isBlockedByHttp403(): Boolean = blockedByHttp403

    /**
     * Explicit user action: clear the 403 latch and perform exactly one reload.
     * There is no automatic retry/backoff loop here.
     */
    fun manualReload(): Boolean {
        val view = webView ?: return false
        blockedByHttp403 = false
        AndroidBotLog.log("UI: MANUAL_PAGE_RELOAD")
        view.reload()
        return true
    }

    fun switchProfile(profileName: String): Boolean {
        val currentEngine = engine ?: return false
        val currentState = currentEngine.state.value
        if (currentState != AutomationState.IDLE && currentState != AutomationState.STOPPED) {
            AndroidBotLog.log("PROFILE: switch blocked while task is running")
            return false
        }

        val old = webView
        currentEngine.detachWebView()
        old?.let { view ->
            try {
                view.stopLoading()
                view.removeJavascriptInterface("AndroidBot")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "PROFILE: old WebView destroy failed: ${e.message}")
            }
        }

        WebViewMemoryCleanup.stop()
        webView = null
        configuredProfile = null
        blockedByHttp403 = false
        currentEngine.resetStats()

        val context = appContext ?: return false
        ensure(context, profileName)
        scheduler?.onProfileChanged()
        AndroidBotLog.log("PROFILE: active=$profileName")
        return true
    }

    fun destroy() {
        scheduler?.destroy()
        scheduler = null
        releaseAutomationWakeLock()
        releaseKeepAliveWakeLock()
        engine?.destroy()
        webView?.let { view ->
            try {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            } catch (_: Exception) {
            }
        }
        WebViewMemoryCleanup.stop()
        webView = null
        engine = null
        configuredProfile = null
        blockedByHttp403 = false
        onBackStateChanged = null
        _webViewVersion.intValue++
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        context: Context,
        profileName: String,
        runtimeEngine: AutomationEngine
    ): WebView {
        return WebView(context).also { view ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                WebViewCompat.setProfile(view, profileName)
                AndroidBotLog.log("PROFILE: WebView profile=$profileName")
            } else {
                AndroidBotLog.log("PROFILE: MULTI_PROFILE unsupported by current WebView")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAcceptThirdPartyCookies(view, true)
                }
            }

            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let { msg ->
                        runtimeEngine.logConsole(
                            msg.messageLevel().name,
                            msg.message() + " (${msg.sourceId()}:${msg.lineNumber()})"
                        )
                        Log.d("MangaBuffConsole", "[${msg.messageLevel()}] ${msg.message()}")
                    }
                    return true
                }
            }

            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()

                    // OAuth/social login starts on MangaBuff and temporarily leaves
                    // mangabuff.ru for VK / Discord / Yandex / Google.
                    // The previous implementation returned true for every external
                    // URL, which cancelled the OAuth navigation before the provider
                    // could authenticate the user.
                    if (isOAuthUrl(url) || oauthFlowActive) {
                        if (isInternalUrl(url)) {
                            oauthFlowActive = false
                        }
                        return false
                    }

                    // Normal external links are still opened outside the app.
                    return if (isInternalUrl(url)) {
                        false
                    } else {
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                Uri.parse(url)
                            ).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            v.context.applicationContext.startActivity(intent)
                        } catch (e: Exception) {
                            AndroidBotLog.log("EXTERNAL URL: unable to open $url: ${e.message}")
                        }
                        true
                    }
                }

                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    val u = url ?: ""
                    AndroidBotLog.log("PAGE: PAGE_STARTED=$u")
                    onPageStarted?.invoke(v ?: return, u)
                    if (isInternalUrl(u)) {
                        persistentState?.saveLastUrl(configuredProfile ?: profileName, u)
                    }
                    runtimeEngine.notifyPageStarted(u)
                    if (isInternalUrl(u) && !isOAuthUrl(u)) {
                        oauthFlowActive = false
                    }
                    onBackStateChanged?.invoke(true)
                }

                override fun onPageFinished(v: WebView, url: String) {
                    AndroidBotLog.log("PAGE: PAGE_FINISHED=$url")
                    if (isInternalUrl(url)) {
                        persistentState?.saveLastUrl(configuredProfile ?: profileName, url)
                    }
                    runtimeEngine.notifyPageFinished(url)
                    onPageFinished?.invoke(v, url)
                    onBackStateChanged?.invoke(v.canGoBack())
                }

                override fun onReceivedError(
                    v: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        AndroidBotLog.log("NET ERROR: ${error?.description}")
                    }
                }

                override fun onReceivedHttpError(
                    v: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    // WebView can report HTTP errors for images, JS, ads, etc.
                    // Only a main-document error is allowed to block automation.
                    if (request?.isForMainFrame == true) {
                        val statusCode = errorResponse?.statusCode ?: 0
                        if (statusCode == 403) {
                            blockedByHttp403 = true
                            AndroidBotLog.log(
                                "HTTP 403 MAIN FRAME -> BLOCKED; automatic reload disabled"
                            )
                        }
                        runtimeEngine.notifyHttpError(statusCode)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    runtimeEngine.notifyRendererCrashed()
                    Handler(Looper.getMainLooper()).post { recoverWebViewAfterRendererCrash(profileName) }
                    return true
                }
            }
        }
    }

    private fun recoverWebViewAfterRendererCrash(profileName: String) {
        val context = appContext ?: return
        val old = webView
        AndroidBotLog.log("RECOVERY: renderer crash -> rebuilding WebView profile=$profileName")

        engine?.detachWebView()
        try {
            (old?.parent as? android.view.ViewGroup)?.removeView(old)
            old?.destroy()
        } catch (e: Exception) {
            AndroidBotLog.log("RECOVERY: old WebView destroy error: ${e.message}")
        }

        blockedByHttp403 = false
        val runtimeEngine = engine ?: AutomationEngine().also { engine = it }
        val newView = createWebView(context, profileName, runtimeEngine)
        webView = newView
        configuredProfile = profileName
        runtimeEngine.start(newView, profileName, persistentState)
        WebViewMemoryCleanup.start { webView }
        _webViewVersion.intValue++

        val restoredUrl = persistentState?.lastUrl(profileName)?.takeIf { isInternalUrl(it) }
            ?: "https://mangabuff.ru/"
        newView.loadUrl(restoredUrl)
        AndroidBotLog.log("RECOVERY: renderer replacement loaded=$restoredUrl")
    }

    private var oauthFlowActive: Boolean = false

    private fun isOAuthUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = (uri.host ?: "").lowercase()
            val path = uri.path ?: ""

            // The OAuth flow is initiated by these MangaBuff endpoints.
            // Provider host matching is intentionally limited to the services
            // exposed by the login page supplied by the user.
            val startsLoginRedirect = isInternalUrl(url) && path.startsWith("/login/redirect/")
            val providerHost = host == "id.vk.com" ||
                host == "id.vk.ru" ||
                host == "vk.com" ||
                host.endsWith(".vk.com") ||
                host.endsWith(".vk.ru") ||
                host == "discord.com" ||
                host.endsWith(".discord.com") ||
                host == "yandex.ru" ||
                host.endsWith(".yandex.ru") ||
                host == "accounts.google.com" ||
                host.endsWith(".google.com")

            if (startsLoginRedirect) {
                oauthFlowActive = true
                true
            } else {
                providerHost
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isInternalUrl(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host ?: return false
            host == "mangabuff.ru" || host == "www.mangabuff.ru"
        } catch (_: Exception) {
            false
        }
    }

    private const val TAG = "MangaBuffAuto"
}
