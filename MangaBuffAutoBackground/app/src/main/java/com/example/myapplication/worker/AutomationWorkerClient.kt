package com.example.myapplication.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.webkit.WebViewFeature
import com.example.myapplication.automation.DeckCommentToggleRegistry
import com.example.myapplication.automation.IncompleteTaskSequenceException
import com.example.myapplication.automation.latestProfileCookieHeader
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.TaskType
import com.example.myapplication.network.AppProxyConfig
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Рабочий процесс завершился во время выполнения аккаунта. */
class WorkerDiedException(message: String) : IllegalStateException(message)

/**
 * Клиент рабочих процессов WebView (основной процесс). Каждому аккаунту выдаётся процесс
 * с подходящим прокси-маршрутом; аккаунты с разными маршрутами работают одновременно.
 */
internal class AutomationWorkerClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLog: (LogEntry) -> Unit,
    private val onStatus: (accountId: String, status: String, running: Boolean, task: String, progress: Float) -> Unit,
    private val onManga: (accountId: String, url: String, title: String) -> Unit,
    private val onStats: (accountId: String, diamonds: String, cardDrop: String, chapters: String, comments: String, candies: String) -> Unit,
    private val onDaily: (accountId: String, stats: DailyStats) -> Unit,
    private val onTower: (accountId: String, endsAt: Long) -> Unit,
    private val onMine: (accountId: String, ore: Int) -> Unit,
    private val onRecovery: (accountId: String) -> Unit
) {
    companion object {
        private val serviceClasses: List<Class<*>> = listOf(
            AutomationWorker0::class.java,
            AutomationWorker1::class.java,
            AutomationWorker2::class.java,
            AutomationWorker3::class.java
        )

        /** Нужны: API 28+, отдельный каталог данных WebView на процесс, профили и прокси. */
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 28 &&
                WebViewFeature.isStartupFeatureSupported(
                    context,
                    WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX
                ) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }

    private class Pending(
        val requestId: String,
        val accountId: String,
        val worker: Int,
        val runId: String,
        val profileId: String,
        val containerId: String,
        val routeId: String,
        val lease: WorkerSlotPool.Lease,
        val result: CompletableDeferred<Bundle>,
        val stopped: CompletableDeferred<Bundle>
    )
    private class Conn(val index: Int) {
        @Volatile var messenger: Messenger? = null
        var connected: CompletableDeferred<Messenger>? = null
        var bound = false
        lateinit var connection: ServiceConnection
    }

    private val gson = Gson()
    val pool = WorkerSlotPool(WorkerProtocol.WORKER_COUNT)
    private val conns = List(WorkerProtocol.WORKER_COUNT) { Conn(it) }
    private val pending = ConcurrentHashMap<String, Pending>()
    private val activeWorkerByAccount = ConcurrentHashMap<String, Int>()
    private val activePendingByAccount = ConcurrentHashMap<String, Pending>()
    private val stopAckTimeoutMs = 10_000L
    private val replyHandler = Handler(Looper.getMainLooper()) { msg ->
        handleEvent(msg)
        true
    }
    private val reply = Messenger(replyHandler)

    init {
        conns.forEach { conn ->
            conn.connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val m = Messenger(service)
                    conn.messenger = m
                    conn.connected?.complete(m)
                    conn.connected = null
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    conn.messenger = null
                    failPending(conn.index, "WORKER_PROCESS_DIED worker=${conn.index}")
                }

                override fun onBindingDied(name: ComponentName?) {
                    conn.messenger = null
                    failPending(conn.index, "WORKER_BINDING_DIED worker=${conn.index}")
                }
            }
        }
    }

    private fun failPending(worker: Int, reason: String) {
        pending.values.filter { it.worker == worker }.forEach { p ->
            onLog(LogEntry(username = p.accountId, component = "WORKER", message = reason, isError = true))
            onRecovery(p.accountId)
            p.result.completeExceptionally(WorkerDiedException(reason))
            p.stopped.completeExceptionally(WorkerDiedException(reason))
        }
    }

    private suspend fun messengerOf(index: Int): Messenger {
        val conn = conns[index]
        conn.messenger?.let { return it }
        val waiting = conn.connected ?: CompletableDeferred<Messenger>().also { conn.connected = it }
        if (!conn.bound) {
            val intent = Intent(context, serviceClasses[index])
            conn.bound = context.bindService(intent, conn.connection, Context.BIND_AUTO_CREATE)
            if (!conn.bound) throw IllegalStateException("Не удалось запустить рабочий процесс ${index}")
        }
        return withTimeout(20_000L) { waiting.await() }
    }

    private fun send(index: Int, what: Int, fill: Bundle.() -> Unit): Boolean {
        val target = conns[index].messenger ?: return false
        val message = Message.obtain(null, what)
        message.replyTo = reply
        message.data = Bundle().apply(fill)
        return try {
            target.send(message)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    /** Run succeeds or fails only after a matching worker stop acknowledgement is received. */
    suspend fun runForAccount(
        lease: WorkerSlotPool.Lease,
        account: MangaBuffAccount,
        settings: GlobalSettings,
        taskType: TaskType,
        promoCode: String,
        resume: Boolean,
        proxy: AppProxyConfig
    ) {
        val index = lease.index
        val messenger = messengerOf(index)
        val dispatchAccount = account.copy(
            cookiesJson = latestProfileCookieHeader(account.id, account.getSafeCookiesJson())
        )
        val reqId = UUID.randomUUID().toString()
        val entry = Pending(
            requestId = reqId,
            accountId = account.id,
            worker = index,
            runId = lease.runId,
            profileId = lease.profileId,
            containerId = lease.containerId,
            routeId = lease.route,
            lease = lease,
            result = CompletableDeferred(),
            stopped = CompletableDeferred()
        )
        if (activePendingByAccount.putIfAbsent(account.id, entry) != null) {
            lease.quarantine("ACCOUNT_ALREADY_ACTIVE_IN_WORKER_CLIENT")
            throw WorkerDiedException("ACCOUNT_ALREADY_ACTIVE_IN_WORKER_CLIENT")
        }
        pending[reqId] = entry
        activeWorkerByAccount[account.id] = index

        val deckJob: Job = scope.launch {
            DeckCommentToggleRegistry.stateFor(account.id, account.deckCommentEnabled).collect { snapshot ->
                send(index, WorkerProtocol.CMD_DECK_TOGGLE) {
                    putString(WorkerProtocol.K_ACCOUNT_ID, account.id)
                    putBoolean(WorkerProtocol.K_BOOL, snapshot.enabled)
                }
            }
        }
        onLog(
            LogEntry(
                username = account.username,
                component = "WORKER",
                message = "RUN_DISPATCHED accountId=${account.id} worker=$index route=${lease.route} runId=${lease.runId} task=${taskType.title}"
            )
        )

        try {
            val message = Message.obtain(null, WorkerProtocol.CMD_RUN)
            message.replyTo = reply
            message.data = Bundle().apply {
                putRunIdentity(entry)
                putString(WorkerProtocol.K_ACCOUNT_JSON, gson.toJson(dispatchAccount))
                putString(WorkerProtocol.K_SETTINGS_JSON, gson.toJson(settings))
                putString(WorkerProtocol.K_PROXY_JSON, gson.toJson(proxy))
                putString(WorkerProtocol.K_TASK, taskType.name)
                putString(WorkerProtocol.K_PROMO, promoCode)
                putBoolean(WorkerProtocol.K_RESUME, resume)
            }
            try {
                messenger.send(message)
            } catch (_: RemoteException) {
                lease.quarantine("WORKER_SEND_FAILED")
                throw WorkerDiedException("WORKER_SEND_FAILED worker=$index")
            }

            val result = entry.result.await()
            val errorClass = result.getString(WorkerProtocol.K_ERR_CLASS).orEmpty()
            if (errorClass in setOf("WORKER_BUSY", "WORKER_QUARANTINED", "RUN_IDENTITY_MISMATCH")) {
                lease.quarantine(errorClass)
                throw WorkerDiedException("$errorClass worker=$index")
            }
            val stopped = withTimeout(stopAckTimeoutMs) { entry.stopped.await() }
            if (!stopped.getBoolean(WorkerProtocol.K_STOP_CONFIRMED)) {
                lease.quarantine("STOP_NOT_CONFIRMED")
                throw WorkerDiedException("STOP_NOT_CONFIRMED worker=$index")
            }
            lease.markVerifiedClosed()

            if (!result.getBoolean(WorkerProtocol.K_OK)) {
                val msg = result.getString(WorkerProtocol.K_ERR_MSG).orEmpty()
                when (errorClass) {
                    "CancellationException" -> throw CancellationException(msg)
                    "IncompleteTaskSequenceException" -> throw IncompleteTaskSequenceException(msg)
                    else -> throw IllegalStateException(msg.ifBlank { errorClass })
                }
            }
        } catch (e: CancellationException) {
            var stopFailure: String? = null
            withContext(NonCancellable) {
                val sent = send(index, WorkerProtocol.CMD_STOP_ACCOUNT) { putRunIdentity(entry) }
                if (!sent) {
                    stopFailure = "STOP_COMMAND_FAILED"
                } else {
                    try {
                        val stopped = withTimeout(stopAckTimeoutMs) { entry.stopped.await() }
                        if (!stopped.getBoolean(WorkerProtocol.K_STOP_CONFIRMED)) {
                            stopFailure = "STOP_NOT_CONFIRMED"
                        } else {
                            lease.markVerifiedClosed()
                        }
                    } catch (_: Exception) {
                        stopFailure = "STOP_ACK_TIMEOUT_OR_IPC_LOST"
                    }
                }
                if (stopFailure != null) lease.quarantine(stopFailure!!)
            }
            if (stopFailure != null) throw WorkerDiedException("${stopFailure!!} worker=$index; slot quarantined")
            throw e
        } finally {
            deckJob.cancel()
            pending.remove(reqId, entry)
            activePendingByAccount.remove(account.id, entry)
            activeWorkerByAccount.remove(account.id, index)
        }
    }

    fun isRunningInWorker(accountId: String): Boolean = activeWorkerByAccount.containsKey(accountId)

    fun stopAccount(accountId: String) {
        val entry = activePendingByAccount[accountId] ?: return
        send(entry.worker, WorkerProtocol.CMD_STOP_ACCOUNT) { putRunIdentity(entry) }
    }

    fun skipCurrentManga(accountId: String): Boolean {
        val index = activeWorkerByAccount[accountId] ?: return false
        return send(index, WorkerProtocol.CMD_SKIP_MANGA) { putString(WorkerProtocol.K_ACCOUNT_ID, accountId) }
    }

    fun markCurrentMangaAsRead(accountId: String): Boolean {
        val index = activeWorkerByAccount[accountId] ?: return false
        return send(index, WorkerProtocol.CMD_MARK_READ) { putString(WorkerProtocol.K_ACCOUNT_ID, accountId) }
    }

    fun reloadAccount(account: MangaBuffAccount): Boolean {
        val index = activeWorkerByAccount[account.id] ?: return false
        return send(index, WorkerProtocol.CMD_RELOAD) { putString(WorkerProtocol.K_ACCOUNT_JSON, gson.toJson(account)) }
    }

    fun stopAll() {
        conns.forEach { c -> if (c.messenger != null) send(c.index, WorkerProtocol.CMD_STOP_ALL) {} }
    }

    /** Отключает рабочие процессы (при остановке сервиса). */
    fun shutdown() {
        stopAll()
        conns.forEach { c ->
            if (c.bound) runCatching { context.unbindService(c.connection) }
            c.bound = false
            c.messenger = null
        }
    }

    private fun Bundle.putRunIdentity(entry: Pending) {
        putString(WorkerProtocol.K_REQ, entry.requestId)
        putString(WorkerProtocol.K_ACCOUNT_ID, entry.accountId)
        putString(WorkerProtocol.K_RUN_ID, entry.runId)
        putString(WorkerProtocol.K_PROFILE_ID, entry.profileId)
        putString(WorkerProtocol.K_CONTAINER_ID, entry.containerId)
        putString(WorkerProtocol.K_ROUTE_ID, entry.routeId)
    }

    private fun runIdentity(entry: Pending) = WorkerRunIdentity(
        entry.requestId, entry.accountId, entry.runId, entry.profileId, entry.containerId, entry.routeId
    )

    private fun runIdentity(data: Bundle) = WorkerRunIdentity(
        data.getString(WorkerProtocol.K_REQ).orEmpty(),
        data.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty(),
        data.getString(WorkerProtocol.K_RUN_ID).orEmpty(),
        data.getString(WorkerProtocol.K_PROFILE_ID).orEmpty(),
        data.getString(WorkerProtocol.K_CONTAINER_ID).orEmpty(),
        data.getString(WorkerProtocol.K_ROUTE_ID).orEmpty()
    )

    private fun identityMatches(entry: Pending, data: Bundle): Boolean = runIdentity(entry) == runIdentity(data)

    private fun handleEvent(msg: Message) {
        val d = msg.data
        val id = d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty()
        when (msg.what) {
            WorkerProtocol.EV_LOG -> {
                val eventRunId = d.getString(WorkerProtocol.K_RUN_ID).orEmpty()
                if (eventRunId.isNotBlank() && pending.values.none { it.runId == eventRunId }) return
                gson.fromJson(d.getString(WorkerProtocol.K_JSON), LogEntry::class.java)?.let(onLog)
            }
            WorkerProtocol.EV_STATE -> {
                val entry = pending[d.getString(WorkerProtocol.K_REQ).orEmpty()]
                val next = runCatching {
                    WorkerSlotState.valueOf(d.getString(WorkerProtocol.K_STATE).orEmpty())
                }.getOrNull()
                if (entry == null || !identityMatches(entry, d) || next == null) {
                    onLog(LogEntry(username = id, component = "SECURITY", message = "STALE_OR_INVALID_EV_STATE_IGNORED", isError = true))
                } else {
                    try {
                        if (next == WorkerSlotState.QUARANTINED) {
                            entry.lease.quarantine("WORKER_QUARANTINED_STATE")
                        } else if (next == WorkerSlotState.VERIFIED_CLOSED) {
                            entry.lease.markVerifiedClosed()
                        } else if (next != WorkerSlotState.FREE) {
                            entry.lease.transition(next)
                        }
                    } catch (_: Exception) {
                        entry.lease.quarantine("INVALID_WORKER_STATE_TRANSITION")
                        onLog(LogEntry(username = id, component = "SECURITY", message = "INVALID_WORKER_STATE_TRANSITION state=${next.name}", isError = true))
                    }
                }
            }
            WorkerProtocol.EV_STOPPED -> {
                val entry = pending[d.getString(WorkerProtocol.K_REQ).orEmpty()]
                if (entry == null || !WorkerStopConfirmation.accepts(
                        runIdentity(entry), runIdentity(d), d.getBoolean(WorkerProtocol.K_STOP_CONFIRMED)
                    )
                ) {
                    onLog(LogEntry(username = id, component = "SECURITY", message = "STALE_OR_INVALID_EV_STOPPED_IGNORED", isError = true))
                } else {
                    // EV_STOPPED is only phase one: worker keeps its lease in VERIFIED_CLOSED
                    // until this matching receipt arrives. If ACK IPC is lost, neither side reuses it.
                    val ackSent = send(entry.worker, WorkerProtocol.CMD_ACK_STOP) { putRunIdentity(entry) }
                    if (!ackSent) {
                        entry.lease.quarantine("STOP_RECEIPT_ACK_SEND_FAILED")
                        entry.stopped.completeExceptionally(
                            WorkerDiedException("STOP_RECEIPT_ACK_SEND_FAILED worker=${entry.worker}")
                        )
                    } else {
                        entry.stopped.complete(d)
                    }
                }
            }
            WorkerProtocol.EV_RESULT -> {
                val entry = pending[d.getString(WorkerProtocol.K_REQ).orEmpty()]
                if (entry == null || !identityMatches(entry, d)) {
                    onLog(LogEntry(username = id, component = "SECURITY", message = "STALE_EV_RESULT_IGNORED", isError = true))
                } else {
                    entry.result.complete(d)
                }
            }
            else -> {
                val entry = activePendingByAccount[id]
                if (entry == null || !identityMatches(entry, d)) {
                    onLog(LogEntry(username = id, component = "SECURITY", message = "STALE_WORKER_EVENT_IGNORED event=${msg.what}", isError = true))
                    return
                }
                when (msg.what) {
                    WorkerProtocol.EV_STATUS -> onStatus(
                        id, d.getString(WorkerProtocol.K_STR1).orEmpty(), d.getBoolean(WorkerProtocol.K_BOOL),
                        d.getString(WorkerProtocol.K_STR2).orEmpty(), d.getFloat(WorkerProtocol.K_FLOAT)
                    )
                    WorkerProtocol.EV_MANGA -> onManga(
                        id, d.getString(WorkerProtocol.K_STR1).orEmpty(), d.getString(WorkerProtocol.K_STR2).orEmpty()
                    )
                    WorkerProtocol.EV_STATS -> onStats(
                        id, d.getString(WorkerProtocol.K_STR1).orEmpty(), d.getString(WorkerProtocol.K_STR2).orEmpty(),
                        d.getString(WorkerProtocol.K_STR3).orEmpty(), d.getString(WorkerProtocol.K_STR4).orEmpty(),
                        d.getString(WorkerProtocol.K_STR5).orEmpty()
                    )
                    WorkerProtocol.EV_DAILY ->
                        gson.fromJson(d.getString(WorkerProtocol.K_JSON), DailyStats::class.java)?.let { onDaily(id, it) }
                    WorkerProtocol.EV_TOWER -> onTower(id, d.getLong(WorkerProtocol.K_LONG))
                    WorkerProtocol.EV_MINE -> onMine(id, d.getInt(WorkerProtocol.K_INT))
                    WorkerProtocol.EV_RECOVERY -> onRecovery(id)
                }
            }
        }
    }
}
