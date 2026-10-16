package com.example.myapplication.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.automation.BackgroundExecutionState
import com.example.myapplication.automation.AccountExecutionGate
import com.example.myapplication.automation.MultiAccountAutomationRunner
import com.example.myapplication.automation.AutomationWebViewRegistry
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

class MangaBuffForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "mangabuff_bot_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_FOREGROUND"
        const val ACTION_STOP = "ACTION_STOP_FOREGROUND"
        const val ACTION_UPDATE = "ACTION_UPDATE_STATUS"

        const val ACTION_START_ACCOUNT = "ACTION_START_ACCOUNT"
        const val ACTION_START_ALL = "ACTION_START_ALL"
        const val ACTION_STOP_ACCOUNT = "ACTION_STOP_ACCOUNT"
        const val ACTION_DELETE_ACCOUNT = "ACTION_DELETE_ACCOUNT"
        const val ACTION_TRADE_MONITOR_START = "ACTION_TRADE_MONITOR_START"
        const val ACTION_TRADE_MONITOR_STOP = "ACTION_TRADE_MONITOR_STOP"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
        const val ACTION_RELOAD_ACCOUNT = "ACTION_RELOAD_ACCOUNT"
        const val ACTION_SKIP_MANGA = "ACTION_SKIP_MANGA"
        const val ACTION_MARK_READ = "ACTION_MARK_READ"
        const val ACTION_TOWER_CYCLE_DUE = "ACTION_TOWER_CYCLE_DUE"

        const val EXTRA_STATUS = "EXTRA_STATUS_TEXT"
        const val EXTRA_ACCOUNT_ID = "EXTRA_ACCOUNT_ID"
        const val EXTRA_TASK_TYPE = "EXTRA_TASK_TYPE"
        const val EXTRA_PROMO_CODE = "EXTRA_PROMO_CODE"

        const val ACTION_RUNTIME_EVENT =
            "com.example.myapplication.action.MANGABUFF_RUNTIME_EVENT"

        const val EXTRA_EVENT_TYPE = "EXTRA_EVENT_TYPE"
        const val EVENT_STATUS = "STATUS"
        const val EVENT_LOG = "LOG"
        const val EVENT_STATS = "STATS"
        const val EVENT_DAILY_STATS = "DAILY_STATS"
        const val EVENT_MINE_BALANCE = "MINE_BALANCE"
        const val EVENT_MANGA = "MANGA"
        const val EVENT_TOWER_TIMER = "TOWER_TIMER"

        const val EXTRA_USERNAME = "EXTRA_USERNAME"
        const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
        const val EXTRA_COMPONENT = "EXTRA_COMPONENT"
        const val EXTRA_LOG_TIMESTAMP = "EXTRA_LOG_TIMESTAMP"
        const val EXTRA_ERROR = "EXTRA_ERROR"
        const val EXTRA_IS_RUNNING = "EXTRA_IS_RUNNING"
        const val EXTRA_CURRENT_TASK = "EXTRA_CURRENT_TASK"
        const val EXTRA_PROGRESS = "EXTRA_PROGRESS"
        const val EXTRA_DIAMONDS = "EXTRA_DIAMONDS"
        const val EXTRA_CARD_DROP = "EXTRA_CARD_DROP"
        const val EXTRA_CHAPTERS = "EXTRA_CHAPTERS"
        const val EXTRA_COMMENTS = "EXTRA_COMMENTS"
        const val EXTRA_DAY = "EXTRA_DAY"
        const val EXTRA_BATTLES = "EXTRA_BATTLES"
        const val EXTRA_BATTLE_ATTEMPTS = "EXTRA_BATTLE_ATTEMPTS"
        const val EXTRA_QUIZ = "EXTRA_QUIZ"
        const val EXTRA_ADS = "EXTRA_ADS"
        const val EXTRA_MINE_ORE = "EXTRA_MINE_ORE"
        const val EXTRA_MINE_BALANCE = "EXTRA_MINE_BALANCE"
        const val EXTRA_MINE_EXCHANGE_ORE = "EXTRA_MINE_EXCHANGE_ORE"
        const val EXTRA_MINE_DIAMONDS = "EXTRA_MINE_DIAMONDS"
        const val EXTRA_READER_CHAPTERS = "EXTRA_READER_CHAPTERS"
        const val EXTRA_DAILY_COMMENTS = "EXTRA_DAILY_COMMENTS"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_TOWER_ENDS_AT_MS = "EXTRA_TOWER_ENDS_AT_MS"

        private const val PREFS_RUNTIME = "mangabuff_runtime"
        private const val KEY_ACTIVE_RUNS = "active_runs"
        private const val KEY_TOWER_CYCLE_ENDS = "tower_cycle_ends"
        private const val KEY_PENDING_PROFILE_DELETES = "pending_profile_deletes"
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_MISSING_WEBVIEW_GRACE_MS = 30_000L

        // Automatic recovery is bounded: a WebView that keeps crashing must end in a
        // safe stopped state with a clear log entry instead of restarting forever.
        private const val RECOVERY_MAX_PER_WINDOW = 3
        private const val RECOVERY_WINDOW_MS = 10 * 60_000L

        @Volatile
        private var tradeMonitorKeepaliveRequested = false

        /** Keep the foreground service alive while the process-scoped trade monitor is active. */
        fun setTradeMonitorActive(context: Context, active: Boolean): Boolean {
            val wasActive = tradeMonitorKeepaliveRequested
            if (!active && !wasActive) return true
            tradeMonitorKeepaliveRequested = active
            val ok = startCommand(
                context,
                if (active) ACTION_TRADE_MONITOR_START else ACTION_TRADE_MONITOR_STOP,
                startAsForeground = active
            )
            if (active && !ok) tradeMonitorKeepaliveRequested = false
            return ok
        }

        fun deleteAccountData(context: Context, accountId: String) {
            if (accountId.isBlank()) return
            startCommand(context, ACTION_DELETE_ACCOUNT, accountId = accountId, startAsForeground = true)
        }

        fun startService(
            context: Context,
            initialStatus: String = "Бот работает в фоновом режиме"
        ) {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS, initialStatus)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(
                    "MangaBuffForegroundService",
                    "Failed to start foreground service action=$ACTION_START",
                    e
                )
            }
        }

        fun startAccount(context: Context, accountId: String, taskType: TaskType): Boolean {
            return startCommand(
                context,
                ACTION_START_ACCOUNT,
                accountId = accountId,
                taskType = taskType,
                startAsForeground = true
            )
        }

        fun startAll(context: Context, taskType: TaskType): Boolean {
            return startCommand(
                context,
                ACTION_START_ALL,
                taskType = taskType,
                startAsForeground = true
            )
        }

        fun startAllWithPromoCode(context: Context, promoCode: String): Boolean {
            val code = promoCode.trim()
            if (code.isEmpty()) return false
            return startCommand(
                context,
                ACTION_START_ALL,
                taskType = TaskType.PROMO,
                startAsForeground = true,
                promoCode = code
            )
        }

        fun stopAccount(context: Context, accountId: String) {
            startCommand(context, ACTION_STOP_ACCOUNT, accountId = accountId)
        }

        fun stopAll(context: Context) {
            startCommand(context, ACTION_STOP_ALL)
        }

        fun reloadAccount(context: Context, accountId: String) {
            startCommand(context, ACTION_RELOAD_ACCOUNT, accountId = accountId)
        }

        fun skipManga(context: Context, accountId: String): Boolean {
            return startCommand(context, ACTION_SKIP_MANGA, accountId = accountId)
        }

        fun markRead(context: Context, accountId: String): Boolean {
            return startCommand(context, ACTION_MARK_READ, accountId = accountId)
        }

        fun updateStatus(context: Context, status: String) {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_STATUS, status)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(
                    "MangaBuffForegroundService",
                    "Failed to update service notification action=$ACTION_UPDATE",
                    e
                )
            }
        }

        fun stopService(context: Context) {
            startCommand(context, ACTION_STOP)
        }

        private fun startCommand(
            context: Context,
            action: String,
            accountId: String? = null,
            taskType: TaskType? = null,
            startAsForeground: Boolean = false,
            promoCode: String? = null
        ): Boolean {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    this.action = action
                    if (accountId != null) putExtra(EXTRA_ACCOUNT_ID, accountId)
                    if (taskType != null) putExtra(EXTRA_TASK_TYPE, taskType.name)
                    if (promoCode != null) putExtra(EXTRA_PROMO_CODE, promoCode)
                    if (action == ACTION_TRADE_MONITOR_START) {
                        putExtra(EXTRA_STATUS, "Мониторинг обменов активен")
                    }
                    if (action == ACTION_DELETE_ACCOUNT) {
                        putExtra(EXTRA_STATUS, "Удаление данных аккаунта…")
                    }
                }
                if (startAsForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                return true
            } catch (e: Exception) {
                Log.e(
                    "MangaBuffForegroundService",
                    "Failed to dispatch command action=$action accountId=${accountId ?: "none"} " +
                        "taskType=${taskType?.name ?: "none"} startAsForeground=$startAsForeground",
                    e
                )
                return false
            }
        }
    }

    // Use the dispatching Main dispatcher, not Main.immediate: account startup must not
    // finish inline inside onStartCommand/startAllInternal before every account Job is registered.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val accountJobs = ConcurrentHashMap<String, Job>()
    // Pending delayed restarts are tracked so OFFLINE can cancel them immediately.
    private val accountRecoveryRestartJobs = ConcurrentHashMap<String, Job>()

    // One account at a time; queue state is isolated in a small class with unit tests.
    private val accountExecutionQueue = AccountExecutionQueue()

    // Tower cycles are scheduled independently from the account task queue.
    // The delay never occupies the account WebView or blocks other tasks.
    private val towerCycleJobs = ConcurrentHashMap<String, Job>()
    private val towerCycleEndsAt = ConcurrentHashMap<String, Long>()
    private val recoveryRequests = ConcurrentHashMap.newKeySet<String>()
    private val missingWebViewSince = ConcurrentHashMap<String, Long>()
    private val recoveryBudget = RecoveryBudget(RECOVERY_MAX_PER_WINDOW, RECOVERY_WINDOW_MS)
    private var watchdogJob: Job? = null
    private val repository by lazy { AccountRepository(applicationContext) }
    private lateinit var automationRunner: MultiAccountAutomationRunner

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MangaBuffAuto::ServiceWakeLock")
    }

    private var screenRegistered = false
    private var restoredPersistedRuns = false
    private var stoppingExplicitly = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> BackgroundExecutionState.setScreenOff(true)
                Intent.ACTION_SCREEN_ON -> BackgroundExecutionState.setScreenOff(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Run deferred WebView-profile deletions before any account can load a Profile handle.
        cleanupPendingProfileDeletes()

        automationRunner = MultiAccountAutomationRunner(
            context = applicationContext,
            onLog = ::publishLog,
            onAccountStatusUpdate = ::publishStatus,
            onMangaActiveUrlUpdate = ::persistAndPublishManga,
            onAccountStatsUpdate = ::publishStats,
            onDailyStatsUpdate = ::publishDailyStats,
            onTowerExpeditionEndsAtUpdate = ::publishTowerExpeditionEndsAt,
            onWebViewAssigned = { accountId, webView ->
                AutomationWebViewRegistry.assigned(accountId, webView)
                publishLog(
                    LogEntry(
                        username = accountId,
                        component = "WEBVIEW",
                        message = "SERVICE_WEBVIEW_ASSIGNED accountId=$accountId instance=${webView.hashCode()}"
                    )
                )
            },
            onWebViewCleared = { accountId, webView ->
                AutomationWebViewRegistry.cleared(accountId, webView)
                publishLog(
                    LogEntry(
                        username = accountId,
                        component = "WEBVIEW",
                        message = "SERVICE_WEBVIEW_CLEARED accountId=$accountId instance=${webView.hashCode()}"
                    )
                )
            },
            onAccountRecoveryNeeded = ::requestAccountRecovery,
            onMineBalanceUpdate = ::publishMineBalance
        )

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

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        BackgroundExecutionState.setScreenOff(!powerManager.isInteractive)

        publishLog(LogEntry(message = "BG: SERVICE_CREATED owner=foreground_service"))
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        ensureForeground(
            intent?.getStringExtra(EXTRA_STATUS)
                ?: "Фоновая автоматизация активна"
        )

        val towerDueAccountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        val towerDueEndsAt = intent?.getLongExtra(EXTRA_TOWER_ENDS_AT_MS, -1L) ?: -1L

        if (!restoredPersistedRuns) {
            restoredPersistedRuns = true
            restorePersistedRuns()
            restoreTowerCycles(
                skipAccountId = towerDueAccountId.takeIf { action == ACTION_TOWER_CYCLE_DUE }
            )
        }

        when (action) {
            ACTION_START,
            ACTION_UPDATE -> {
                // Notification only; actual automation is controlled by explicit account actions.
            }

            ACTION_TRADE_MONITOR_START -> {
                tradeMonitorKeepaliveRequested = true
            }

            ACTION_TRADE_MONITOR_STOP -> {
                tradeMonitorKeepaliveRequested = false
                stopSelfIfIdle()
            }

            ACTION_START_ACCOUNT -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                val taskTypeName = intent?.getStringExtra(EXTRA_TASK_TYPE).orEmpty()
                val taskType = taskTypeName.toTaskTypeOrNull()
                val promoCode = intent?.getStringExtra(EXTRA_PROMO_CODE)
                if (accountId.isNotBlank() && taskType != null) {
                    // A deliberate start opens a fresh recovery budget for this account.
                    recoveryBudget.reset(accountId)
                    startAccountInternal(accountId, taskType, promoCode)
                }
            }

            ACTION_START_ALL -> {
                val taskType = intent?.getStringExtra(EXTRA_TASK_TYPE)
                    ?.toTaskTypeOrNull()
                    ?: TaskType.ALL
                val promoCode = intent?.getStringExtra(EXTRA_PROMO_CODE)
                recoveryBudget.resetAll()
                startAllInternal(taskType, promoCode)
            }

            ACTION_STOP_ACCOUNT -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank()) {
                    stopAccountInternal(accountId)
                }
            }

            ACTION_DELETE_ACCOUNT -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank()) deleteAccountInternal(accountId)
            }

            ACTION_STOP_ALL -> stopAllInternal(explicitUserStop = true)

            ACTION_RELOAD_ACCOUNT -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank()) {
                    val account = repository.getAccounts().firstOrNull { it.id == accountId }
                    if (account != null && !account.isOffline) {
                        automationRunner.reloadAccount(account)
                    } else {
                        publishLog(
                            LogEntry(
                                username = accountId,
                                component = "OFFLINE",
                                message = "RELOAD_REJECTED account is OFFLINE"
                            )
                        )
                    }
                }
            }

            ACTION_SKIP_MANGA -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank() && !isAccountOffline(accountId)) {
                    automationRunner.skipCurrentManga(accountId)
                }
            }

            ACTION_MARK_READ -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank() && !isAccountOffline(accountId)) {
                    automationRunner.markCurrentMangaAsRead(accountId)
                }
            }

            ACTION_TOWER_CYCLE_DUE -> {
                if (towerDueAccountId.isNotBlank() && towerDueEndsAt > 0L) {
                    handleTowerCycleDue(towerDueAccountId, towerDueEndsAt)
                } else {
                    publishLog(
                        LogEntry(
                            component = "TOWER",
                            message = "TOWER_CYCLE_DUE_REJECTED missing_account_or_deadline",
                            isError = true
                        )
                    )
                }
            }

            ACTION_STOP -> stopAllInternal(explicitUserStop = true)
        }

        return START_STICKY
    }

    private fun ensureForeground(status: String) {
        startForeground(NOTIFICATION_ID, buildNotification(status))
    }

    private fun startAccountInternal(
        accountId: String,
        taskType: TaskType,
        promoCode: String? = null
    ) {
        // A deliberate new start supersedes any delayed retry for this account.
        accountRecoveryRestartJobs.remove(accountId)?.cancel()
        // A new user/background start opens a fresh recovery window.
        // A previous explicit "Stop all" must not permanently disable renderer recovery.
        stoppingExplicitly = false
        recoveryRequests.remove(accountId)
        missingWebViewSince.remove(accountId)
        val account = repository.getAccounts().firstOrNull { it.id == accountId }
        if (account == null) {
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "SECURITY",
                    message = "START_ACCOUNT_REJECTED account_not_found",
                    isError = true
                )
            )
            removePersistedRun(accountId)
            return
        }

        if (account.isOffline) {
            // OFFLINE survives process restarts and blocks queued/stale start intents too.
            cancelTowerCycle(accountId, clearPersisted = true)
            removePersistedRun(accountId)
            accountJobs.remove(accountId)?.cancel()
            automationRunner.stopAccount(accountId)
            runtimePrefs.edit().remove(promoCodePreferenceKey(accountId)).apply()
            publishStatus(
                accountId,
                "OFFLINE — задачи остановлены, авторизация сохранена",
                false,
                "",
                0f
            )
            releaseWakeLockIfIdle()
            stopSelfIfIdle()
            publishLog(
                LogEntry(
                    username = account.username,
                    component = "OFFLINE",
                    message = "START_REJECTED account is OFFLINE; saved profile retained"
                )
            )
            return
        }

        val promoCodeKey = promoCodePreferenceKey(accountId)
        val effectivePromoCode = if (taskType == TaskType.PROMO) {
            promoCode?.trim()?.takeIf { it.isNotEmpty() }
                ?: runtimePrefs.getString(promoCodeKey, "").orEmpty().trim()
        } else {
            ""
        }

        if (taskType == TaskType.PROMO && effectivePromoCode.isBlank()) {
            publishLog(
                LogEntry(
                    username = account.username,
                    component = "PROMO",
                    message = "PROMO_START_REJECTED missing_code",
                    isError = true
                )
            )
            publishStatus(accountId, "Промокод не задан", false, "Промокод", 0f)
            removePersistedRun(accountId)
            runtimePrefs.edit().remove(promoCodeKey).apply()
            return
        }

        if (taskType == TaskType.PROMO) {
            runtimePrefs.edit().putString(promoCodeKey, effectivePromoCode).apply()
        } else {
            runtimePrefs.edit().remove(promoCodeKey).apply()
        }

        accountJobs[accountId]?.cancel()
        automationRunner.stopAccount(accountId)
        persistRun(accountId, taskType)
        acquireWakeLockIfNeeded()

        publishLog(
            LogEntry(
                username = account.username,
                component = "ACCOUNT",
                message = "SERVICE_START accountId=$accountId taskType=${taskType.title}"
            )
        )
        publishStatus(accountId, "Запуск...", true, taskType.title, 0f)

        // Register the Job before its body can execute. Main.immediate may otherwise
        // complete a failing run inline before accountJobs[accountId] is populated,
        // skipping finally cleanup and leaving a persisted run/WakeLock behind.
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            var hasExecutionPermit = false
            var exclusiveExecutionPermit: AccountExecutionGate.Permit? = null
            if (currentJob != null) accountExecutionQueue.markQueued(accountId, currentJob)

            try {
                publishLog(
                    LogEntry(
                        username = account.username,
                        component = "QUEUE",
                        message = "ACCOUNT_QUEUED taskType=${taskType.title} activeLimit=1"
                    )
                )
                publishStatus(accountId, "В очереди — ожидает освобождения аккаунта", true, taskType.title, 0f)

                accountExecutionQueue.acquire()
                hasExecutionPermit = true

                publishLog(
                    LogEntry(
                        username = account.username,
                        component = "QUEUE",
                        message = "ACCOUNT_QUEUE_ACQUIRED taskType=${taskType.title}"
                    )
                )
                publishLog(
                    LogEntry(
                        username = account.username,
                        component = "ACCOUNT",
                        message = "ACCOUNT_EXECUTION_GATE_WAITING accountId=$accountId"
                    )
                )
                publishStatus(accountId, "Ожидание освобождения общего слота...", true, taskType.title, 0f)
                exclusiveExecutionPermit = AccountExecutionGate.acquire()
                // Keep this job marked as queued while it waits for the shared Gate.
                // The watchdog must not mistake a healthy Gate wait for a missing-WebView hang.
                if (currentJob != null) accountExecutionQueue.markAcquired(accountId, currentJob)
                publishLog(
                    LogEntry(
                        username = account.username,
                        component = "ACCOUNT",
                        message = "ACCOUNT_EXECUTION_GATE_ACQUIRED accountId=$accountId"
                    )
                )
                publishStatus(accountId, "Подготовка...", true, taskType.title, 0f)

                automationRunner.runForAccount(
                    account = account,
                    settings = repository.getSettings(),
                    taskType = taskType,
                    promoCode = effectivePromoCode
                )
            } catch (e: CancellationException) {
                publishLog(
                    LogEntry(
                        username = account.username,
                        component = "ACCOUNT",
                        message = "SERVICE_CANCELLED taskType=${taskType.title}"
                    )
                )
            } catch (e: Exception) {
                publishLog(
                    LogEntry(
                        username = account.username,
                        component = "ACCOUNT",
                        message = "SERVICE_FAILED reason=${e.message}",
                        isError = true
                    )
                )
            } finally {
                try {
                    if (currentJob != null && accountJobs[accountId] === currentJob) {
                        accountJobs.remove(accountId)
                        // Clean up the previous account before yielding the queue permit.
                        automationRunner.stopAccount(accountId)

                        val recoveryRequested = recoveryRequests.remove(accountId)
                        missingWebViewSince.remove(accountId)
                        val persistedTask = readPersistedRuns()[accountId]
                        val recoveryWanted =
                            recoveryRequested &&
                                !stoppingExplicitly &&
                                persistedTask == taskType
                        val shouldRecover = recoveryWanted &&
                            recoveryBudget.tryConsume(accountId, SystemClock.elapsedRealtime())
                        val recoveryExhausted = recoveryWanted && !shouldRecover

                        if (shouldRecover) {
                            publishLog(
                                LogEntry(
                                    username = account.username,
                                    component = "BG",
                                    message = "WATCHDOG_RECOVERY_SCHEDULED accountId=$accountId taskType=${taskType.title}",
                                    isError = true
                                )
                            )
                            publishStatus(
                                accountId,
                                "Восстановление после сбоя WebView...",
                                true,
                                taskType.title,
                                0f
                            )

                            accountRecoveryRestartJobs.remove(accountId)?.cancel()
                            val restartJob = serviceScope.launch(start = CoroutineStart.LAZY) {
                                val currentJob = coroutineContext[Job]
                                try {
                                    delay(1_500L)
                                    if (
                                        !stoppingExplicitly &&
                                        !isAccountOffline(accountId) &&
                                        readPersistedRuns()[accountId] == taskType
                                    ) {
                                        if (currentJob != null) {
                                            accountRecoveryRestartJobs.remove(accountId, currentJob)
                                        }
                                        startAccountInternal(accountId, taskType)
                                    }
                                } finally {
                                    if (currentJob != null) {
                                        accountRecoveryRestartJobs.remove(accountId, currentJob)
                                    }
                                }
                            }
                            accountRecoveryRestartJobs[accountId] = restartJob
                            restartJob.start()
                        } else {
                            if (recoveryExhausted) {
                                publishLog(
                                    LogEntry(
                                        username = account.username,
                                        component = "BG",
                                        message = "WATCHDOG_RECOVERY_GIVE_UP accountId=$accountId " +
                                            "taskType=${taskType.title} " +
                                            "limit=$RECOVERY_MAX_PER_WINDOW/${RECOVERY_WINDOW_MS / 60_000L}min " +
                                            "state=STOPPED_SAFE",
                                        isError = true
                                    )
                                )
                            }
                            removePersistedRun(accountId)
                            if (taskType == TaskType.PROMO) {
                                runtimePrefs.edit().remove(promoCodePreferenceKey(accountId)).apply()
                            }
                            publishStatus(
                                accountId,
                                if (recoveryExhausted) "Остановлено: слишком много сбоев WebView" else "Готово",
                                false,
                                "",
                                0f
                            )
                            releaseWakeLockIfIdle()
                            stopSelfIfIdle()
                        }
                    }
                } finally {
                    if (currentJob != null) accountExecutionQueue.forget(accountId, currentJob)
                    if (hasExecutionPermit) accountExecutionQueue.release()
                    exclusiveExecutionPermit?.release()
                }
            }
        }

        accountJobs[accountId] = job
        job.start()
    }

    private fun requestAccountRecovery(accountId: String) {
        if (stoppingExplicitly || isAccountOffline(accountId)) return
        if (recoveryRequests.add(accountId)) {
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "BG",
                    message = "WATCHDOG_RECOVERY_REQUEST accountId=$accountId",
                    isError = true
                )
            )
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (stoppingExplicitly) continue

                val now = System.currentTimeMillis()
                val activeIds = accountJobs
                    .filterValues { it.isActive }
                    .keys
                    .toSet()
                val webViews = AutomationWebViewRegistry.webViewsByAccount.value

                missingWebViewSince.keys
                    .filter { it !in activeIds }
                    .forEach { missingWebViewSince.remove(it) }

                for (accountId in activeIds) {
                    if (isAccountOffline(accountId)) {
                        recoveryRequests.remove(accountId)
                        missingWebViewSince.remove(accountId)
                        continue
                    }
                    // Queued jobs intentionally do not own a WebView yet; starting
                    // recovery for them would cancel/restart a healthy queue entry.
                    val accountJob = accountJobs[accountId]
                    if (accountJob != null && accountExecutionQueue.isQueued(accountId, accountJob)) {
                        missingWebViewSince.remove(accountId)
                        continue
                    }

                    if (webViews.containsKey(accountId)) {
                        missingWebViewSince.remove(accountId)
                        continue
                    }

                    val since = missingWebViewSince.putIfAbsent(accountId, now) ?: now
                    if (now - since >= WATCHDOG_MISSING_WEBVIEW_GRACE_MS) {
                        requestAccountRecovery(accountId)
                        // Flagging alone never ends a stuck job: recovery runs only in the job's
                        // finally block. Cancel it so that path actually executes. The identity
                        // check keeps a newer run of the same account untouched.
                        if (
                            accountJob != null &&
                            recoveryRequests.contains(accountId) &&
                            accountJobs[accountId] === accountJob
                        ) {
                            publishLog(
                                LogEntry(
                                    username = accountId,
                                    component = "BG",
                                    message = "WATCHDOG_CANCEL_STUCK_JOB accountId=$accountId " +
                                        "missingWebViewMs=${now - since}",
                                    isError = true
                                )
                            )
                            accountJob.cancel()
                        }
                    }
                }
            }
        }
        publishLog(LogEntry(message = "BG: WATCHDOG_STARTED interval=${WATCHDOG_INTERVAL_MS}ms"))
    }

    private fun startAllInternal(taskType: TaskType, promoCode: String? = null) {
        val accounts = repository.getAccounts().filterNot { it.isOffline }
        if (accounts.isEmpty()) {
            publishLog(LogEntry(component = "OFFLINE", message = "START_ALL_IGNORED no_online_accounts"))
            stopSelfIfIdle()
            return
        }

        publishLog(
            LogEntry(
                component = "RUNNER",
                message = "SERVICE_START_ALL accounts=${accounts.size} taskType=${taskType.title}"
            )
        )

        accounts.forEach { account ->
            startAccountInternal(account.id, taskType, promoCode)
        }
    }

    private fun deleteAccountInternal(accountId: String) {
        // stopAccountInternal synchronously tears down the WebView before profile deletion.
        stopAccountInternal(accountId)
        deleteWebViewProfile(accountId)
        publishLog(
            LogEntry(
                username = accountId,
                component = "PROFILE",
                message = "ACCOUNT_PROFILE_CLEANUP_REQUESTED accountId=$accountId"
            )
        )
        stopSelfIfIdle()
    }

    private fun deleteWebViewProfile(accountId: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            publishLog(LogEntry(username = accountId, component = "PROFILE", message = "PROFILE_DELETE_SKIPPED multi_profile_unsupported"))
            return
        }
        try {
            // A previously-used Profile handle cannot be deleted in this process. In that case
            // persist a non-secret marker and retry before Profiles are loaded at next startup.
            val deleted = ProfileStore.getInstance().deleteProfile("mb_$accountId")
            removePendingProfileDelete(accountId)
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "PROFILE",
                    message = "PROFILE_DELETE_RESULT profile=mb_$accountId deleted=$deleted"
                )
            )
        } catch (e: Exception) {
            addPendingProfileDelete(accountId)
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "PROFILE",
                    message = "PROFILE_DELETE_DEFERRED profile=mb_$accountId retryOnNextProcess=true reason=" + e.message,
                    isError = true
                )
            )
        }
    }

    private fun cleanupPendingProfileDeletes() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        val pending = runtimePrefs.getStringSet(KEY_PENDING_PROFILE_DELETES, emptySet()).orEmpty().toSet()
        if (pending.isEmpty()) return
        val remaining = mutableSetOf<String>()
        for (accountId in pending) {
            try {
                val deleted = ProfileStore.getInstance().deleteProfile("mb_$accountId")
                publishLog(
                    LogEntry(
                        username = accountId,
                        component = "PROFILE",
                        message = "PROFILE_DELETE_RETRY_RESULT profile=mb_$accountId deleted=$deleted"
                    )
                )
            } catch (e: Exception) {
                remaining.add(accountId)
                publishLog(
                    LogEntry(
                        username = accountId,
                        component = "PROFILE",
                        message = "PROFILE_DELETE_RETRY_FAILED profile=mb_$accountId reason=" + e.message,
                        isError = true
                    )
                )
            }
        }
        if (remaining.isEmpty()) runtimePrefs.edit().remove(KEY_PENDING_PROFILE_DELETES).apply()
        else runtimePrefs.edit().putStringSet(KEY_PENDING_PROFILE_DELETES, remaining).apply()
    }

    private fun addPendingProfileDelete(accountId: String) {
        val pending = runtimePrefs.getStringSet(KEY_PENDING_PROFILE_DELETES, emptySet()).orEmpty().toMutableSet()
        pending.add(accountId)
        runtimePrefs.edit().putStringSet(KEY_PENDING_PROFILE_DELETES, pending).apply()
    }

    private fun removePendingProfileDelete(accountId: String) {
        val pending = runtimePrefs.getStringSet(KEY_PENDING_PROFILE_DELETES, emptySet()).orEmpty().toMutableSet()
        if (pending.remove(accountId)) {
            if (pending.isEmpty()) runtimePrefs.edit().remove(KEY_PENDING_PROFILE_DELETES).apply()
            else runtimePrefs.edit().putStringSet(KEY_PENDING_PROFILE_DELETES, pending).apply()
        }
    }

    private fun stopAccountInternal(accountId: String) {
        accountRecoveryRestartJobs.remove(accountId)?.cancel()
        cancelTowerCycle(accountId, clearPersisted = true)
        recoveryRequests.remove(accountId)
        missingWebViewSince.remove(accountId)
        removePersistedRun(accountId)
        runtimePrefs.edit().remove(promoCodePreferenceKey(accountId)).apply()
        accountJobs.remove(accountId)?.cancel()
        automationRunner.stopAccount(accountId)
        publishStatus(
            accountId,
            if (isAccountOffline(accountId)) {
                "OFFLINE — задачи остановлены, авторизация сохранена"
            } else {
                "Остановлено пользователем"
            },
            false,
            "",
            0f
        )
        releaseWakeLockIfIdle()
        stopSelfIfIdle()
    }

    private fun stopAllInternal(explicitUserStop: Boolean) {
        if (explicitUserStop) stoppingExplicitly = true

        accountRecoveryRestartJobs.values.forEach { it.cancel() }
        accountRecoveryRestartJobs.clear()

        (towerCycleEndsAt.keys + towerCycleJobs.keys + readPersistedTowerCycles().keys)
            .toSet()
            .forEach(::cancelTowerCycleAlarm)
        towerCycleJobs.values.forEach { it.cancel() }
        towerCycleJobs.clear()
        towerCycleEndsAt.clear()
        runtimePrefs.edit().remove(KEY_TOWER_CYCLE_ENDS).apply()

        val ids = accountJobs.keys.toList()
        ids.forEach { accountId ->
            removePersistedRun(accountId)
            accountJobs.remove(accountId)?.cancel()
        }
        val promoKeys = runtimePrefs.all.keys.filter { it.startsWith("promo_code_") }
        if (promoKeys.isNotEmpty()) {
            val editor = runtimePrefs.edit()
            promoKeys.forEach { key -> editor.remove(key) }
            editor.apply()
        }
        recoveryRequests.clear()
        missingWebViewSince.clear()
        automationRunner.stopAll()

        if (explicitUserStop) {
            publishLog(LogEntry(message = "BG: ALL_TASKS_STOPPED_BY_USER"))
        }

        releaseWakeLockIfIdle(force = true)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun restorePersistedRuns() {
        val persisted = readPersistedRuns()
        if (persisted.isEmpty()) {
            publishLog(LogEntry(message = "BG: NO_PERSISTED_RUNS"))
            return
        }

        publishLog(
            LogEntry(
                component = "BG",
                message = "RESTORE_PERSISTED_RUNS count=${persisted.size}"
            )
        )

        persisted.forEach { (accountId, taskType) ->
            startAccountInternal(accountId, taskType)
        }
    }

    private fun acquireWakeLockIfNeeded() {
        if (!wakeLock.isHeld) {
            try {
                wakeLock.acquire()
                publishLog(LogEntry(message = "BG: WAKELOCK_ACQUIRED owner=foreground_service"))
            } catch (e: Exception) {
                publishLog(
                    LogEntry(
                        component = "BG",
                        message = "WAKELOCK_ACQUIRE_ERROR ${e.message}",
                        isError = true
                    )
                )
            }
        }
    }

    private fun releaseWakeLockIfIdle(force: Boolean = false) {
        // A scheduled Tower timer does not need the CPU held awake. Only live automation
        // jobs and immediate renderer recovery are allowed to retain the partial WakeLock.
        if (force || (accountJobs.isEmpty() && recoveryRequests.isEmpty())) {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    publishLog(LogEntry(message = "BG: WAKELOCK_RELEASED owner=foreground_service"))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopSelfIfIdle() {
        if (tradeMonitorKeepaliveRequested || accountJobs.isNotEmpty() || towerCycleJobs.isNotEmpty() || readPersistedRuns().isNotEmpty() || recoveryRequests.isNotEmpty()) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun persistRun(accountId: String, taskType: TaskType) {
        val values = runtimePrefs.getStringSet(KEY_ACTIVE_RUNS, emptySet()).orEmpty().toMutableSet()
        values.removeAll { it.startsWith("$accountId|") }
        values.add("$accountId|${taskType.name}")
        runtimePrefs.edit().putStringSet(KEY_ACTIVE_RUNS, values).apply()
    }

    private fun removePersistedRun(accountId: String) {
        val values = runtimePrefs.getStringSet(KEY_ACTIVE_RUNS, emptySet()).orEmpty().toMutableSet()
        values.removeAll { it.startsWith("$accountId|") }
        runtimePrefs.edit().putStringSet(KEY_ACTIVE_RUNS, values).apply()
    }

    private fun readPersistedRuns(): Map<String, TaskType> {
        val raw = runtimePrefs.getStringSet(KEY_ACTIVE_RUNS, emptySet()).orEmpty()
        return raw.mapNotNull { item ->
            val p = item.indexOf('|')
            if (p <= 0 || p >= item.length - 1) return@mapNotNull null
            val id = item.substring(0, p)
            val type = item.substring(p + 1).toTaskTypeOrNull() ?: return@mapNotNull null
            id to type
        }.toMap()
    }

    private fun promoCodePreferenceKey(accountId: String): String = "promo_code_" + accountId

    private val runtimePrefs
        get() = getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)

    private fun publishStatus(
        accountId: String,
        statusMessage: String,
        isRunning: Boolean,
        currentTask: String,
        progress: Float
    ) {
        sendRuntimeEvent(
            EVENT_STATUS,
            accountId = accountId,
            username = repository.getAccounts().firstOrNull { it.id == accountId }?.username.orEmpty(),
            extras = {
                putExtra(EXTRA_STATUS, statusMessage)
                putExtra(EXTRA_MESSAGE, statusMessage)
                putExtra(EXTRA_IS_RUNNING, isRunning)
                putExtra(EXTRA_CURRENT_TASK, currentTask)
                putExtra(EXTRA_PROGRESS, progress)
            }
        )
    }

    private fun publishLog(entry: LogEntry) {
        sendRuntimeEvent(
            EVENT_LOG,
            accountId = entry.username.takeIf { it.isNotBlank() },
            username = entry.username,
            extras = {
                putExtra(EXTRA_MESSAGE, entry.message)
                putExtra(EXTRA_COMPONENT, entry.component)
                putExtra(EXTRA_LOG_TIMESTAMP, entry.timestamp)
                putExtra(EXTRA_ERROR, entry.isError)
            }
        )
    }

    private fun publishStats(
        accountId: String,
        diamonds: String,
        cardDrop: String,
        chapters: String,
        comments: String
    ) {
        sendRuntimeEvent(
            EVENT_STATS,
            accountId = accountId,
            extras = {
                putExtra(EXTRA_DIAMONDS, diamonds)
                putExtra(EXTRA_CARD_DROP, cardDrop)
                putExtra(EXTRA_CHAPTERS, chapters)
                putExtra(EXTRA_COMMENTS, comments)
            }
        )
    }

    private fun publishDailyStats(accountId: String, stats: DailyStats) {
        sendRuntimeEvent(
            EVENT_DAILY_STATS,
            accountId = accountId,
            extras = {
                putExtra(EXTRA_DAY, stats.day)
                putExtra(EXTRA_BATTLES, stats.battles)
                putExtra(EXTRA_BATTLE_ATTEMPTS, stats.battleAttempts)
                putExtra(EXTRA_QUIZ, stats.quiz)
                putExtra(EXTRA_ADS, stats.ads)
                putExtra(EXTRA_MINE_ORE, stats.mineOre)
                putExtra(EXTRA_MINE_EXCHANGE_ORE, stats.mineExchangeOre)
                putExtra(EXTRA_MINE_DIAMONDS, stats.mineDiamonds)
                putExtra(EXTRA_READER_CHAPTERS, stats.readerChapters)
                putExtra(EXTRA_DAILY_COMMENTS, stats.comments)
            }
        )
    }

    private fun publishMineBalance(accountId: String, oreBalance: Int) {
        sendRuntimeEvent(
            EVENT_MINE_BALANCE,
            accountId = accountId,
            extras = { putExtra(EXTRA_MINE_BALANCE, oreBalance) }
        )
    }

    private fun persistAndPublishManga(accountId: String, url: String, title: String) {
        val updated = repository.updateAccount(accountId) { account ->
            account.copy(
                activeMangaUrl = url,
                activeMangaTitle = title
            )
        }

        if (updated == null) {
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "READER",
                    message = "READER_STATE_PERSIST_FAILED accountId=" + accountId + " url=" + url,
                    isError = true
                )
            )
        } else {
            publishLog(
                LogEntry(
                    username = updated.username,
                    component = "READER",
                    message = "READER_STATE_PERSISTED url=" +
                        updated.getSafeActiveMangaUrl() +
                        " title='" + updated.getSafeActiveMangaTitle() + "'"
                )
            )
        }

        // Keep UI state in sync, but never rely on the Activity being alive to persist resume state.
        publishManga(accountId, url, title)
    }

    private fun publishManga(accountId: String, url: String, title: String) {
        sendRuntimeEvent(
            EVENT_MANGA,
            accountId = accountId,
            extras = {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
        )
    }

    private fun publishTowerExpeditionEndsAt(accountId: String, endsAtMillis: Long) {
        sendRuntimeEvent(
            EVENT_TOWER_TIMER,
            accountId = accountId,
            extras = { putExtra(EXTRA_TOWER_ENDS_AT_MS, endsAtMillis) }
        )
        if (endsAtMillis > System.currentTimeMillis()) {
            scheduleTowerCycle(accountId, endsAtMillis)
        }
    }

    /**
     * Schedules the next Tower-only pass without keeping runAccountTasks alive.
     * Other tasks can finish normally; when the expedition expires, the next pass
     * claims the reward and sends a new squad through the existing Tower DOM flow.
     */
    private fun isAccountOffline(accountId: String): Boolean =
        repository.getAccounts().firstOrNull { it.id == accountId }?.isOffline == true

    private fun scheduleTowerCycle(accountId: String, endsAtMillis: Long) {
        if (isAccountOffline(accountId)) {
            cancelTowerCycle(accountId, clearPersisted = true)
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "OFFLINE",
                    message = "TOWER_SCHEDULE_REJECTED account is OFFLINE"
                )
            )
            return
        }
        if (stoppingExplicitly || endsAtMillis <= System.currentTimeMillis()) return
        val oldEnd = towerCycleEndsAt[accountId]
        if (oldEnd == endsAtMillis && towerCycleJobs[accountId]?.isActive == true) return

        towerCycleJobs.remove(accountId)?.cancel()
        towerCycleEndsAt[accountId] = endsAtMillis
        val persisted = runtimePrefs.getStringSet(KEY_TOWER_CYCLE_ENDS, emptySet())
            .orEmpty().toMutableSet()
        persisted.removeAll { it.startsWith("$accountId|") }
        persisted.add("$accountId|$endsAtMillis")
        runtimePrefs.edit().putStringSet(KEY_TOWER_CYCLE_ENDS, persisted).apply()

        // Exact allow-while-idle alarms wake the device at expedition completion without
        // pinning the CPU awake for hours. A coroutine remains as an in-process backstop.
        scheduleTowerCycleAlarm(accountId, endsAtMillis)
        publishLog(
            LogEntry(
                username = repository.getAccounts().firstOrNull { it.id == accountId }?.username.orEmpty(),
                component = "TOWER",
                message = "TOWER_CYCLE_SCHEDULED endsAt=$endsAtMillis delayMs=${endsAtMillis - System.currentTimeMillis()} wakelock=not_required"
            )
        )

        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    val remaining = endsAtMillis - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    delay(remaining.coerceAtMost(60_000L))
                }
                handleTowerCycleDue(accountId, endsAtMillis, coroutineContext[Job])
            } catch (_: CancellationException) {
                // Expected when the user stops this account or replaces the timer.
            } finally {
                if (towerCycleJobs[accountId] === coroutineContext[Job]) {
                    towerCycleJobs.remove(accountId)
                }
                releaseWakeLockIfIdle()
                stopSelfIfIdle()
            }
        }
        towerCycleJobs[accountId] = job
        job.start()
    }

    private fun scheduleTowerCycleAlarm(accountId: String, endsAtMillis: Long) {
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val pendingIntent = TowerCycleAlarmReceiver.pendingIntent(this, accountId, endsAtMillis)
        try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    endsAtMillis,
                    pendingIntent
                )
                publishLog(
                    LogEntry(
                        username = repository.getAccounts().firstOrNull { it.id == accountId }?.username.orEmpty(),
                        component = "TOWER",
                        message = "TOWER_ALARM_ARMED exact=true endsAt=$endsAtMillis"
                    )
                )
            } else {
                // Without the user-granted exact-alarm access, retain the coroutine fallback
                // and use an inexact wake-up alarm. Settings now exposes the exact-alarm switch.
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    endsAtMillis,
                    pendingIntent
                )
                publishLog(
                    LogEntry(
                        username = repository.getAccounts().firstOrNull { it.id == accountId }?.username.orEmpty(),
                        component = "TOWER",
                        message = "TOWER_ALARM_ARMED exact=false fallback=inexact endsAt=$endsAtMillis"
                    )
                )
            }
        } catch (e: SecurityException) {
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "TOWER",
                    message = "TOWER_EXACT_ALARM_DENIED fallback=inexact reason=${e.message}",
                    isError = true
                )
            )
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    endsAtMillis,
                    pendingIntent
                )
            }.onFailure { fallbackError ->
                publishLog(
                    LogEntry(
                        username = accountId,
                        component = "TOWER",
                        message = "TOWER_ALARM_FAILED reason=${fallbackError.message}",
                        isError = true
                    )
                )
            }
        } catch (e: Exception) {
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "TOWER",
                    message = "TOWER_ALARM_FAILED reason=${e.message}",
                    isError = true
                )
            )
        }
    }

    private fun cancelTowerCycleAlarm(accountId: String) {
        runCatching {
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.cancel(TowerCycleAlarmReceiver.pendingIntent(this, accountId))
        }
    }

    private fun readPersistedTowerCycles(): Map<String, Long> {
        return runtimePrefs.getStringSet(KEY_TOWER_CYCLE_ENDS, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val separator = entry.indexOf('|')
                if (separator <= 0 || separator >= entry.length - 1) return@mapNotNull null
                val accountId = entry.substring(0, separator)
                val endsAt = entry.substring(separator + 1).toLongOrNull()
                    ?: return@mapNotNull null
                accountId to endsAt
            }.toMap()
    }

    private fun removePersistedTowerCycle(accountId: String) {
        val persisted = runtimePrefs.getStringSet(KEY_TOWER_CYCLE_ENDS, emptySet())
            .orEmpty().filterNot { it.startsWith("$accountId|") }.toSet()
        runtimePrefs.edit().putStringSet(KEY_TOWER_CYCLE_ENDS, persisted).apply()
    }

    private fun handleTowerCycleDue(
        accountId: String,
        endsAtMillis: Long,
        sourceJob: Job? = null
    ) {
        val expectedEnd = towerCycleEndsAt[accountId] ?: readPersistedTowerCycles()[accountId]
        if (expectedEnd != endsAtMillis) {
            publishLog(
                LogEntry(
                    username = repository.getAccounts().firstOrNull { it.id == accountId }?.username.orEmpty(),
                    component = "TOWER",
                    message = "TOWER_CYCLE_STALE_ALARM_IGNORED expected=${expectedEnd ?: -1L} received=$endsAtMillis"
                )
            )
            return
        }

        towerCycleJobs.remove(accountId)?.let { job ->
            if (job !== sourceJob) job.cancel()
        }
        towerCycleEndsAt.remove(accountId)
        cancelTowerCycleAlarm(accountId)
        removePersistedTowerCycle(accountId)

        if (stoppingExplicitly) return

        val account = repository.getAccounts().firstOrNull { it.id == accountId }
        if (account == null) {
            publishLog(
                LogEntry(
                    username = accountId,
                    component = "TOWER",
                    message = "TOWER_CYCLE_ACCOUNT_MISSING",
                    isError = true
                )
            )
            releaseWakeLockIfIdle()
            stopSelfIfIdle()
            return
        }

        if (account.isOffline) {
            publishStatus(
                accountId,
                "OFFLINE — задачи остановлены, авторизация сохранена",
                false,
                "",
                0f
            )
            releaseWakeLockIfIdle()
            stopSelfIfIdle()
            return
        }

        val persistedTask = readPersistedRuns()[accountId]
        if (persistedTask != null || accountJobs[accountId]?.isActive == true) {
            // Never start a second WebView task concurrently; retry less aggressively to
            // avoid repeated wake-ups while another task is still using this account.
            scheduleTowerCycle(accountId, System.currentTimeMillis() + 15_000L)
            return
        }

        publishLog(
            LogEntry(
                username = account.username,
                component = "TOWER",
                message = "TOWER_CYCLE_DUE reward_claim_and_next_expedition"
            )
        )
        startAccountInternal(accountId, TaskType.TOWER)
    }

    private fun cancelTowerCycle(accountId: String, clearPersisted: Boolean) {
        towerCycleJobs.remove(accountId)?.cancel()
        towerCycleEndsAt.remove(accountId)
        cancelTowerCycleAlarm(accountId)
        if (clearPersisted) {
            removePersistedTowerCycle(accountId)
        }
    }

    private fun restoreTowerCycles(skipAccountId: String? = null) {
        val entries = readPersistedTowerCycles()
        val now = System.currentTimeMillis()
        val accountsById = repository.getAccounts().associateBy { it.id }
        entries.forEach { (accountId, endsAt) ->
            if (accountId == skipAccountId) return@forEach
            val account = accountsById[accountId]
            if (account == null || account.isOffline) {
                cancelTowerCycle(accountId, clearPersisted = true)
            } else {
                scheduleTowerCycle(accountId, maxOf(endsAt, now + 1_000L))
            }
        }
    }

    private fun sendRuntimeEvent(
        eventType: String,
        accountId: String? = null,
        username: String = "",
        extras: Intent.() -> Unit = {}
    ) {
        val intent = Intent(ACTION_RUNTIME_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_EVENT_TYPE, eventType)
            if (!accountId.isNullOrBlank()) putExtra(EXTRA_ACCOUNT_ID, accountId)
            if (username.isNotBlank()) putExtra(EXTRA_USERNAME, username)
            extras()
        }
        sendBroadcast(intent)
    }

    private fun String.toTaskTypeOrNull(): TaskType? =
        if (this == "QUIZ") TaskType.TOWER else runCatching { TaskType.valueOf(this) }.getOrNull()

    override fun onDestroy() {
        watchdogJob?.cancel()
        watchdogJob = null
        recoveryRequests.clear()
        missingWebViewSince.clear()
        accountRecoveryRestartJobs.values.forEach { it.cancel() }
        accountRecoveryRestartJobs.clear()

        try {
            if (screenRegistered) unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }

        serviceScope.cancel()
        automationRunner.stopAll()

        releaseWakeLockIfIdle(force = true)

        publishLog(LogEntry(message = "BG: SERVICE_DESTROYED owner=foreground_service"))
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая работа MangaBuff Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Уведомления о выполнении задач авто-бота MangaBuff в фоновом режиме"
            }
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MangaBuffForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaBuff Bot (Фоновый режим)")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                pendingStop
            )
            .build()
    }
}
