package com.example.myapplication.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AutomationRuntime(
    private val context: Context,
    private val onLog: (LogEntry) -> Unit,
    private val onAccountStatusUpdate: (accountId: String, statusMessage: String, isRunning: Boolean, currentTask: String, progress: Float) -> Unit,
    private val onMangaActiveUrlUpdate: (accountId: String, url: String, title: String) -> Unit,
    private val onAccountStatsUpdate: (accountId: String, diamonds: String, cardDrop: String, chapters: String, comments: String) -> Unit = { _, _, _, _, _ -> },
    private val onDailyStatsUpdate: (accountId: String, stats: DailyStats) -> Unit = { _, _ -> },
    private val onWebViewAssigned: (accountId: String, webView: WebView) -> Unit = { _, _ -> },
    private val onWebViewCleared: (accountId: String, webView: WebView) -> Unit = { _, _ -> }
) {
    private val runtimes = mutableMapOf<String, AccountRuntime>()
    private val automationEngines = mutableMapOf<String, MangaBuffAutomation>()
    private val executionJobs = mutableMapOf<String, Job>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val webViewStore = ProfileWebViewStore(
        context = context,
        onLog = onLog,
        onRendererGone = { accountId ->
            onLog(
                LogEntry(
                    username = accountId,
                    component = "SECURITY",
                    message = "RENDERER_GONE_CANCEL_ACCOUNT accountId=$accountId",
                    isError = true
                )
            )
            stopAccount(accountId)
        }
    )

    @Synchronized
    private fun getOrCreateEngine(accountId: String): MangaBuffAutomation {
        return automationEngines.getOrPut(accountId) {
            val engine = MangaBuffAutomation(context, onLog, onAccountStatusUpdate, onMangaActiveUrlUpdate, onAccountStatsUpdate, onDailyStatsUpdate)
            onLog(LogEntry(username = accountId, component = "ENGINE", message = "CREATE engineId=${engine.hashCode()}"))
            engine
        }.also {
            onLog(LogEntry(username = accountId, component = "ENGINE", message = "GET engineId=${it.hashCode()}"))
        }
    }

    @Synchronized
    fun prepareAccount(account: MangaBuffAccount): AccountRuntime {
        onLog(LogEntry(username = account.username, component = "ACCOUNT", message = "START requested"))
        val (profileName, webView) = webViewStore.getOrCreateWebView(account.id, account.getSafeCookiesJson())
        onWebViewAssigned(account.id, webView)
        val runtime = AccountRuntime(
            accountId = account.id,
            profileName = profileName,
            webView = webView,
            state = AccountState.IDLE
        )
        runtimes[account.id] = runtime
        onLog(
            LogEntry(
                username = account.username,
                component = "ACCOUNT",
                message = "READY accountId=${account.id} profile=$profileName runId=${runtime.runId} webView=${webView.hashCode()}"
            )
        )
        return runtime
    }

    @Synchronized
    fun switchAccount(fromAccountId: String?, toAccount: MangaBuffAccount): AccountRuntime {
        if (fromAccountId != null && fromAccountId != toAccount.id) {
            stopAccount(fromAccountId)
        }
        return prepareAccount(toAccount)
    }

    fun skipCurrentManga(accountId: String): Boolean {
        return automationEngines[accountId]?.skipCurrentManga() == true
    }

    fun markCurrentMangaAsRead(accountId: String): Boolean {
        return automationEngines[accountId]?.markCurrentMangaAsRead() == true
    }

    @Synchronized
    fun stopAccount(accountId: String) {
        executionJobs.remove(accountId)?.cancel()

        val runtime = runtimes.remove(accountId)
        if (runtime != null) {
            runtime.state = AccountState.STOPPED
            onWebViewCleared(accountId, runtime.webView)
            webViewStore.releaseWebView(accountId)
            onLog(
                LogEntry(
                    username = accountId,
                    component = "ACCOUNT",
                    message = "STOPPED accountId=$accountId runId=${runtime.runId} webView=${runtime.webView.hashCode()}"
                )
            )
        }

        val engine = automationEngines.remove(accountId)
        if (engine != null) {
            engine.closeRuntime()
            onLog(
                LogEntry(
                    username = accountId,
                    component = "ENGINE",
                    message = "STOP / DESTROY engineId=${engine.hashCode()} accountId=$accountId"
                )
            )
        }
    }

    @Synchronized
    fun reloadAccount(account: MangaBuffAccount) {
        val runtime = runtimes[account.id] ?: prepareAccount(account)
        mainHandler.post {
            try {
                runtime.webView.reload()
                runtime.webView.loadUrl("https://mangabuff.ru/")
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun executeTask(
        activeAccountId: String,
        account: MangaBuffAccount,
        settings: GlobalSettings,
        taskType: TaskType,
        expectedProfileName: String
    ) {
        val runtime = runtimes[account.id] ?: prepareAccount(account)
        onWebViewAssigned(account.id, runtime.webView)

        // Синхронизируем куки именно этого аккаунта
        webViewStore.syncCookiesForAccount(account.id, runtime.profileName, account.getSafeCookiesJson())

        // Pre-run security checks
        val accountIdMatch = (account.id == activeAccountId)
        val profileMatch = (runtime.profileName == expectedProfileName)
        
        val isMultiProfileSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        val actualProfileName = if (isMultiProfileSupported) {
            try {
                WebViewCompat.getProfile(runtime.webView)?.name.orEmpty()
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        val webViewProfileMatch = if (isMultiProfileSupported) {
            actualProfileName == runtime.profileName
        } else {
            false
        }

        onLog(
            LogEntry(
                username = account.username,
                component = "SECURITY",
                message = "ACCOUNT_BIND_CHECK accountId=${account.id} expectedProfile=${runtime.profileName} actualProfile=$actualProfileName webView=${runtime.webView.hashCode()}"
            )
        )

        if (!accountIdMatch || !profileMatch || !webViewProfileMatch) {
            onLog(LogEntry(username = account.username, component = "SECURITY", message = "ATTACH_FOREIGN_WEBVIEW failed", isError = true))
            throw SecurityException("Security check failed! accountIdMatch=$accountIdMatch, profileMatch=$profileMatch, webViewProfileMatch=$webViewProfileMatch")
        }

        /*
         * Background execution must not depend on the Activity being alive.
         * UI attachment is optional and only used for debug display.
         */
        if (runtime.webView.isAttachedToWindow) {
            onLog(
                LogEntry(
                    username = account.username,
                    component = "WEBVIEW",
                    message = "ATTACHED_FOR_UI accountId=${account.id} webView=${runtime.webView.hashCode()}"
                )
            )
        } else {
            onLog(
                LogEntry(
                    username = account.username,
                    component = "WEBVIEW",
                    message = "BACKGROUND_NO_UI_ATTACHMENT accountId=${account.id} webView=${runtime.webView.hashCode()}"
                )
            )
        }

        val webViewUa = runtime.webView.settings.userAgentString
        val storedHttpUa = account.getSafeUserAgent()
        onLog(
            LogEntry(
                username = account.username,
                component = "NETWORK",
                message = "WEBVIEW_UA=$webViewUa STORED_HTTP_UA=$storedHttpUa UA_MATCH=${webViewUa == storedHttpUa}"
            )
        )

        val engine = getOrCreateEngine(account.id)
        onLog(LogEntry(username = account.username, component = "ENGINE", message = "USE engineId=${engine.hashCode()}"))

        val engineHeartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5000L)
                val (url, isAttached) = withContext(Dispatchers.Main.immediate) {
                    val currentUrl = runtime.webView.url ?: "UNKNOWN"
                    val attached = runtime.webView.isAttachedToWindow
                    Pair(currentUrl, attached)
                }
                onLog(LogEntry(
                    username = account.username,
                    component = "ENGINE",
                    message = "HEARTBEAT engineId=${engine.hashCode()} accountId=${account.id} currentMangaUrl=${engine.getCurrentMangaUrl()} currentChapterId=${engine.getLastFinishedChapterId()} currentChapterNumber=${engine.getLastFinishedChapterNumber()}"
                ))
                onLog(LogEntry(
                    username = account.username,
                    component = "WEBVIEW",
                    message = "HEARTBEAT profile=${runtime.profileName} url=$url isAttached=$isAttached isDestroyed=false"
                ))
            }
        }

        runtime.state = AccountState.RUNNING

        val currentJob = coroutineContext[Job]
        synchronized(executionJobs) {
            executionJobs[account.id] =
                currentJob ?: throw IllegalStateException("ACCOUNT_JOB_MISSING accountId=${account.id}")
        }

        try {
            engine.runAccountTasks(account, settings, taskType, runtime.webView)
            runtime.state = AccountState.IDLE
            onLog(LogEntry(username = account.username, component = "ACCOUNT", message = "FINISHED"))
        } catch (e: CancellationException) {
            runtime.state = AccountState.STOPPED
            onLog(LogEntry(username = account.username, component = "ACCOUNT", message = "CANCELLED"))
            throw e
        } catch (e: Exception) {
            runtime.state = AccountState.ERROR
            onLog(LogEntry(username = account.username, component = "ACCOUNT", message = "FAILED reason=${e.message}", isError = true))
            throw e
        } finally {
            engineHeartbeatJob.cancel()
            synchronized(executionJobs) {
                val registered = executionJobs[account.id]
                if (registered == currentJob) {
                    executionJobs.remove(account.id)
                }
            }
        }
    }

    @Synchronized
    fun stopAll() {
        val ids = runtimes.keys.toList()
        for (id in ids) {
            stopAccount(id)
        }
        automationEngines.clear()
        webViewStore.releaseAll()
    }

    @Synchronized
    fun closeAllWebViews() {
        stopAll()
    }
}
