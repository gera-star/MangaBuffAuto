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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.automation.BackgroundExecutionState
import com.example.myapplication.automation.MultiAccountAutomationRunner
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
        const val ACTION_RELOAD_ACCOUNT = "ACTION_RELOAD_ACCOUNT"
        const val ACTION_SKIP_MANGA = "ACTION_SKIP_MANGA"
        const val ACTION_MARK_READ = "ACTION_MARK_READ"

        const val EXTRA_STATUS = "EXTRA_STATUS_TEXT"
        const val EXTRA_ACCOUNT_ID = "EXTRA_ACCOUNT_ID"
        const val EXTRA_TASK_TYPE = "EXTRA_TASK_TYPE"

        const val ACTION_RUNTIME_EVENT =
            "com.example.myapplication.action.MANGABUFF_RUNTIME_EVENT"

        const val EXTRA_EVENT_TYPE = "EXTRA_EVENT_TYPE"
        const val EVENT_STATUS = "STATUS"
        const val EVENT_LOG = "LOG"
        const val EVENT_STATS = "STATS"
        const val EVENT_DAILY_STATS = "DAILY_STATS"
        const val EVENT_MANGA = "MANGA"

        const val EXTRA_USERNAME = "EXTRA_USERNAME"
        const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
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
        const val EXTRA_QUIZ = "EXTRA_QUIZ"
        const val EXTRA_ADS = "EXTRA_ADS"
        const val EXTRA_MINE_ORE = "EXTRA_MINE_ORE"
        const val EXTRA_MINE_EXCHANGE_ORE = "EXTRA_MINE_EXCHANGE_ORE"
        const val EXTRA_MINE_DIAMONDS = "EXTRA_MINE_DIAMONDS"
        const val EXTRA_READER_CHAPTERS = "EXTRA_READER_CHAPTERS"
        const val EXTRA_DAILY_COMMENTS = "EXTRA_DAILY_COMMENTS"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TITLE = "EXTRA_TITLE"

        private const val PREFS_RUNTIME = "mangabuff_runtime"
        private const val KEY_ACTIVE_RUNS = "active_runs"

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
            } catch (_: Exception) {
                // Caller cannot recover a rejected service start synchronously.
            }
        }

        fun startAccount(context: Context, accountId: String, taskType: TaskType) {
            startCommand(
                context,
                ACTION_START_ACCOUNT,
                accountId = accountId,
                taskType = taskType,
                startAsForeground = true
            )
        }

        fun startAll(context: Context, taskType: TaskType) {
            startCommand(
                context,
                ACTION_START_ALL,
                taskType = taskType,
                startAsForeground = true
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
            startCommand(context, ACTION_SKIP_MANGA, accountId = accountId)
            return true
        }

        fun markRead(context: Context, accountId: String): Boolean {
            startCommand(context, ACTION_MARK_READ, accountId = accountId)
            return true
        }

        fun updateStatus(context: Context, status: String) {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_STATUS, status)
                }
                context.startService(intent)
            } catch (_: Exception) {
                // Ignore service update failures.
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
            startAsForeground: Boolean = false
        ) {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    this.action = action
                    if (accountId != null) putExtra(EXTRA_ACCOUNT_ID, accountId)
                    if (taskType != null) putExtra(EXTRA_TASK_TYPE, taskType.name)
                }
                if (startAsForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Ignore command dispatch failures.
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accountJobs = ConcurrentHashMap<String, Job>()
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

        automationRunner = MultiAccountAutomationRunner(
            context = applicationContext,
            onLog = ::publishLog,
            onAccountStatusUpdate = ::publishStatus,
            onMangaActiveUrlUpdate = ::publishManga,
            onAccountStatsUpdate = ::publishStats,
            onDailyStatsUpdate = ::publishDailyStats,
            onWebViewAssigned = { accountId, webView ->
                publishLog(
                    LogEntry(
                        username = accountId,
                        component = "WEBVIEW",
                        message = "SERVICE_WEBVIEW_ASSIGNED accountId=$accountId instance=${webView.hashCode()}"
                    )
                )
            },
            onWebViewCleared = { accountId, webView ->
                publishLog(
                    LogEntry(
                        username = accountId,
                        component = "WEBVIEW",
                        message = "SERVICE_WEBVIEW_CLEARED accountId=$accountId instance=${webView.hashCode()}"
                    )
                )
            }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        ensureForeground(
            intent?.getStringExtra(EXTRA_STATUS)
                ?: "Фоновая автоматизация активна"
        )

        if (!restoredPersistedRuns) {
            restoredPersistedRuns = true
            restorePersistedRuns()
        }

        when (action) {
            ACTION_START,
            ACTION_UPDATE -> {
                // Notification only; actual automation is controlled by explicit account actions.
            }

            ACTION_START_ACCOUNT -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                val taskTypeName = intent?.getStringExtra(EXTRA_TASK_TYPE).orEmpty()
                val taskType = taskTypeName.toTaskTypeOrNull()
                if (accountId.isNotBlank() && taskType != null) {
                    startAccountInternal(accountId, taskType)
                }
            }

            ACTION_START_ALL -> {
                val taskType = intent?.getStringExtra(EXTRA_TASK_TYPE)
                    ?.toTaskTypeOrNull()
                    ?: TaskType.ALL
                startAllInternal(taskType)
            }

            ACTION_STOP_ACCOUNT -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank()) {
                    stopAccountInternal(accountId)
                }
            }

            ACTION_STOP_ALL -> stopAllInternal(explicitUserStop = true)

            ACTION_RELOAD_ACCOUNT -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank()) {
                    automationRunner.reloadAccount(
                        repository.getAccounts().firstOrNull { it.id == accountId }
                            ?: return START_STICKY
                    )
                }
            }

            ACTION_SKIP_MANGA -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank()) {
                    automationRunner.skipCurrentManga(accountId)
                }
            }

            ACTION_MARK_READ -> {
                val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
                if (accountId.isNotBlank()) {
                    automationRunner.markCurrentMangaAsRead(accountId)
                }
            }

            ACTION_STOP -> stopAllInternal(explicitUserStop = true)
        }

        return START_STICKY
    }

    private fun ensureForeground(status: String) {
        startForeground(NOTIFICATION_ID, buildNotification(status))
    }

    private fun startAccountInternal(accountId: String, taskType: TaskType) {
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

        val job = serviceScope.launch {
            try {
                automationRunner.runForAccount(account, repository.getSettings(), taskType)
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
                val currentJob = coroutineContext[Job]
                if (currentJob != null && accountJobs[accountId] === currentJob) {
                    accountJobs.remove(accountId)
                    automationRunner.stopAccount(accountId)
                    removePersistedRun(accountId)
                    publishStatus(accountId, "Готово", false, "", 0f)
                    releaseWakeLockIfIdle()
                    stopSelfIfIdle()
                }
            }
        }

        accountJobs[accountId] = job
    }

    private fun startAllInternal(taskType: TaskType) {
        val accounts = repository.getAccounts()
        if (accounts.isEmpty()) {
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
            startAccountInternal(account.id, taskType)
        }
    }

    private fun stopAccountInternal(accountId: String) {
        removePersistedRun(accountId)
        accountJobs.remove(accountId)?.cancel()
        automationRunner.stopAccount(accountId)
        publishStatus(accountId, "Остановлено пользователем", false, "", 0f)
        releaseWakeLockIfIdle()
        stopSelfIfIdle()
    }

    private fun stopAllInternal(explicitUserStop: Boolean) {
        if (explicitUserStop) stoppingExplicitly = true

        val ids = accountJobs.keys.toList()
        ids.forEach { accountId ->
            removePersistedRun(accountId)
            accountJobs.remove(accountId)?.cancel()
        }
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
        if (force || accountJobs.isEmpty()) {
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
        if (accountJobs.isNotEmpty() || readPersistedRuns().isNotEmpty()) return
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
        runCatching { TaskType.valueOf(this) }.getOrNull()

    override fun onDestroy() {
        try {
            if (screenRegistered) unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }

        serviceScope.cancel()
        if (!stoppingExplicitly) {
            automationRunner.stopAll()
        } else {
            automationRunner.stopAll()
        }

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
