package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AccountRepository(application)
    private val _activeWebView = MutableStateFlow<android.webkit.WebView?>(null)
    val activeWebView: StateFlow<android.webkit.WebView?> = _activeWebView.asStateFlow()

    // TEMP DEBUG: show the real automation WebView so the rewarded ad can be
    // inspected and its close button can be pressed manually.
    private val _debugWebViewVisible = MutableStateFlow(false)
    val debugWebViewVisible: StateFlow<Boolean> = _debugWebViewVisible.asStateFlow()

    fun setDebugWebViewVisible(visible: Boolean) {
        _debugWebViewVisible.value = visible
    }

    private val applicationContext = getApplication<Application>()

    private fun observeBackgroundService() {
        viewModelScope.launch {
            MangaBuffForegroundService.eventBus.state.collect { state ->
                _isRunning.value = state.isRunning
            }
        }

        viewModelScope.launch {
            MangaBuffForegroundService.eventBus.activeWebView.collect { webView ->
                _activeWebView.value = webView
            }
        }

        viewModelScope.launch {
            MangaBuffForegroundService.eventBus.events.collect { event ->
                when (event) {
                    is com.example.myapplication.service.AutomationServiceEvent.Log ->
                        addLog(event.entry)

                    is com.example.myapplication.service.AutomationServiceEvent.AccountStatus ->
                        updateAccountStatus(
                            event.accountId,
                            event.statusMessage,
                            event.isRunning,
                            event.currentTask,
                            event.progress
                        )

                    is com.example.myapplication.service.AutomationServiceEvent.MangaActive ->
                        updateActiveMangaUrl(event.accountId, event.url, event.title)

                    is com.example.myapplication.service.AutomationServiceEvent.AccountStats ->
                        updateAccountStats(
                            event.accountId,
                            event.diamonds,
                            event.cardDrop,
                            event.chapters,
                            event.comments
                        )

                    is com.example.myapplication.service.AutomationServiceEvent.DailyStatsUpdate ->
                        updateDailyStats(event.accountId, event.stats)
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
        loadData()
        observeBackgroundService()
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
        MangaBuffForegroundService.reloadAccount(applicationContext, account.id)
        addLog(LogEntry(username = account.username, message = "Обновление страницы и переход на главную"))
    }

    fun skipCurrentManga(): Boolean {
        MangaBuffForegroundService.skipCurrentManga(applicationContext)
        addLog(LogEntry(message = "READER: SKIP_MANGA_BUTTON_DISPATCHED"))
        return true
    }

    fun markCurrentMangaAsRead(): Boolean {
        MangaBuffForegroundService.markCurrentMangaAsRead(applicationContext)
        addLog(LogEntry(message = "READER: MARK_READ_BUTTON_DISPATCHED"))
        return true
    }

    /**
     * Marks the currently reading manga as "Прочитано" (folder 3) and
     * lets the reader runtime continue with the normal catalog flow:
     * /manga?hide_read=1 -> choose another manga -> start reading.
     */
    fun changeCurrentManga(accountId: String): Boolean {
        MangaBuffForegroundService.markCurrentMangaAsRead(applicationContext, accountId)
        addLog(
            LogEntry(
                username = accountId,
                message = "READER: CHANGE_MANGA_REQUESTED action=MARK_READ_THEN_CATALOG"
            )
        )
        return true
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
        MangaBuffForegroundService.startAccountTask(applicationContext, account.id, taskType)
    }

    fun runTaskForAllAccounts(taskType: TaskType) {
        if (MangaBuffForegroundService.isRunning()) {
            MangaBuffForegroundService.stopAllTasks(applicationContext)
        } else {
            MangaBuffForegroundService.startAllTasks(applicationContext, taskType)
        }
    }

    fun stopAccountTask(accountId: String) {
        MangaBuffForegroundService.stopAccountTask(applicationContext, accountId)

        _accounts.update { list ->
            list.map { acc ->
                if (acc.id == accountId) {
                    acc.copy(
                        isRunning = false,
                        taskProgress = 0f,
                        statusMessage = "Остановлено пользователем"
                    )
                } else acc
            }
        }
        repository.saveAccounts(_accounts.value)
    }

    fun stopAllTasks() {
        MangaBuffForegroundService.stopAllTasks(applicationContext)
        addLog(LogEntry(message = "Все задачи остановлены"))
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(entry: LogEntry) {
        _logs.update { (listOf(entry) + it).take(5000) }
    }
}
