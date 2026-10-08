package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.automation.MultiAccountAutomationRunner
import com.example.myapplication.automation.NetworkResetManager
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.TaskType
import com.example.myapplication.service.MangaBuffForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AccountRepository(application)
    private val _activeWebView = MutableStateFlow<android.webkit.WebView?>(null)
    val activeWebView: StateFlow<android.webkit.WebView?> = _activeWebView.asStateFlow()
    private var activeWebViewAccountId: String? = null

    // TEMP DEBUG: show the real automation WebView so the rewarded ad can be
    // inspected and its close button can be pressed manually.
    private val _debugWebViewVisible = MutableStateFlow(false)
    val debugWebViewVisible: StateFlow<Boolean> = _debugWebViewVisible.asStateFlow()

    fun setDebugWebViewVisible(visible: Boolean) {
        _debugWebViewVisible.value = visible
    }

    private val automationRunner = MultiAccountAutomationRunner(
        context = application,
        onLog = { logEntry -> addLog(logEntry) },
        onAccountStatusUpdate = { accountId, statusMessage, isRunning, currentTask, progress ->
            updateAccountStatus(accountId, statusMessage, isRunning, currentTask, progress)
        },
        onMangaActiveUrlUpdate = { accountId, url, title ->
            updateActiveMangaUrl(accountId, url, title)
        },
        onAccountStatsUpdate = { accountId, diamonds, cardDrop, chapters, comments ->
            updateAccountStats(accountId, diamonds, cardDrop, chapters, comments)
        },
        onDailyStatsUpdate = { accountId, stats ->
            updateDailyStats(accountId, stats)
        },
        onWebViewAssigned = { accountId, webView ->
            if (_activeWebView.value == null || activeWebViewAccountId == accountId) {
                activeWebViewAccountId = accountId
                _activeWebView.value = webView
            }
        },
        onWebViewCleared = { accountId, webView ->
            if (activeWebViewAccountId == accountId && _activeWebView.value === webView) {
                activeWebViewAccountId = null
                _activeWebView.value = null
            }
        }
    )

    private val accountJobs = mutableMapOf<String, Job>()
    private var sequentialJob: Job? = null
    private var sequentialCurrentAccountId: String? = null
    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val networkResetManager = NetworkResetManager(application) { message ->
        addLog(LogEntry(message = message))
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    @Synchronized
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            try {
                val powerManager = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MangaBuffAuto::WakeLock").apply {
                    acquire()
                }
                addLog(LogEntry(message = "BG: SERVICE_CREATED"))
                addLog(LogEntry(message = "BG: SERVICE_STARTED"))
                addLog(LogEntry(message = "BG: FOREGROUND_STARTED"))
                addLog(LogEntry(message = "BG: WAKELOCK_ACQUIRED"))
                addLog(LogEntry(message = "BG: AUTOMATION_RUNNING"))
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    addLog(LogEntry(message = "BG: WAKELOCK_RELEASED"))
                    addLog(LogEntry(message = "BG: SERVICE_STOPPED"))
                }
            }
        } catch (e: Exception) {}
        wakeLock = null
    }

    private val _accounts = MutableStateFlow<List<MangaBuffAccount>>(emptyList())
    val accounts: StateFlow<List<MangaBuffAccount>> = _accounts.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _settings = MutableStateFlow(GlobalSettings())
    val settings: StateFlow<GlobalSettings> = _settings.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _activeTab = MutableStateFlow(0)
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _showAddAccountDialog = MutableStateFlow(false)
    val showAddAccountDialog: StateFlow<Boolean> = _showAddAccountDialog.asStateFlow()

    private val _showPromoDialog = MutableStateFlow(false)
    val showPromoDialog: StateFlow<Boolean> = _showPromoDialog.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        _accounts.value = repository.getAccounts()
        _settings.value = repository.getSettings()
    }

    fun setActiveTab(index: Int) {
        _activeTab.value = index
    }

    fun setShowAddAccountDialog(show: Boolean) {
        _showAddAccountDialog.value = show
    }

    fun setShowPromoDialog(show: Boolean) {
        _showPromoDialog.value = show
    }

    fun addAccount(username: String, cookiesJson: String, csrfToken: String) {
        val newAccount = MangaBuffAccount(
            username = username,
            cookiesJson = cookiesJson,
            csrfToken = csrfToken
        )
        repository.saveAccount(newAccount)
        loadData()
        addLog(LogEntry(username = username, message = "Аккаунт успешно добавлен"))
    }

    fun addAccountWithId(id: String, username: String, cookiesJson: String, csrfToken: String) {
        val newAccount = MangaBuffAccount(
            id = id,
            username = username,
            cookiesJson = cookiesJson,
            csrfToken = csrfToken
        )
        repository.saveAccount(newAccount)
        loadData()
        addLog(LogEntry(username = username, message = "Аккаунт успешно добавлен с изолированным профилем"))
    }

    fun deleteAccount(accountId: String) {
        stopAccountTask(accountId)
        repository.deleteAccount(accountId)
        loadData()
        addLog(LogEntry(message = "Аккаунт удален"))
    }

    fun reloadAccount(account: MangaBuffAccount) {
        automationRunner.reloadAccount(account)
        addLog(LogEntry(username = account.username, message = "Обновление страницы и переход на главную"))
    }

    fun skipCurrentManga(): Boolean {
        val skipped = automationRunner.skipCurrentManga()
        addLog(
            LogEntry(
                message = if (skipped) "READER: SKIP_MANGA_BUTTON_DISPATCHED" else "READER: SKIP_MANGA_BUTTON_NOT_AVAILABLE",
                isError = !skipped
            )
        )
        return skipped
    }

    fun markCurrentMangaAsRead(): Boolean {
        val marked = automationRunner.markCurrentMangaAsRead()
        addLog(
            LogEntry(
                message = if (marked) "READER: MARK_READ_BUTTON_DISPATCHED" else "READER: MARK_READ_BUTTON_NOT_AVAILABLE",
                isError = !marked
            )
        )
        return marked
    }

    /**
     * Marks the currently reading manga as "Прочитано" (folder 3) and
     * lets the reader runtime continue with the normal catalog flow:
     * /manga?hide_read=1 -> choose another manga -> start reading.
     */
    fun changeCurrentManga(accountId: String): Boolean {
        val marked = automationRunner.markCurrentMangaAsRead(accountId)
        addLog(
            LogEntry(
                username = accountId,
                message = if (marked) {
                    "READER: CHANGE_MANGA_REQUESTED action=MARK_READ_THEN_CATALOG"
                } else {
                    "READER: CHANGE_MANGA_NOT_AVAILABLE"
                },
                isError = !marked
            )
        )
        return marked
    }

    fun updateAccountTasks(
        account: MangaBuffAccount,
        reader: Boolean,
        quiz: Boolean,
        adv: Boolean,
        mine: Boolean,
        comment: Boolean,
        battle: Boolean
    ) {
        val updated = repository.updateAccount(account.id) { acc ->
            acc.copy(
                readerEnabled = reader,
                quizEnabled = quiz,
                advEnabled = adv,
                mineEnabled = mine,
                commentEnabled = comment,
                battleEnabled = battle
            )
        } ?: return
        _accounts.update { list -> list.map { acc -> if (acc.id == updated.id) updated else acc } }
    }

    private fun updateAccountStatus(
        accountId: String,
        statusMessage: String,
        isRunning: Boolean,
        currentTask: String,
        progress: Float
    ) {
        _accounts.update { list ->
            list.map { acc ->
                if (acc.id == accountId) {
                    val updated = acc.copy(
                        statusMessage = statusMessage,
                        isRunning = isRunning,
                        currentTask = currentTask,
                        taskProgress = progress,
                        lastRunTime = if (!isRunning) System.currentTimeMillis() else acc.lastRunTime
                    )
                    updated
                } else acc
            }
        }
    }

    private fun updateAccountStats(
        accountId: String,
        diamonds: String,
        cardDrop: String,
        chapters: String,
        comments: String
    ) {
        val updated = repository.updateAccount(accountId) { acc ->
            acc.copy(
                diamonds = diamonds,
                cardDrop = cardDrop,
                chapterProgress = chapters,
                commentProgress = comments
            )
        }
        if (updated != null) {
            _accounts.update { list -> list.map { acc -> if (acc.id == accountId) updated else acc } }
        }
        addLog(LogEntry(message = "STAT: STATE_UPDATED"))
        addLog(LogEntry(message = "STAT: UI_STATE_PUBLISHED"))
    }

    private fun updateDailyStats(accountId: String, stats: DailyStats) {
        val updated = repository.updateAccount(accountId) { acc ->
            acc.copy(
                dailyStatsDay = stats.day,
                dailyBattles = stats.battles,
                dailyBattleAttempts = stats.battleAttempts,
                dailyQuiz = stats.quiz,
                dailyAds = stats.ads,
                dailyMineOre = stats.mineOre,
                dailyMineExchangeOre = stats.mineExchangeOre,
                dailyMineDiamonds = stats.mineDiamonds,
                dailyReaderChapters = stats.readerChapters,
                dailyComments = stats.comments
            )
        }
        if (updated != null) {
            _accounts.update { list -> list.map { acc -> if (acc.id == accountId) updated else acc } }
        }
    }

    private fun updateActiveMangaUrl(accountId: String, url: String, title: String) {
        val updated = repository.updateAccount(accountId) { acc ->
            acc.copy(activeMangaUrl = url, activeMangaTitle = title)
        }
        if (updated != null) {
            _accounts.update { list -> list.map { acc -> if (acc.id == accountId) updated else acc } }
        }
    }

    private fun syncKeepScreenOnState() {
        _keepScreenOn.value = _isRunning.value && _settings.value.keepScreenOn
    }

    fun saveSettings(newSettings: GlobalSettings) {
        _settings.value = newSettings
        repository.saveSettings(newSettings)
        syncKeepScreenOnState()
        addLog(LogEntry(message = "Параметры выполнения обновлены"))
    }

    fun runTaskForAccount(account: MangaBuffAccount, taskType: TaskType) {
        if (_isRunning.value) {
            addLog(
                LogEntry(
                    username = account.username,
                    message = "JOB: START_REJECTED reason=AUTOMATION_ALREADY_RUNNING",
                    isError = true
                )
            )
            return
        }

        accountJobs[account.id]?.cancel()
        accountJobs.remove(account.id)
        automationRunner.stopAccount(account.id)

        sequentialJob?.cancel()
        sequentialJob = null

        _isRunning.value = true
        syncKeepScreenOnState()
        acquireWakeLock()
        MangaBuffForegroundService.startService(getApplication(), "Выполнение задач (${account.username})...")

        println("JOB: START accountId=${account.id}")
        accountJobs[account.id] = viewModelScope.launch {
            try {
                automationRunner.runForAccount(account, _settings.value, taskType)
            } catch (e: CancellationException) {
                println("JOB: CANCEL accountId=${account.id}")
                addLog(LogEntry(username = account.username, message = "Выполнение остановлено пользователем"))
            } catch (e: Exception) {
                addLog(LogEntry(username = account.username, message = "Ошибка выполнения: ${e.message}", isError = true))
            } finally {
                val currentJob = coroutineContext[Job]
                val ownsCurrentSlot = accountJobs[account.id] == currentJob
                if (ownsCurrentSlot) {
                    accountJobs.remove(account.id)
                    println("JOB: COMPLETE accountId=${account.id}")
                    repository.updateAccount(account.id) { acc ->
                        acc.copy(
                            isRunning = false,
                            taskProgress = 0f,
                            statusMessage = if (acc.statusMessage?.contains("Завершено") == true) acc.statusMessage else "Остановлено пользователем"
                        )
                    }?.let { updated ->
                        _accounts.update { list -> list.map { acc -> if (acc.id == account.id) updated else acc } }
                    }
                }
                if (accountJobs.isEmpty()) {
                    _isRunning.value = false
                    syncKeepScreenOnState()
                    releaseWakeLock()
                    MangaBuffForegroundService.stopService(getApplication())
                }
            }
        }
    }

    fun runTaskForAllAccounts(taskType: TaskType) {
        if (_isRunning.value) {
            addLog(
                LogEntry(
                    message = "SEQUENTIAL: START_REJECTED reason=AUTOMATION_ALREADY_RUNNING",
                    isError = true
                )
            )
            return
        }

        val enabledAccounts = _accounts.value.toList()
        if (enabledAccounts.isEmpty()) {
            addLog(LogEntry(message = "SEQUENTIAL: NO_ACCOUNTS", isError = true))
            return
        }

        val settingsSnapshot = _settings.value
        _isRunning.value = true
        syncKeepScreenOnState()
        acquireWakeLock()
        MangaBuffForegroundService.startService(
            getApplication(),
            "Последовательный запуск: ${enabledAccounts.size} аккаунтов..."
        )

        sequentialJob = viewModelScope.launch {
            addLog(
                LogEntry(
                    message = "SEQUENTIAL: START accounts=${enabledAccounts.size} networkReset=${settingsSnapshot.networkResetEnabled}/${settingsSnapshot.networkResetMode}"
                )
            )

            try {
                for ((index, account) in enabledAccounts.withIndex()) {
                    coroutineContext.ensureActive()
                    sequentialCurrentAccountId = account.id
                    accountJobs[account.id] = coroutineContext[Job]!!

                    addLog(
                        LogEntry(
                            username = account.username,
                            message = "SEQUENTIAL: ACCOUNT_START ${index + 1}/${enabledAccounts.size} accountId=${account.id}"
                        )
                    )

                    try {
                        automationRunner.runForAccount(
                            account,
                            settingsSnapshot.copy(sequentialAccounts = true),
                            taskType
                        )
                    } catch (e: CancellationException) {
                        addLog(LogEntry(username = account.username, message = "SEQUENTIAL: ACCOUNT_CANCELLED"))
                        throw e
                    } catch (e: Exception) {
                        addLog(
                            LogEntry(
                                username = account.username,
                                message = "SEQUENTIAL: ACCOUNT_FAILED reason=${e.message}",
                                isError = true
                            )
                        )
                    } finally {
                        automationRunner.stopAccount(account.id)
                        accountJobs.remove(account.id)
                        repository.updateAccount(account.id) { acc ->
                            acc.copy(
                                isRunning = false,
                                taskProgress = 0f,
                                statusMessage = if (acc.statusMessage?.contains("Завершено") == true) acc.statusMessage else "Остановлено"
                            )
                        }?.let { updated ->
                            _accounts.update { list -> list.map { acc -> if (acc.id == account.id) updated else acc } }
                        }
                        addLog(
                            LogEntry(
                                username = account.username,
                                message = "SEQUENTIAL: ACCOUNT_CLEANUP_DONE ${index + 1}/${enabledAccounts.size}"
                            )
                        )
                    }

                    sequentialCurrentAccountId = null
                    coroutineContext.ensureActive()

                    if (settingsSnapshot.networkResetEnabled &&
                        settingsSnapshot.networkResetMode != com.example.myapplication.data.NetworkResetMode.NONE &&
                        index + 1 < enabledAccounts.size
                    ) {
                        addLog(LogEntry(username = account.username, message = "SEQUENTIAL: NETWORK_RESET_START beforeNext=${index + 2}/${enabledAccounts.size}"))
                        val networkReady = networkResetManager.reset(settingsSnapshot.networkResetMode)
                        if (!networkReady) {
                            addLog(LogEntry(username = account.username, message = "SEQUENTIAL: NETWORK_RESET_FAILED -> STOP", isError = true))
                            break
                        }
                        addLog(LogEntry(username = account.username, message = "SEQUENTIAL: NETWORK_READY beforeNext=${index + 2}/${enabledAccounts.size}"))
                    }

                    if (index + 1 < enabledAccounts.size) {
                        addLog(LogEntry(username = enabledAccounts[index + 1].username, message = "SEQUENTIAL: NEXT_ACCOUNT_READY ${index + 2}/${enabledAccounts.size}"))
                    }
                }
            } finally {
                sequentialCurrentAccountId = null
                sequentialJob = null
                accountJobs.clear()
                _isRunning.value = false
                syncKeepScreenOnState()
                releaseWakeLock()
                MangaBuffForegroundService.stopService(getApplication())
                addLog(LogEntry(message = "SEQUENTIAL: FINISHED"))
            }
        }
    }
    private fun stopAllTasksState() {
        _accounts.update { list ->
            list.map { acc ->
                acc.copy(
                    isRunning = false,
                    taskProgress = 0f,
                    statusMessage = if (acc.statusMessage?.contains("Завершено") == true) acc.statusMessage else "Остановлено пользователем"
                )
            }
        }
        repository.saveAccounts(_accounts.value)
    }

    fun stopAccountTask(accountId: String) {
        println("JOB: STOP_ACCOUNT accountId=${accountId}")
        if (sequentialCurrentAccountId == accountId && sequentialJob != null) {
            sequentialJob?.cancel()
            return
        }

        accountJobs[accountId]?.cancel()
        accountJobs.remove(accountId)
        automationRunner.stopAccount(accountId)

        repository.updateAccount(accountId) { acc ->
            acc.copy(
                isRunning = false,
                taskProgress = 0f,
                statusMessage = "Остановлено пользователем"
            )
        }?.let { updated ->
            _accounts.update { list -> list.map { acc -> if (acc.id == accountId) updated else acc } }
        }

        if (accountJobs.isEmpty()) {
            _isRunning.value = false
            syncKeepScreenOnState()
            releaseWakeLock()
            MangaBuffForegroundService.stopService(getApplication())
        }
    }

    fun stopAllTasks() {
        println("JOB: STOP_ALL")
        sequentialJob?.cancel()
        sequentialJob = null
        sequentialCurrentAccountId = null

        for ((accountId, job) in accountJobs) {
            println("JOB: CANCEL accountId=${accountId}")
            job.cancel()
        }
        accountJobs.clear()
        automationRunner.stopAll()
        _isRunning.value = false
        syncKeepScreenOnState()
        releaseWakeLock()
        stopAllTasksState()
        MangaBuffForegroundService.stopService(getApplication())
        addLog(LogEntry(message = "Все задачи остановлены"))
    }
    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(entry: LogEntry) {
        _logs.update { (listOf(entry) + it).take(5000) }
    }
}
