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
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.currentStatsDay
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class AutomationRuntime(
    private val context: Context,
    private val onLog: (LogEntry) -> Unit,
    private val onAccountStatusUpdate: (accountId: String, statusMessage: String, isRunning: Boolean, currentTask: String, progress: Float) -> Unit,
    private val onMangaActiveUrlUpdate: (accountId: String, url: String, title: String) -> Unit,
    private val onAccountStatsUpdate: (accountId: String, diamonds: String, cardDrop: String, chapters: String, comments: String, eventCandies: String) -> Unit = { _, _, _, _, _, _ -> },
    private val onDailyStatsUpdate: (accountId: String, stats: DailyStats) -> Unit = { _, _ -> },
    private val onTowerExpeditionEndsAtUpdate: (accountId: String, endsAtMillis: Long) -> Unit = { _, _ -> },
    private val onWebViewAssigned: (accountId: String, webView: WebView) -> Unit = { _, _ -> },
    private val onWebViewCleared: (accountId: String, webView: WebView) -> Unit = { _, _ -> },
    private val onAccountRecoveryNeeded: (accountId: String) -> Unit = {},
    private val onMineBalanceUpdate: (accountId: String, oreBalance: Int) -> Unit = { _, _ -> },
    /** false в рабочем процессе: аккаунты на диск пишет только основной процесс. */
    private val persistUserAgentMigration: Boolean = true
) {
    private val runtimes = ConcurrentHashMap<String, AccountRuntime>()
    private val automationEngines = ConcurrentHashMap<String, MangaBuffAutomation>()
    private val executionJobs = ConcurrentHashMap<String, Job>()
    private val stopLocks = ConcurrentHashMap<String, Mutex>()
    private val failedStops = ConcurrentHashMap.newKeySet<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val webViewStore = ProfileWebViewStore(
        context = context,
        onLog = onLog,
        onRendererGone = { accountId, webView ->
            val current = runtimes[accountId]
            if (current?.webView === webView) {
                onLog(
                    LogEntry(
                        username = accountId,
                        component = "SECURITY",
                        message = "RENDERER_GONE_CANCEL_ACCOUNT accountId=$accountId",
                        isError = true
                    )
                )
                onAccountRecoveryNeeded(accountId)
                stopAccount(accountId)
            } else {
                onLog(
                    LogEntry(
                        username = accountId,
                        component = "SECURITY",
                        message = "STALE_RENDERER_IGNORED accountId=$accountId webView=${webView.hashCode()}"
                    )
                )
            }
        }
    )

    @Synchronized
    private fun getOrCreateEngine(account: MangaBuffAccount): MangaBuffAutomation {
        val accountId = account.id
        return automationEngines.getOrPut(accountId) {
            val engine = MangaBuffAutomation(
                context = context,
                onLog = onLog,
                onAccountStatusUpdate = onAccountStatusUpdate,
                onMangaActiveUrlUpdate = onMangaActiveUrlUpdate,
                onAccountStatsUpdate = onAccountStatsUpdate,
                onDailyStatsUpdate = onDailyStatsUpdate,
                onTowerExpeditionEndsAtUpdate = onTowerExpeditionEndsAtUpdate,
                onRendererGone = { failedAccountId, failedWebView ->
                    val currentRuntime = runtimes[failedAccountId]
                    if (currentRuntime?.webView === failedWebView) {
                        onAccountRecoveryNeeded(failedAccountId)
                        stopAccount(failedAccountId)
                    } else {
                        onLog(
                            LogEntry(
                                username = failedAccountId,
                                component = "SECURITY",
                                message = "STALE_TASK_RENDERER_IGNORED accountId=$failedAccountId webView=${failedWebView.hashCode()}"
                            )
                        )
                    }
                },
                onMineBalanceUpdate = onMineBalanceUpdate,
                proxyAccountId = accountId,
                initialDailyStats = AccountDailyStatsMapper.fromAccount(account, currentStatsDay())
            )
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

    /** Best-effort stop request for UI/renderer callbacks; queue owners must await cleanup. */
    fun stopAccount(accountId: String) {
        executionJobs[accountId]?.let { it.cancel(); return }
        cleanupScope.launch {
            try { stopAccountAndAwait(accountId) }
            catch (e: Exception) {
                onLog(LogEntry(
                    username = accountId, component = "SECURITY",
                    message = "STOP_CLEANUP_UNCONFIRMED accountId=$accountId reason=${e.javaClass.simpleName}",
                    isError = true
                ))
            }
        }
    }

    suspend fun stopAccountAndAwait(accountId: String) {
        if (accountId in failedStops) throw IllegalStateException("STOP_ALREADY_QUARANTINED accountId=$accountId")
        val callerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val stopLock = stopLocks.computeIfAbsent(accountId) { Mutex() }
        try {
            StopCleanupBarrier.execute(STOP_CLEANUP_TIMEOUT_MS) {
                stopLock.withLock {
                    if (accountId in failedStops) throw IllegalStateException("STOP_ALREADY_QUARANTINED accountId=$accountId")
                    val (targetJob, targetRuntime, targetEngine) = synchronized(this@AutomationRuntime) {
                        val job = executionJobs[accountId]
                        val runtime = runtimes[accountId]
                        val engine = automationEngines[accountId]
                        if (runtime != null) runtimes.remove(accountId, runtime)
                        if (engine != null) automationEngines.remove(accountId, engine)
                        Triple(job, runtime, engine)
                    }
                    if (targetJob != null && targetJob === callerJob) {
                        throw IllegalStateException("STOP_CANNOT_JOIN_CURRENT_RUN_JOB accountId=$accountId")
                    }
                    targetJob?.cancel()
                    withContext(Dispatchers.Main.immediate) { targetEngine?.closeRuntime() }
                    targetJob?.join()

                    // A cancelled run may publish a runtime during synchronous setup; drain it too.
                    val (lateRuntime, lateEngine) = synchronized(this@AutomationRuntime) {
                        val runtime = runtimes.remove(accountId)
                        val engine = automationEngines.remove(accountId)
                        runtime to engine
                    }
                    if (lateEngine != null && lateEngine !== targetEngine) {
                        withContext(Dispatchers.Main.immediate) { lateEngine.closeRuntime() }
                    }
                    targetEngine?.awaitRuntimeClosed()
                    if (lateEngine != null && lateEngine !== targetEngine) lateEngine.awaitRuntimeClosed()

                    val runtimeToClear = lateRuntime ?: targetRuntime
                    if (runtimeToClear != null) {
                        runtimeToClear.state = AccountState.STOPPED
                        runCatching { onWebViewCleared(accountId, runtimeToClear.webView) }
                            .onFailure { error ->
                                onLog(LogEntry(
                                    username = accountId, component = "WEBVIEW",
                                    message = "WEBVIEW_CLEAR_CALLBACK_FAILED reason=${error.javaClass.simpleName}",
                                    isError = true
                                ))
                            }
                    }
                    webViewStore.releaseWebView(accountId)
                    onLog(LogEntry(
                        username = accountId, component = "ACCOUNT",
                        message = "STOPPED_CONFIRMED accountId=$accountId runId=${runtimeToClear?.runId ?: "unknown"}"
                    ))
                }
            }
        } catch (e: Exception) {
            // A timeout/destroy exception makes runtime state unknowable. Keep the profile/WebView
            // reference intact and refuse all future cleanup attempts that might imply a clean stop.
            failedStops.add(accountId)
            throw e
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
        expectedProfileName: String,
        promoCode: String = "",
        resumePersistedProgress: Boolean = false
    ) {
        val runJob = coroutineContext[Job]
        if (runJob != null) synchronized(this) {
            val previous = executionJobs[account.id]
            if (previous != null && previous !== runJob && previous.isActive) {
                throw IllegalStateException("ACCOUNT_RUN_ALREADY_ACTIVE accountId=${account.id}")
            }
            executionJobs[account.id] = runJob
        }
        try {
            executeTaskInternal(
                activeAccountId = activeAccountId,
                account = account,
                settings = settings,
                taskType = taskType,
                expectedProfileName = expectedProfileName,
                promoCode = promoCode,
                resumePersistedProgress = resumePersistedProgress
            )
        } finally {
            if (runJob != null) synchronized(this) {
                if (executionJobs[account.id] === runJob) executionJobs.remove(account.id)
            }
        }
    }

    private suspend fun executeTaskInternal(
        activeAccountId: String,
        account: MangaBuffAccount,
        settings: GlobalSettings,
        taskType: TaskType,
        expectedProfileName: String,
        promoCode: String = "",
        resumePersistedProgress: Boolean = false
    ) {
        coroutineContext.ensureActive()
        val runtime = runtimes[account.id] ?: prepareAccount(account)
        coroutineContext.ensureActive()
        onWebViewAssigned(account.id, runtime.webView)

        // Синхронизируем куки именно этого аккаунта
        webViewStore.syncCookiesForAccountAwait(account.id, runtime.profileName, account.getSafeCookiesJson())

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

        val webViewUa = runtime.webView.settings.userAgentString.orEmpty()
        val legacyDefaultUa = MangaBuffAccount().getSafeUserAgent()
        var engineAccount = account
        if (
            UserAgentConsistencyPolicy.shouldAdoptWebViewUserAgent(
                storedUserAgent = account.userAgent,
                legacyDefaultUserAgent = legacyDefaultUa,
                webViewUserAgent = webViewUa
            )
        ) {
            // New accounts save the actual UA from the login WebView. For legacy accounts
            // that still contain only the old hard-coded fallback, use this device's real
            // WebView UA for native requests instead of retaining a stale placeholder.
            val savedAccount = if (persistUserAgentMigration) {
                AccountRepository(context.applicationContext).updateAccount(account.id) { current ->
                    if (
                        current.userAgent.isNullOrBlank() ||
                        current.userAgent.trim() == legacyDefaultUa.trim()
                    ) {
                        current.copy(userAgent = webViewUa)
                    } else {
                        current
                    }
                }
            } else {
                // Рабочий процесс: используем UA только в памяти, на диск не пишем.
                account.copy(userAgent = webViewUa)
            }
            val resolvedUserAgent = savedAccount?.getSafeUserAgent() ?: webViewUa
            engineAccount = account.copy(userAgent = resolvedUserAgent)
            if (resolvedUserAgent == webViewUa) {
                onLog(
                    LogEntry(
                        username = account.username,
                        component = "NETWORK",
                        message = "USER_AGENT_DEFAULT_MIGRATED accountId=${account.id} source=WEBVIEW_DEFAULT uaLength=${webViewUa.length}"
                    )
                )
            } else {
                onLog(
                    LogEntry(
                        username = account.username,
                        component = "NETWORK",
                        message = "USER_AGENT_MIGRATION_SKIPPED accountId=${account.id} reason=EXPLICIT_STORED_VALUE_PRESERVED"
                    )
                )
            }
        }
        val storedHttpUa = engineAccount.getSafeUserAgent()
        onLog(
            LogEntry(
                username = account.username,
                component = "NETWORK",
                message = "WEBVIEW_UA=$webViewUa STORED_HTTP_UA=$storedHttpUa UA_MATCH=${webViewUa == storedHttpUa}"
            )
        )

        val engine = getOrCreateEngine(engineAccount)
        onLog(LogEntry(username = account.username, component = "ENGINE", message = "USE engineId=${engine.hashCode()}"))

        val engineHeartbeatJob = CoroutineScope(coroutineContext + Dispatchers.IO).launch {
            while (isActive) {
                delay(15_000L)
                // This coroutine is a child of the account's Job, so an uncaught exception here
                // (for example reading webView.url after the WebView was destroyed) would cancel
                // the whole account run. Diagnostics must never do that.
                try {
                    val (url, isAttached, chapterContext) = withContext(Dispatchers.Main.immediate) {
                        Triple(
                            runtime.webView.url ?: "UNKNOWN",
                            runtime.webView.isAttachedToWindow,
                            engine.getHeartbeatChapterContext()
                        )
                    }
                    val urlChapter = url.substringBefore('?')
                        .trimEnd('/')
                        .substringAfterLast('/')
                        .takeIf { it.toIntOrNull() != null }
                        .orEmpty()
                    val chapterMatchesUrl = urlChapter.isNotBlank() && urlChapter == chapterContext.second
                    val heartbeatChapterId = if (chapterMatchesUrl) chapterContext.first.ifBlank { "PENDING" } else "PENDING"
                    val heartbeatChapter = urlChapter
                        .ifBlank { if (chapterMatchesUrl) chapterContext.second else "UNKNOWN" }
                    onLog(
                        LogEntry(
                            username = account.username,
                            component = "ENGINE",
                            message = "HEARTBEAT accountId=${account.id} engineId=${engine.hashCode()} " +
                                "url=$url attached=$isAttached manga=${engine.getCurrentMangaUrl()} " +
                                "chapterId=$heartbeatChapterId chapter=$heartbeatChapter " +
                                "chapterContextMatchesUrl=$chapterMatchesUrl"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLog(
                        LogEntry(
                            username = account.username,
                            component = "ENGINE",
                            message = "HEARTBEAT_ERROR accountId=${account.id} error=${e.message}",
                            isError = true
                        )
                    )
                }
            }
        }

        val currentJob = coroutineContext[Job]
        val runStillCurrent = synchronized(this) {
            if (
                currentJob == null ||
                !currentJob.isActive ||
                runtimes[account.id] !== runtime ||
                automationEngines[account.id] !== engine
            ) {
                false
            } else {
                executionJobs[account.id] = currentJob
                runtime.state = AccountState.RUNNING
                true
            }
        }
        if (!runStillCurrent) {
            engineHeartbeatJob.cancel()
            throw CancellationException("STALE_ACCOUNT_RUNTIME accountId=${account.id}")
        }

        try {
            engine.runAccountTasks(
                account = engineAccount,
                settings = settings,
                taskType = taskType,
                webView = runtime.webView,
                promoCode = promoCode,
                resumeFromCheckpoint = resumePersistedProgress
            )
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
            withContext(NonCancellable) {
                engineHeartbeatJob.cancel()
                engineHeartbeatJob.join()
            }
        }
    }

    fun stopAll() {
        val ids = synchronized(this) {
            (runtimes.keys + automationEngines.keys + executionJobs.keys + failedStops).toSet()
        }
        ids.forEach { stopAccount(it) }
    }

    suspend fun stopAllAndAwait() {
        val ids = synchronized(this) {
            (runtimes.keys + automationEngines.keys + executionJobs.keys + failedStops).toSet()
        }
        ids.forEach { stopAccountAndAwait(it) }
        // Do not release untracked WebViews here: a prior timed-out run may still own network work.
    }

    @Synchronized
    fun closeAllWebViews() { stopAll() }

    suspend fun closeAllWebViewsAndAwait() { stopAllAndAwait() }

    private companion object {
        const val STOP_CLEANUP_TIMEOUT_MS = StopCleanupBarrier.DEFAULT_TIMEOUT_MS
    }
}
