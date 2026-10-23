package com.example.myapplication.worker

import android.app.Service
import com.example.myapplication.ContainerApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import androidx.core.content.ContextCompat
import com.example.myapplication.automation.BackgroundExecutionState
import com.example.myapplication.automation.DeckCommentToggleRegistry
import com.example.myapplication.automation.MultiAccountAutomationRunner
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.TaskType
import com.example.myapplication.network.AppProxyConfig
import com.example.myapplication.network.AppProxySettings
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Рабочий процесс WebView (:wN). Здесь живут WebView, профили и автоматизация аккаунтов,
 * которым назначен этот процесс. Прокси-override WebView действует на весь процесс,
 * поэтому разные маршруты работают одновременно в разных рабочих процессах.
 *
 * Состояние аккаунтов хранит основной процесс: рабочий получает аккаунт и настройки в сообщении
 * и отправляет обратно только события (логи, статусы, статистику). Рабочий НИКОГДА не пишет
 * список аккаунтов на диск, иначе его устаревшая копия затёрла бы данные основного процесса.
 */
open class AutomationWorkerService : Service() {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runJobs = ConcurrentHashMap<String, Job>()
    private val routeMutex = Mutex()
    private var appliedRouteKey: String? = null
    @Volatile private var activeRun: ActiveRun? = null
    @Volatile private var activeRunner: MultiAccountAutomationRunner? = null
    @Volatile private var poisoned = false
    @Volatile private var routeStateUncertain = false
    @Volatile private var lifecycleState = WorkerSlotState.FREE
    private var isolationReady = false
    private var screenRegistered = false

    private data class ActiveRun(
        val requestId: String,
        val accountId: String,
        val profileId: String,
        val containerId: String,
        val routeId: String,
        val runId: String
    )

    private fun processContainerId(): String =
        if (Build.VERSION.SDK_INT >= 28) {
            android.app.Application.getProcessName().substringAfterLast(':')
        } else {
            "unknown"
        }

