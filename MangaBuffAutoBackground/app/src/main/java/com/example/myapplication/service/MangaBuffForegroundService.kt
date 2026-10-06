package com.example.myapplication.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.automation.BackgroundExecutionState
import com.example.myapplication.automation.MultiAccountAutomationRunner
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MangaBuffForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "mangabuff_bot_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_FOREGROUND"
        const val ACTION_RUN_ACCOUNT = "ACTION_RUN_ACCOUNT"
        const val ACTION_RUN_ALL = "ACTION_RUN_ALL"
        const val ACTION_STOP_ACCOUNT = "ACTION_STOP_ACCOUNT"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
        const val ACTION_SKIP_MANGA = "ACTION_SKIP_MANGA"
        const val ACTION_MARK_READ = "ACTION_MARK_READ"
        const val ACTION_RELOAD_ACCOUNT = "ACTION_RELOAD_ACCOUNT"

        const val EXTRA_STATUS = "EXTRA_STATUS_TEXT"
        const val EXTRA_ACCOUNT_ID = "EXTRA_ACCOUNT_ID"
        const val EXTRA_TASK_TYPE = "EXTRA_TASK_TYPE"

        private const val PREFS = "mangabuff_background_runtime"
        private const val KEY_PENDING = "pending"
        private const val KEY_MODE = "mode"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_TASK_TYPE = "task_type"

        val eventBus = AutomationServiceEventBus()

        fun startService(context: Context, initialStatus: String = "Бот работает в фоновом режиме") {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS, initialStatus)
                },
                foreground = true
            )
        }

        fun startAccountTask(context: Context, accountId: String, taskType: TaskType) {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_RUN_ACCOUNT
                    putExtra(EXTRA_ACCOUNT_ID, accountId)
                    putExtra(EXTRA_TASK_TYPE, taskType.name)
                },
                foreground = true
            )
        }

        fun startAllTasks(context: Context, taskType: TaskType) {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_RUN_ALL
                    putExtra(EXTRA_TASK_TYPE, taskType.name)
                },
                foreground = true
            )
        }

        fun stopAccountTask(context: Context, accountId: String) {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_STOP_ACCOUNT
                    putExtra(EXTRA_ACCOUNT_ID, accountId)
                },
                foreground = false
            )
        }

        fun stopAllTasks(context: Context) {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_STOP_ALL
                },
                foreground = false
            )
        }

        fun skipCurrentManga(context: Context, accountId: String? = null) {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_SKIP_MANGA
                    putExtra(EXTRA_ACCOUNT_ID, accountId)
                },
                foreground = false
            )
        }

        fun markCurrentMangaAsRead(context: Context, accountId: String? = null) {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_MARK_READ
                    putExtra(EXTRA_ACCOUNT_ID, accountId)
                },
                foreground = false
            )
        }

        fun reloadAccount(context: Context, accountId: String) {
            dispatch(
                context,
                Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_RELOAD_ACCOUNT
                    putExtra(EXTRA_ACCOUNT_ID, accountId)
                },
                foreground = false
            )
        }

        private fun dispatch(context: Context, intent: Intent, foreground: Boolean) {
            try {
                if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun isRunning(): Boolean = eventBus.state.value.isRunning
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { AccountRepository(applicationContext) }
    private val stopRequested = AtomicBoolean(false)

    private lateinit var runner: MultiAccountAutomationRunner
    private var automationJob: kotlinx.coroutines.Job? = null
    private var currentAccountId: String? = null
    private var currentMode: String = "IDLE"
    private var currentTaskType: TaskType? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    BackgroundExecutionState.setScreenOff(true)
                    emitLog(LogEntry(component = "BG", message = "SCREEN_STATE=OFF"))
                    notifyReaderScreenChange("window.__mbScreenTurnedOff && window.__mbScreenTurnedOff();")
                }
                Intent.ACTION_SCREEN_ON -> {
                    BackgroundExecutionState.setScreenOff(false)
                    emitLog(LogEntry(component = "BG", message = "SCREEN_STATE=ON"))
                    notifyReaderScreenChange("window.__mbScreenTurnedOn && window.__mbScreenTurnedOn();")
                }
            }
        }
    }

    private fun notifyReaderScreenChange(script: String) {
        val webView = eventBus.activeWebView.value ?: return
        mainHandler.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                emitLog(
                    LogEntry(
                        component = "BG",
                        message = "SCREEN_STATE_JS_NOTIFY_FAILED reason=${e.message}",
                        isError = true
                    )
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        BackgroundExecutionState.setScreenOff(!powerManager.isInteractive)

        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        runner = MultiAccountAutomationRunner(
            context = applicationContext,
            onLog = { entry -> emit(AutomationServiceEvent.Log(entry)) },
            onAccountStatusUpdate = { accountId, statusMessage, isRunning, currentTask, progress ->
                currentAccountId = accountId
                emit(
                    AutomationServiceEvent.AccountStatus(
                        accountId = accountId,
                        statusMessage = statusMessage,
                        isRunning = isRunning,
                        currentTask = currentTask,
                        progress = progress
                    )
                )
            },
            onMangaActiveUrlUpdate = { accountId, url, title ->
                emit(AutomationServiceEvent.MangaActive(accountId, url, title))
            },
            onAccountStatsUpdate = { accountId, diamonds, cardDrop, chapters, comments ->
                emit(
                    AutomationServiceEvent.AccountStats(
                        accountId = accountId,
                        diamonds = diamonds,
                        cardDrop = cardDrop,
                        chapters = chapters,
                        comments = comments
                    )
                )
            },
            onDailyStatsUpdate = { accountId, stats ->
                emit(AutomationServiceEvent.DailyStatsUpdate(accountId, stats))
            },
            onWebViewAssigned = { webView ->
                eventBus.activeWebView.value = webView
            },
            onWebViewCleared = { webView ->
                if (eventBus.activeWebView.value === webView) {
                    eventBus.activeWebView.value = null
                }
            }
        )

        emitLog(LogEntry(component = "BG", message = "SERVICE_CREATED owner=foreground_service"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra(EXTRA_STATUS)
            ?: "Фоновая автоматизация активна"

        ensureForeground(status)

        when (intent?.action) {
            ACTION_START -> Unit

            ACTION_RUN_ACCOUNT -> {
                val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
                val taskType = parseTaskType(intent.getStringExtra(EXTRA_TASK_TYPE))
                if (!accountId.isNullOrBlank() && taskType != null) {
                    startAccount(accountId, taskType, restoring = false)
                } else {
                    emitLog(LogEntry(component = "BG", message = "RUN_ACCOUNT_INVALID_REQUEST", isError = true))
                }
            }

            ACTION_RUN_ALL -> {
                val taskType = parseTaskType(intent.getStringExtra(EXTRA_TASK_TYPE))
                if (taskType != null) {
                    startAll(taskType, restoring = false)
                } else {
                    emitLog(LogEntry(component = "BG", message = "RUN_ALL_INVALID_REQUEST", isError = true))
                }
            }

            ACTION_STOP_ACCOUNT -> {
                intent.getStringExtra(EXTRA_ACCOUNT_ID)?.let(::stopAccount)
            }

            ACTION_STOP_ALL -> stopAll(explicitUserStop = true)

            ACTION_SKIP_MANGA -> {
                val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
                runner.skipCurrentManga(accountId)
            }

            ACTION_MARK_READ -> {
                val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
                runner.markCurrentMangaAsRead(accountId)
            }

            ACTION_RELOAD_ACCOUNT -> {
                val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
                if (!accountId.isNullOrBlank()) {
                    repository.getAccounts()
                        .firstOrNull { it.id == accountId }
                        ?.let { runner.reloadAccount(it) }
                }
            }

            null -> {
                serviceScope.launch {
                    delay(300L)
                    restorePendingRunIfNeeded()
                }
            }
        }

        return START_STICKY
    }

    private fun startAccount(accountId: String, taskType: TaskType, restoring: Boolean) {
        if (automationJob?.isActive == true) {
            emitLog(LogEntry(component = "BG", message = "RUN_ACCOUNT_IGNORED_ALREADY_RUNNING"))
            return
        }

        val account = repository.getAccounts().firstOrNull { it.id == accountId }
        if (account == null) {
            clearPendingRun()
            emitLog(LogEntry(username = accountId, component = "BG", message = "ACCOUNT_NOT_FOUND", isError = true))
            finishService()
            return
        }

        currentMode = "ACCOUNT"
        currentAccountId = accountId
        currentTaskType = taskType
        stopRequested.set(false)
        persistPendingRun()

        acquireWakeLock()
        publishRunning("Аккаунт ${account.username}: ${taskType.title}")

        automationJob = serviceScope.launch {
            try {
                runner.runForAccount(account, repository.getSettings(), taskType)
            } catch (_: CancellationException) {
                emitLog(LogEntry(username = account.username, component = "BG", message = "ACCOUNT_JOB_CANCELLED"))
            } catch (e: Exception) {
                emitLog(
                    LogEntry(
                        username = account.username,
                        component = "BG",
                        message = "ACCOUNT_JOB_FAILED reason=${e.message}",
                        isError = true
                    )
                )
            } finally {
                val completedNormally = !stopRequested.get()
                automationJob = null
                if (completedNormally) {
                    clearPendingRun()
                }
                currentMode = "IDLE"
                currentAccountId = null
                currentTaskType = null
                publishStopped()
                releaseWakeLock()
                if (completedNormally) {
                    finishService()
                }
            }
        }
    }

    private fun startAll(taskType: TaskType, restoring: Boolean) {
        if (automationJob?.isActive == true) {
            emitLog(LogEntry(component = "BG", message = "RUN_ALL_IGNORED_ALREADY_RUNNING"))
            return
        }

        val accounts = repository.getAccounts()
        if (accounts.isEmpty()) {
            clearPendingRun()
            emitLog(LogEntry(component = "BG", message = "NO_ACCOUNTS", isError = true))
            finishService()
            return
        }

        currentMode = "ALL"
        currentAccountId = null
        currentTaskType = taskType
        stopRequested.set(false)
        persistPendingRun()

        acquireWakeLock()
        publishRunning("Выполнение задач на всех аккаунтах...")

        automationJob = serviceScope.launch {
            try {
                for (account in accounts) {
                    if (!isActive || stopRequested.get()) {
                        break
                    }
                    currentAccountId = account.id
                    eventBus.state.value = eventBus.state.value.copy(
                        activeAccountId = account.id,
                        status = "Аккаунт ${account.username}: ${taskType.title}"
                    )
                    runner.runForAccount(account, repository.getSettings(), taskType)
                }
            } catch (_: CancellationException) {
                emitLog(LogEntry(component = "BG", message = "BATCH_JOB_CANCELLED"))
            } catch (e: Exception) {
                emitLog(
                    LogEntry(
                        component = "BG",
                        message = "BATCH_JOB_FAILED reason=${e.message}",
                        isError = true
                    )
                )
            } finally {
                val completedNormally = !stopRequested.get()
                automationJob = null
                if (completedNormally) {
                    clearPendingRun()
                }
                currentMode = "IDLE"
                currentAccountId = null
                currentTaskType = null
                publishStopped()
                releaseWakeLock()
                if (completedNormally) {
                    finishService()
                }
            }
        }
    }

    private fun stopAccount(accountId: String) {
        if (currentAccountId != accountId && currentMode == "ALL") {
            emitLog(LogEntry(username = accountId, component = "BG", message = "STOP_ACCOUNT_IGNORED batch_owned"))
            return
        }

        stopRequested.set(true)
        automationJob?.cancel()
        automationJob = null
        runner.stopAccount(accountId)
        clearPendingRun()
        currentMode = "IDLE"
        currentAccountId = null
        currentTaskType = null
        publishStopped()
        releaseWakeLock()
        finishService()
    }

    private fun stopAll(explicitUserStop: Boolean) {
        stopRequested.set(true)

        if (explicitUserStop) {
            clearPendingRun()
            emitLog(LogEntry(component = "BG", message = "USER_STOP_REQUESTED"))
        }

        automationJob?.cancel()
        automationJob = null
        runner.stopAll()

        currentMode = "IDLE"
        currentAccountId = null
        currentTaskType = null
        publishStopped()
        releaseWakeLock()
        finishService()
    }

    private fun restorePendingRunIfNeeded() {
        if (automationJob?.isActive == true) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return

        val taskType = parseTaskType(prefs.getString(KEY_TASK_TYPE, null))
        if (taskType == null) {
            clearPendingRun()
            finishService()
            return
        }

        val mode = prefs.getString(KEY_MODE, "IDLE") ?: "IDLE"
        emitLog(LogEntry(component = "BG", message = "RESTORE_PENDING mode=${mode} task=${taskType.name}"))

        if (mode == "ACCOUNT") {
            val accountId = prefs.getString(KEY_ACCOUNT_ID, null)
            if (accountId != null) {
                startAccount(accountId, taskType, restoring = true)
            } else {
                clearPendingRun()
                finishService()
            }
        } else if (mode == "ALL") {
            startAll(taskType, restoring = true)
        } else {
            clearPendingRun()
            finishService()
        }
    }

    private fun persistPendingRun() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING, true)
            .putString(KEY_MODE, currentMode)
            .putString(KEY_ACCOUNT_ID, currentAccountId)
            .putString(KEY_TASK_TYPE, currentTaskType?.name)
            .apply()
    }

    private fun clearPendingRun() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MangaBuffAutoBackground::Automation"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            emitLog(LogEntry(component = "BG", message = "WAKELOCK_ACQUIRED"))
        } catch (e: Exception) {
            emitLog(LogEntry(component = "BG", message = "WAKELOCK_ACQUIRE_FAILED ${e.message}", isError = true))
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    emitLog(LogEntry(component = "BG", message = "WAKELOCK_RELEASED"))
                }
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun publishRunning(status: String) {
        eventBus.state.value = AutomationServiceState(
            isRunning = true,
            activeAccountId = currentAccountId,
            taskType = currentTaskType,
            mode = currentMode,
            status = status
        )
        updateNotification(status)
        emitLog(LogEntry(component = "BG", message = "AUTOMATION_OWNED_BY_SERVICE"))
    }

    private fun publishStopped() {
        eventBus.state.value = AutomationServiceState(
            isRunning = false,
            status = "Готов"
        )
        updateNotification("Фоновая автоматизация не запущена")
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun emit(event: AutomationServiceEvent) {
        eventBus.events.tryEmit(event)
    }

    private fun emitLog(entry: LogEntry) {
        emit(AutomationServiceEvent.Log(entry))
    }

    private fun ensureForeground(status: String) {
        startForeground(NOTIFICATION_ID, buildNotification(status))
    }

    private fun updateNotification(status: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun parseTaskType(value: String?): TaskType? =
        value?.let { runCatching { TaskType.valueOf(it) }.getOrNull() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая работа MangaBuff Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о выполнении задач авто-бота MangaBuff в фоновом режиме"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
            action = ACTION_STOP_ALL
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaBuff Bot • фон")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenApp)
            .setOngoing(eventBus.state.value.isRunning)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                pendingStop
            )
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        emitLog(
            LogEntry(
                component = "BG",
                message = "TASK_REMOVED pending=${getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PENDING, false)}"
            )
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val pending = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PENDING, false)
        emitLog(
            LogEntry(
                component = "BG",
                message = if (pending) {
                    "SERVICE_DESTROYED_UNEXPECTED pending_run_preserved"
                } else {
                    "SERVICE_DESTROYED"
                }
            )
        )
        releaseWakeLock()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
