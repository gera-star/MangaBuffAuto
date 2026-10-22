package com.example.myapplication.worker

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var screenRegistered = false

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

    private val runner: MultiAccountAutomationRunner by lazy {
        MultiAccountAutomationRunner(
            context = applicationContext,
            onLog = { entry -> emit(WorkerProtocol.EV_LOG) { putString(WorkerProtocol.K_JSON, gson.toJson(trim(entry))) } },
            onAccountStatusUpdate = { id, status, running, task, progress ->
                emit(WorkerProtocol.EV_STATUS) {
                    putString(WorkerProtocol.K_ACCOUNT_ID, id)
                    putString(WorkerProtocol.K_STR1, status.take(WorkerProtocol.MAX_TEXT))
                    putBoolean(WorkerProtocol.K_BOOL, running)
                    putString(WorkerProtocol.K_STR2, task)
                    putFloat(WorkerProtocol.K_FLOAT, progress)
                }
            },
            onMangaActiveUrlUpdate = { id, url, title ->
                emit(WorkerProtocol.EV_MANGA) {
                    putString(WorkerProtocol.K_ACCOUNT_ID, id)
                    putString(WorkerProtocol.K_STR1, url)
                    putString(WorkerProtocol.K_STR2, title)
                }
            },
            onAccountStatsUpdate = { id, diamonds, cardDrop, chapters, comments, candies ->
                emit(WorkerProtocol.EV_STATS) {
                    putString(WorkerProtocol.K_ACCOUNT_ID, id)
                    putString(WorkerProtocol.K_STR1, diamonds)
                    putString(WorkerProtocol.K_STR2, cardDrop)
                    putString(WorkerProtocol.K_STR3, chapters)
                    putString(WorkerProtocol.K_STR4, comments)
                    putString(WorkerProtocol.K_STR5, candies)
                }
            },
            onDailyStatsUpdate = { id, stats ->
                emit(WorkerProtocol.EV_DAILY) {
                    putString(WorkerProtocol.K_ACCOUNT_ID, id)
                    putString(WorkerProtocol.K_JSON, gson.toJson(stats))
                }
            },
            onTowerExpeditionEndsAtUpdate = { id, endsAt ->
                emit(WorkerProtocol.EV_TOWER) {
                    putString(WorkerProtocol.K_ACCOUNT_ID, id)
                    putLong(WorkerProtocol.K_LONG, endsAt)
                }
            },
            onWebViewAssigned = { _, _ -> },
            onWebViewCleared = { _, _ -> },
            onAccountRecoveryNeeded = { id ->
                emit(WorkerProtocol.EV_RECOVERY) { putString(WorkerProtocol.K_ACCOUNT_ID, id) }
            },
            onMineBalanceUpdate = { id, ore ->
                emit(WorkerProtocol.EV_MINE) {
                    putString(WorkerProtocol.K_ACCOUNT_ID, id)
                    putInt(WorkerProtocol.K_INT, ore)
                }
            },
            // Рабочий не пишет аккаунты на диск (см. описание класса).
            persistUserAgentMigration = false
        )
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
        runJobs.values.forEach { it.cancel() }
        runJobs.clear()
        runCatching { runner.stopAll("WORKER_UNBOUND") }
    }

    private fun handleCommand(msg: Message) {
        val d = msg.data
        when (msg.what) {
            WorkerProtocol.CMD_RUN -> startRun(d)
            WorkerProtocol.CMD_STOP_ACCOUNT -> {
                val id = d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty()
                runJobs.remove(id)?.cancel()
                runner.stopAccount(id)
            }
            WorkerProtocol.CMD_STOP_ALL -> stopEverything()
            WorkerProtocol.CMD_SKIP_MANGA -> runner.skipCurrentManga(d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty())
            WorkerProtocol.CMD_MARK_READ -> runner.markCurrentMangaAsRead(d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty())
            WorkerProtocol.CMD_DECK_TOGGLE ->
                DeckCommentToggleRegistry.setEnabled(
                    d.getString(WorkerProtocol.K_ACCOUNT_ID).orEmpty(),
                    d.getBoolean(WorkerProtocol.K_BOOL)
                )
            WorkerProtocol.CMD_RELOAD ->
                gson.fromJson(d.getString(WorkerProtocol.K_ACCOUNT_JSON), MangaBuffAccount::class.java)
                    ?.let { runner.reloadAccount(it) }
        }
    }

    private fun startRun(d: Bundle) {
        val reqId = d.getString(WorkerProtocol.K_REQ).orEmpty()
        val account = gson.fromJson(d.getString(WorkerProtocol.K_ACCOUNT_JSON), MangaBuffAccount::class.java)
        val settings = gson.fromJson(d.getString(WorkerProtocol.K_SETTINGS_JSON), GlobalSettings::class.java)
        val proxy = gson.fromJson(d.getString(WorkerProtocol.K_PROXY_JSON), AppProxyConfig::class.java) ?: AppProxyConfig()
        val taskType = TaskType.valueOf(d.getString(WorkerProtocol.K_TASK).orEmpty())
        val promo = d.getString(WorkerProtocol.K_PROMO).orEmpty()
        val resume = d.getBoolean(WorkerProtocol.K_RESUME)

        // SharedPreferences этого процесса могут быть устаревшими: маршрут берём из сообщения.
        AppProxySettings.setProcessOverride(account.id, proxy)

        runJobs.remove(account.id)?.cancel()
        val job = scope.launch {
            var ok = false
            var errClass = ""
            var errMsg = ""
            try {
                ensureRoute(proxy)
                DeckCommentToggleRegistry.initializeIfAbsent(account.id, account.deckCommentEnabled)
                runner.runForAccount(account, settings, taskType, promo, resume)
                ok = true
            } catch (e: CancellationException) {
                errClass = "CancellationException"
                errMsg = e.message.orEmpty()
                throw e
            } catch (e: Exception) {
                errClass = e.javaClass.simpleName
                errMsg = e.message.orEmpty()
            } finally {
                emit(WorkerProtocol.EV_RESULT) {
                    putString(WorkerProtocol.K_REQ, reqId)
                    putString(WorkerProtocol.K_ACCOUNT_ID, account.id)
                    putBoolean(WorkerProtocol.K_OK, ok)
                    putString(WorkerProtocol.K_ERR_CLASS, errClass)
                    putString(WorkerProtocol.K_ERR_MSG, errMsg.take(2_000))
                }
            }
        }
        runJobs[account.id] = job
        job.invokeOnCompletion { runJobs.remove(account.id, job) }
    }

    /**
     * Применяет прокси-override процесса. Fail-closed: если прокси включён и применить его не
     * удалось, выбрасываем исключение — страницы не загружаются, реальный IP не раскрывается.
     * Пул основного процесса гарантирует, что смена маршрута происходит, когда в процессе нет
     * активных аккаунтов.
     */
    private suspend fun ensureRoute(proxy: AppProxyConfig) {
        val key = if (proxy.isEnabledAndValid()) "proxy:${proxy.host.trim().lowercase()}:${proxy.port}" else "system"
        routeMutex.withLock {
            if (appliedRouteKey == key) return
            suspendCancellableCoroutine<Unit> { cont ->
                AppProxySettings.applyWebViewProxy(applicationContext, proxy) { success, message ->
                    if (!cont.isActive) return@applyWebViewProxy
                    if (success) cont.resume(Unit)
                    else cont.resumeWithException(IllegalStateException("PROXY_APPLY_FAILED: $message"))
                }
            }
            appliedRouteKey = key
        }
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