    @Volatile
    private var client: Messenger? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> BackgroundExecutionState.setScreenOff(true)
                Intent.ACTION_SCREEN_ON -> BackgroundExecutionState.setScreenOff(false)
            }
        }
    }

    private fun emitRunEvent(
        run: ActiveRun,
        what: Int,
        eventAccountId: String,
        fill: Bundle.() -> Unit = {}
    ) {
        emit(what) {
            putString(WorkerProtocol.K_REQ, run.requestId)
            putString(WorkerProtocol.K_ACCOUNT_ID, eventAccountId)
            putString(WorkerProtocol.K_RUN_ID, run.runId)
            putString(WorkerProtocol.K_PROFILE_ID, run.profileId)
            putString(WorkerProtocol.K_CONTAINER_ID, run.containerId)
            putString(WorkerProtocol.K_ROUTE_ID, run.routeId)
            fill()
        }
    }

    private fun createRunner(run: ActiveRun): MultiAccountAutomationRunner =
        MultiAccountAutomationRunner(
            context = applicationContext,
            onLog = { entry ->
                emit(WorkerProtocol.EV_LOG) {
                    putString(WorkerProtocol.K_REQ, run.requestId)
                    putString(WorkerProtocol.K_RUN_ID, run.runId)
                    putString(WorkerProtocol.K_PROFILE_ID, run.profileId)
                    putString(WorkerProtocol.K_CONTAINER_ID, run.containerId)
                    putString(WorkerProtocol.K_ROUTE_ID, run.routeId)
                    putString(WorkerProtocol.K_JSON, gson.toJson(trim(entry)))
                }
            },
            onAccountStatusUpdate = { id, status, running, task, progress ->
                emitRunEvent(run, WorkerProtocol.EV_STATUS, id) {
                    putString(WorkerProtocol.K_STR1, status.take(WorkerProtocol.MAX_TEXT))
                    putBoolean(WorkerProtocol.K_BOOL, running)
                    putString(WorkerProtocol.K_STR2, task)
                    putFloat(WorkerProtocol.K_FLOAT, progress)
                }
            },
            onMangaActiveUrlUpdate = { id, url, title ->
                emitRunEvent(run, WorkerProtocol.EV_MANGA, id) {
                    putString(WorkerProtocol.K_STR1, url)
                    putString(WorkerProtocol.K_STR2, title)
                }
            },
            onAccountStatsUpdate = { id, diamonds, cardDrop, chapters, comments, candies ->
                emitRunEvent(run, WorkerProtocol.EV_STATS, id) {
                    putString(WorkerProtocol.K_STR1, diamonds)
                    putString(WorkerProtocol.K_STR2, cardDrop)
                    putString(WorkerProtocol.K_STR3, chapters)
                    putString(WorkerProtocol.K_STR4, comments)
                    putString(WorkerProtocol.K_STR5, candies)
                }
            },
            onDailyStatsUpdate = { id, stats ->
                emitRunEvent(run, WorkerProtocol.EV_DAILY, id) {
                    putString(WorkerProtocol.K_JSON, gson.toJson(stats))
                }
            },
            onTowerExpeditionEndsAtUpdate = { id, endsAt ->
                emitRunEvent(run, WorkerProtocol.EV_TOWER, id) {
                    putLong(WorkerProtocol.K_LONG, endsAt)
                }
            },
            onWebViewAssigned = { _, _ -> },
            onWebViewCleared = { _, _ -> },
            onAccountRecoveryNeeded = { id ->
                emitRunEvent(run, WorkerProtocol.EV_RECOVERY, id)
            },
            onMineBalanceUpdate = { id, ore ->
                emitRunEvent(run, WorkerProtocol.EV_MINE, id) {
                    putInt(WorkerProtocol.K_INT, ore)
                }
            },
            // The main process remains the single owner of the account database.
            persistUserAgentMigration = false
        )

    private fun transitionState(run: ActiveRun, next: WorkerSlotState) {
        val current = lifecycleState
        if (current == next) return
        val allowed = when (current) {
            WorkerSlotState.FREE -> setOf(WorkerSlotState.RESERVED)
            WorkerSlotState.RESERVED -> setOf(WorkerSlotState.PROXY_READY, WorkerSlotState.STOPPING)
            WorkerSlotState.PROXY_READY -> setOf(WorkerSlotState.RUNNING, WorkerSlotState.STOPPING)
            WorkerSlotState.RUNNING -> setOf(WorkerSlotState.STOPPING)
            WorkerSlotState.STOPPING -> setOf(WorkerSlotState.VERIFIED_CLOSED)
            WorkerSlotState.VERIFIED_CLOSED, WorkerSlotState.QUARANTINED -> emptySet()
        }
        check(next in allowed) { "Invalid worker transition $current -> $next" }
        lifecycleState = next
        emitRunEvent(run, WorkerProtocol.EV_STATE, run.accountId) {
            putString(WorkerProtocol.K_STATE, next.name)
        }
    }

    private fun emitStopped(run: ActiveRun, reason: String) {
        emitRunEvent(run, WorkerProtocol.EV_STOPPED, run.accountId) {
            putBoolean(WorkerProtocol.K_STOP_CONFIRMED, true)
            putString(WorkerProtocol.K_STOP_REASON, reason)
        }
    }

    private fun emitResult(run: ActiveRun, success: Boolean, errorClass: String = "", errorMessage: String = "") {
        emitRunEvent(run, WorkerProtocol.EV_RESULT, run.accountId) {
            putBoolean(WorkerProtocol.K_OK, success)
            putString(WorkerProtocol.K_ERR_CLASS, errorClass)
            putString(WorkerProtocol.K_ERR_MSG, errorMessage.take(2_000))
        }
    }

    private fun runIdentityFromCommand(data: Bundle): ActiveRun = ActiveRun(
        requestId = data.getString(WorkerProtocol.K_REQ).orEmpty(),
        accountId = data.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty(),
        profileId = data.getString(WorkerProtocol.K_PROFILE_ID).orEmpty(),
        containerId = data.getString(WorkerProtocol.K_CONTAINER_ID).orEmpty(),
        routeId = data.getString(WorkerProtocol.K_ROUTE_ID).orEmpty(),
        runId = data.getString(WorkerProtocol.K_RUN_ID).orEmpty()
    )

    private fun emitStoppedForAlreadyClosedRun(data: Bundle) {
        val requested = runIdentityFromCommand(data)
        if (requested.requestId.isBlank() || requested.runId.isBlank() || poisoned) return
        emitStopped(requested, "ALREADY_CLOSED")
    }

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        msg.replyTo?.let { client = it }
        try {
            handleCommand(msg)
        } catch (e: Exception) {
            emit(WorkerProtocol.EV_LOG) {
                putString(
                    WorkerProtocol.K_JSON,
                    gson.toJson(LogEntry(component = "WORKER", message = "COMMAND_ERROR what=${msg.what} error=${e.message}", isError = true))
                )
            }
        }
        true
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        isolationReady = (application as? ContainerApp)?.isolatedWebViewProcessReady == true
        if (!isolationReady) poisoned = true
        val power = getSystemService(POWER_SERVICE) as PowerManager
        BackgroundExecutionState.setScreenOff(!power.isInteractive)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Основной процесс отключился (остановлен сервис): останавливаем всё, чтобы не оставлять
        // WebView с прокси без владельца.
        stopEverything()
        client = null
        return false
    }

    override fun onDestroy() {
        stopEverything()
        if (screenRegistered) runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun stopEverything() {
        runJobs.values.toList().forEach { it.cancel() }
        runCatching { activeRunner?.stopAll("WORKER_UNBOUND") }
    }

    private fun handleCommand(msg: Message) {
        val d = msg.data
        when (msg.what) {
            WorkerProtocol.CMD_RUN -> startRun(d)
            WorkerProtocol.CMD_STOP_ACCOUNT -> {
                val requested = runIdentityFromCommand(d)
                val current = activeRun
                when {
                    current == null && !poisoned -> emitStoppedForAlreadyClosedRun(d)
                    current?.accountId == requested.accountId && current.runId == requested.runId -> {
                        // The run's finally block closes its runtime before it sends EV_STOPPED.
                        runJobs[current.accountId]?.cancel()
                    }
                    else -> {
                        // A stale STOP must never stop a newer run. No ack means the requester
                        // will quarantine its lease rather than assume this process is clean.
                        emit(WorkerProtocol.EV_LOG) {
                            putString(WorkerProtocol.K_JSON, gson.toJson(
                                LogEntry(component = "WORKER", message = "STALE_STOP_IGNORED requestedRunId=${requested.runId}", isError = true)
                            ))
                        }
                    }
                }
            }
            WorkerProtocol.CMD_STOP_ALL -> stopEverything()
            WorkerProtocol.CMD_SKIP_MANGA -> activeRunner?.skipCurrentManga(d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty())
            WorkerProtocol.CMD_MARK_READ -> activeRunner?.markCurrentMangaAsRead(d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty())
            WorkerProtocol.CMD_DECK_TOGGLE -> DeckCommentToggleRegistry.setEnabled(
                d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty(), d.getBoolean(WorkerProtocol.K_BOOL)
            )
            WorkerProtocol.CMD_RELOAD -> gson.fromJson(
                d.getString(WorkerProtocol.K_ACCOUNT_JSON), MangaBuffAccount::class.java
            )?.let { activeRunner?.reloadAccount(it) }
        }
    }

    private fun startRun(d: Bundle) {
        val reqId = d.getString(WorkerProtocol.K_REQ).orEmpty()
        val runId = d.getString(WorkerProtocol.K_RUN_ID).orEmpty()
        val account = runCatching { gson.fromJson(d.getString(WorkerProtocol.K_ACCOUNT_JSON), MangaBuffAccount::class.java) }.getOrNull()
        val settings = runCatching { gson.fromJson(d.getString(WorkerProtocol.K_SETTINGS_JSON), GlobalSettings::class.java) }.getOrNull()
        val proxy = runCatching { gson.fromJson(d.getString(WorkerProtocol.K_PROXY_JSON), AppProxyConfig::class.java) }.getOrNull() ?: AppProxyConfig()
        val taskType = runCatching { TaskType.valueOf(d.getString(WorkerProtocol.K_TASK).orEmpty()) }.getOrNull()
        if (reqId.isBlank() || runId.isBlank() || account == null || settings == null || taskType == null) {
            emit(WorkerProtocol.EV_RESULT) {
                putString(WorkerProtocol.K_REQ, reqId)
                putString(WorkerProtocol.K_RUN_ID, runId)
                putString(WorkerProtocol.K_ACCOUNT_ID, account?.id.orEmpty())
                putBoolean(WorkerProtocol.K_OK, false)
                putString(WorkerProtocol.K_ERR_CLASS, "INVALID_RUN_REQUEST")
                putString(WorkerProtocol.K_ERR_MSG, "Required run fields are missing or invalid.")
            }
            return
        }
        val incoming = ActiveRun(
            reqId, account.id,
            d.getString(WorkerProtocol.K_PROFILE_ID).orEmpty(),
            d.getString(WorkerProtocol.K_CONTAINER_ID).orEmpty(),
            d.getString(WorkerProtocol.K_ROUTE_ID).orEmpty(),
            runId
        )
        if (!isolationReady || poisoned || routeStateUncertain) {
            poisoned = true
            emitResult(incoming, false, "WORKER_QUARANTINED", "Isolation or proxy state is not safe for reuse.")
            return
        }
        if (incoming.profileId != "mb_${account.id}" ||
            incoming.containerId != processContainerId() || incoming.routeId.isBlank()
        ) {
            emitResult(incoming, false, "RUN_IDENTITY_MISMATCH", "Worker identity did not match the reserved lease.")
            return
        }
        val current = activeRun
        if (current != null || lifecycleState != WorkerSlotState.FREE) {
            if (current != null && current.requestId == reqId && current.runId == runId) return // duplicate RUN is idempotent
            emitResult(incoming, false, "WORKER_BUSY", "Worker already owns another active run.")
            return
        }

        activeRun = incoming
        transitionState(incoming, WorkerSlotState.RESERVED)
        val promo = d.getString(WorkerProtocol.K_PROMO).orEmpty()
        val resume = d.getBoolean(WorkerProtocol.K_RESUME)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val runRunner = createRunner(incoming)
            activeRunner = runRunner
            var ok = false
            var errClass = ""
            var errMsg = ""
            var cleanupConfirmed = false
            try {
                proxy.validateForRouting()
                AppProxySettings.setProcessOverride(account.id, proxy)
                ensureRoute(proxy)
                transitionState(incoming, WorkerSlotState.PROXY_READY)
                DeckCommentToggleRegistry.initializeIfAbsent(account.id, account.deckCommentEnabled)
                transitionState(incoming, WorkerSlotState.RUNNING)
                runRunner.runForAccount(account, settings, taskType, promo, resume)
                ok = true
            } catch (e: CancellationException) {
                errClass = "CancellationException"
                errMsg = e.message.orEmpty()
                throw e
            } catch (e: Exception) {
                errClass = e.javaClass.simpleName
                errMsg = e.message.orEmpty()
            } finally {
                if (lifecycleState != WorkerSlotState.STOPPING && lifecycleState != WorkerSlotState.QUARANTINED) {
                    runCatching { transitionState(incoming, WorkerSlotState.STOPPING) }
                        .onFailure { poisoned = true }
                }
                try {
                    withContext(NonCancellable) {
                        runRunner.stopAccount(account.id)
                        runRunner.closeAllWebViews()
                    }
                    cleanupConfirmed = !poisoned && !routeStateUncertain
                } catch (e: Exception) {
                    poisoned = true
                    errClass = "STOP_CLEANUP_FAILED"
                    errMsg = e.javaClass.simpleName
                }
                if (cleanupConfirmed) {
                    transitionState(incoming, WorkerSlotState.VERIFIED_CLOSED)
                    emitStopped(incoming, if (ok) "RUN_FINISHED_AND_WEBVIEW_DESTROYED" else "RUN_FAILED_AND_WEBVIEW_DESTROYED")
                } else {
                    poisoned = true
                    emit(WorkerProtocol.EV_LOG) {
                        putString(WorkerProtocol.K_JSON, gson.toJson(
                            LogEntry(component = "WORKER", message = "STOP_NOT_CONFIRMED runId=$runId; worker quarantined", isError = true)
                        ))
                    }
                    errClass = "WORKER_QUARANTINED"
                    errMsg = "Runtime/WebView stop or process proxy state could not be confirmed."
                }
                emitResult(incoming, ok && cleanupConfirmed, errClass, errMsg)
                if (activeRunner === runRunner) activeRunner = null
                if (cleanupConfirmed) lifecycleState = WorkerSlotState.FREE
                if (activeRun?.runId == runId && activeRun?.requestId == reqId) activeRun = null
                runJobs.remove(account.id, job)
            }
        }
        runJobs[account.id] = job
        job.invokeOnCompletion {
            runJobs.remove(account.id, job)
            if (activeRun?.runId == runId && activeRun?.requestId == reqId && poisoned) activeRun = null
        }
        job.start()
    }

    /**
     * Применяет прокси-override процесса. Fail-closed: если прокси включён и применить его не
     * удалось, выбрасываем исключение — страницы не загружаются, реальный IP не раскрывается.
     * Пул основного процесса гарантирует, что смена маршрута происходит, когда в процессе нет
     * активных аккаунтов.
     */
    private suspend fun ensureRoute(proxy: AppProxyConfig) {
        proxy.validateForRouting()
        AppProxySettings.requireWebViewProxyStateCertain()
        val key = if (proxy.enabled) "proxy:${proxy.host.trim().lowercase(java.util.Locale.ROOT)}:${proxy.port}" else "system"
        routeMutex.withLock {
            if (appliedRouteKey == key) return
            try {
                withTimeout(PROXY_APPLY_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        cont.invokeOnCancellation {
                            routeStateUncertain = true
                            poisoned = true
                            AppProxySettings.markWebViewProxyStateUncertain()
                            appliedRouteKey = null
                        }
                        AppProxySettings.applyWebViewProxy(applicationContext, proxy) { success, message ->
                            if (!cont.isActive) return@applyWebViewProxy
                            if (success) {
                                cont.resume(Unit)
                            } else {
                                routeStateUncertain = true
                                poisoned = true
                                AppProxySettings.markWebViewProxyStateUncertain()
                                cont.resumeWithException(IllegalStateException("PROXY_APPLY_FAILED: $message"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                appliedRouteKey = null
                if (e is CancellationException) {
                    routeStateUncertain = true
                    poisoned = true
                    AppProxySettings.markWebViewProxyStateUncertain()
                }
                throw e
            }
            appliedRouteKey = key
        }
    }

    private companion object {
        const val PROXY_APPLY_TIMEOUT_MS = 15_000L
    }

    private fun trim(entry: LogEntry): LogEntry =
        if (entry.message.length > WorkerProtocol.MAX_TEXT) entry.copy(message = entry.message.take(WorkerProtocol.MAX_TEXT)) else entry

    private fun emit(what: Int, fill: Bundle.() -> Unit) {
        val target = client ?: return
        val message = Message.obtain(null, what)
        message.data = Bundle().apply(fill)
        try {
            target.send(message)
        } catch (_: RemoteException) {
        }
    }
}

class AutomationWorker0 : AutomationWorkerService()
class AutomationWorker1 : AutomationWorkerService()
class AutomationWorker2 : AutomationWorkerService()
class AutomationWorker3 : AutomationWorkerService()
