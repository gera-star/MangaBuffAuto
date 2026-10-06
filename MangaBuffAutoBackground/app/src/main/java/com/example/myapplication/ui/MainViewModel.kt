package com.example.myapplication.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.TaskType
import com.example.myapplication.service.MangaBuffForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AccountRepository(application)
    /*
     * Debug/UI registry only. Automation never uses a single global WebView.
     * Every WebView is addressed by accountId.
     */
    private val _webViewsByAccount =
        MutableStateFlow<Map<String, android.webkit.WebView>>(emptyMap())
    val webViewsByAccount: StateFlow<Map<String, android.webkit.WebView>> =
        _webViewsByAccount.asStateFlow()

    // TEMP DEBUG: show the real automation WebView so the rewarded ad can be
    // inspected and its close button can be pressed manually.
    private val _debugWebViewVisible = MutableStateFlow(false)
    val debugWebViewVisible: StateFlow<Boolean> = _debugWebViewVisible.asStateFlow()

    fun setDebugWebViewVisible(visible: Boolean) {
        _debugWebViewVisible.value = visible
    }

    private val runtimeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val eventType = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_EVENT_TYPE).orEmpty()
            val accountId = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_ACCOUNT_ID).orEmpty()

            when (eventType) {
                MangaBuffForegroundService.EVENT_STATUS -> {
                    updateAccountStatus(
                        accountId,
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_STATUS)
                            ?: intent?.getStringExtra(MangaBuffForegroundService.EXTRA_MESSAGE).orEmpty(),
                        intent?.getBooleanExtra(MangaBuffForegroundService.EXTRA_IS_RUNNING, false) ?: false,
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_CURRENT_TASK).orEmpty(),
                        intent?.getFloatExtra(MangaBuffForegroundService.EXTRA_PROGRESS, 0f) ?: 0f
                    )
                }
                MangaBuffForegroundService.EVENT_LOG -> {
                    addLog(
                        LogEntry(
                            username = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_USERNAME).orEmpty(),
                            component = "BG",
                            message = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_MESSAGE).orEmpty(),
                            isError = intent?.getBooleanExtra(MangaBuffForegroundService.EXTRA_ERROR, false) ?: false
                        )
                    )
                }
                MangaBuffForegroundService.EVENT_STATS -> {
                    updateAccountStats(
                        accountId,
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_DIAMONDS).orEmpty(),
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_CARD_DROP).orEmpty(),
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_CHAPTERS).orEmpty(),
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_COMMENTS).orEmpty()
                    )
                }
                MangaBuffForegroundService.EVENT_DAILY_STATS -> {
                    updateDailyStats(
                        accountId,
                        DailyStats(
                            day = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_DAY).orEmpty(),
                            battles = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_BATTLES, 0) ?: 0,
                            quiz = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_QUIZ, 0) ?: 0,
                            ads = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_ADS, 0) ?: 0,
                            mineOre = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_MINE_ORE, 0) ?: 0,
                            mineExchangeOre = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_MINE_EXCHANGE_ORE, 0) ?: 0,
                            mineDiamonds = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_MINE_DIAMONDS, 0) ?: 0,
                            readerChapters = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_READER_CHAPTERS, 0) ?: 0,
                            comments = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_DAILY_COMMENTS, 0) ?: 0
                        )
                    )
                }
                MangaBuffForegroundService.EVENT_MANGA -> {
                    updateActiveMangaUrl(
                        accountId,
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_URL).orEmpty(),
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_TITLE).orEmpty()
                    )
                }
            }
        }
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
        ContextCompat.registerReceiver(
            getApplication(),
            runtimeReceiver,
            IntentFilter(MangaBuffForegroundService.ACTION_RUNTIME_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        loadData()
    }

    override fun onCleared() {
        try {
            getApplication<Application>().unregisterReceiver(runtimeReceiver)
        } catch (_: Exception) {
        }
        super.onCleared()
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
        MangaBuffForegroundService.reloadAccount(getApplication(), account.id)
        addLog(
            LogEntry(
                username = account.username,
                component = "BG",
                message = "RELOAD_REQUESTED accountId=${account.id}"
            )
        )
    }

    fun skipCurrentManga(): Boolean {
        addLog(
            LogEntry(
                component = "SECURITY",
                message = "READER: SKIP_REJECTED accountId_REQUIRED",
                isError = true
            )
        )
        return false
    }

    fun skipCurrentManga(accountId: String): Boolean {
        MangaBuffForegroundService.skipManga(getApplication(), accountId)
        return true
    }

    fun markCurrentMangaAsRead(): Boolean {
        addLog(
            LogEntry(
                component = "SECURITY",
                message = "READER: MARK_READ_REJECTED accountId_REQUIRED",
                isError = true
            )
        )
        return false
    }

    fun markCurrentMangaAsRead(accountId: String): Boolean {
        MangaBuffForegroundService.markRead(getApplication(), accountId)
        return true
    }

    /**
     * Marks the currently reading manga as "Прочитано" (folder 3) and
     * lets the reader runtime continue with the normal catalog flow:
     * /manga?hide_read=1 -> choose another manga -> start reading.
     */
    fun changeCurrentManga(accountId: String): Boolean {
        val marked = MangaBuffForegroundService.markRead(getApplication(), accountId)
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
        val updatedList = _accounts.value.map { acc ->
            if (acc.id == account.id) {
                acc.copy(
                    readerEnabled = reader,
                    quizEnabled = quiz,
                    advEnabled = adv,
                    mineEnabled = mine,
                    commentEnabled = comment,
                    battleEnabled = battle
                )
            } else acc
        }
        _accounts.value = updatedList
        repository.saveAccounts(updatedList)
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
                    acc.copy(
                        statusMessage = statusMessage,
                        isRunning = isRunning,
                        currentTask = currentTask,
                        taskProgress = progress,
                        lastRunTime = if (!isRunning) System.currentTimeMillis() else acc.lastRunTime
                    )
                } else acc
            }
        }
        _isRunning.value = _accounts.value.any { it.isRunning }
    }

    private fun updateAccountStats(
        accountId: String,
        diamonds: String,
        cardDrop: String,
        chapters: String,
        comments: String
    ) {
        _accounts.update { list ->
            val updatedList = list.map { acc ->
                if (acc.id == accountId) {
                    acc.copy(
                        diamonds = diamonds,
                        cardDrop = cardDrop,
                        chapterProgress = chapters,
                        commentProgress = comments
                    )
                } else acc
            }
            repository.saveAccounts(updatedList)
            updatedList
        }
        addLog(LogEntry(message = "STAT: STATE_UPDATED"))
        addLog(LogEntry(message = "STAT: UI_STATE_PUBLISHED"))
    }

    private fun updateDailyStats(accountId: String, stats: DailyStats) {
        _accounts.update { list ->
            val updated = list.map { acc ->
                if (acc.id == accountId) {
                    acc.copy(
                        dailyStatsDay = stats.day,
                        dailyBattles = stats.battles,
                        dailyQuiz = stats.quiz,
                        dailyAds = stats.ads,
                        dailyMineOre = stats.mineOre,
                        dailyMineExchangeOre = stats.mineExchangeOre,
                        dailyMineDiamonds = stats.mineDiamonds,
                        dailyReaderChapters = stats.readerChapters,
                        dailyComments = stats.comments
                    )
                } else acc
            }
            repository.saveAccounts(updated)
            updated
        }
    }

    private fun updateActiveMangaUrl(accountId: String, url: String, title: String) {
        _accounts.update { list ->
            list.map { acc ->
                if (acc.id == accountId) {
                    val updated = acc.copy(
                        activeMangaUrl = url,
                        activeMangaTitle = title
                    )
                    updated
                } else acc
            }
        }
    }

    fun saveSettings(newSettings: GlobalSettings) {
        _settings.value = newSettings
        repository.saveSettings(newSettings)
        addLog(LogEntry(message = "Параметры выполнения обновлены"))
    }

    fun runTaskForAccount(account: MangaBuffAccount, taskType: TaskType) {
        MangaBuffForegroundService.startAccount(getApplication(), account.id, taskType)
        _isRunning.value = true
        addLog(
            LogEntry(
                username = account.username,
                component = "BG",
                message = "START_REQUEST accountId=${account.id} taskType=${taskType.title}"
            )
        )
    }

    fun runTaskForAllAccounts(taskType: TaskType) {
        MangaBuffForegroundService.startAll(getApplication(), taskType)
        _isRunning.value = _accounts.value.isNotEmpty()
        addLog(
            LogEntry(
                component = "BG",
                message = "START_ALL_REQUEST accounts=${_accounts.value.size} taskType=${taskType.title}"
            )
        )
    }

    private fun stopAllTasksState() {
        _accounts.update { list ->
            list.map { acc ->
                acc.copy(
                    isRunning = false,
                    taskProgress = 0f,
                    statusMessage = if (acc.statusMessage?.contains("Завершено") == true) {
                        acc.statusMessage
                    } else {
                        "Остановлено пользователем"
                    }
                )
            }
        }
        repository.saveAccounts(_accounts.value)
    }

    fun stopAccountTask(accountId: String) {
        MangaBuffForegroundService.stopAccount(getApplication(), accountId)
    }

    fun stopAllTasks() {
        MangaBuffForegroundService.stopAll(getApplication())
        stopAllTasksState()
        _isRunning.value = false
        addLog(LogEntry(message = "Все задачи остановлены"))
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(entry: LogEntry) {
        _logs.update { (listOf(entry) + it).take(5000) }
    }
}
