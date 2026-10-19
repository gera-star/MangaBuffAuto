package com.example.myapplication.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.automation.DeckCommentToggleRegistry
import com.example.myapplication.automation.EventDropToggleRegistry
import com.example.myapplication.automation.TradeAutomation
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.TaskType
import com.example.myapplication.data.appendLogEntry
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
                            timestamp = intent?.getLongExtra(
                                MangaBuffForegroundService.EXTRA_LOG_TIMESTAMP,
                                System.currentTimeMillis()
                            ) ?: System.currentTimeMillis(),
                            username = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_USERNAME).orEmpty(),
                            component = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_COMPONENT)
                                .orEmpty().ifBlank { "BG" },
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
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_COMMENTS).orEmpty(),
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_EVENT_CANDIES).orEmpty()
                    )
                }
                MangaBuffForegroundService.EVENT_DAILY_STATS -> {
                    updateDailyStats(
                        accountId,
                        DailyStats(
                            day = intent?.getStringExtra(MangaBuffForegroundService.EXTRA_DAY).orEmpty(),
                            battles = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_BATTLES, 0) ?: 0,
                            battleAttempts = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_BATTLE_ATTEMPTS, 0) ?: 0,
                            totalBattleAttempts = intent?.getIntExtra(MangaBuffForegroundService.EXTRA_TOTAL_BATTLE_ATTEMPTS, 0) ?: 0,
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
                MangaBuffForegroundService.EVENT_MINE_BALANCE -> {
                    updateAccountMineOreBalance(
                        accountId,
                        intent?.getIntExtra(MangaBuffForegroundService.EXTRA_MINE_BALANCE, 0) ?: 0
                    )
                }
                MangaBuffForegroundService.EVENT_MANGA -> {
                    updateActiveMangaUrl(
                        accountId,
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_URL).orEmpty(),
                        intent?.getStringExtra(MangaBuffForegroundService.EXTRA_TITLE).orEmpty()
                    )
                }
                MangaBuffForegroundService.EVENT_TOWER_TIMER -> {
                    updateTowerExpeditionEndsAt(
                        accountId,
                        intent?.getLongExtra(MangaBuffForegroundService.EXTRA_TOWER_ENDS_AT_MS, 0L) ?: 0L
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

    fun addAccountWithId(
        id: String,
        username: String,
        cookiesJson: String,
        csrfToken: String,
        userAgent: String? = null
    ) {
        val newAccount = MangaBuffAccount(
            id = id,
            username = username,
            cookiesJson = cookiesJson,
            csrfToken = csrfToken,
            userAgent = userAgent?.takeIf { it.isNotBlank() }
                ?: MangaBuffAccount().getSafeUserAgent()
        )
        repository.saveAccount(newAccount)
        loadData()
        addLog(LogEntry(username = username, message = "Аккаунт успешно добавлен с изолированным профилем"))
    }

    fun deleteAccount(accountId: String) {
        // Stop trade HTTP activity first, then let the service destroy the WebView and
        // remove/defer deletion of this account's isolated profile before process exit.
        TradeAutomation.getInstance(getApplication()).forgetAccount(accountId)
        MangaBuffForegroundService.deleteAccountData(getApplication(), accountId)
        repository.deleteAccount(accountId)
        DeckCommentToggleRegistry.remove(accountId)
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
        tower: Boolean,
        adv: Boolean,
        mine: Boolean,
        comment: Boolean,
        deckComment: Boolean,
        battle: Boolean
    ) {
        var previousDeckCommentEnabled: Boolean? = null
        val updated = repository.updateAccount(account.id) {
            previousDeckCommentEnabled = it.deckCommentEnabled
            it.copy(
                readerEnabled = reader,
                towerEnabled = tower,
                advEnabled = adv,
                mineEnabled = mine,
                commentEnabled = comment,
                deckCommentEnabled = deckComment,
                battleEnabled = battle
            )
        } ?: return

        // Publish only an actual deck-toggle change. Other task switches must not
        // overwrite a newer deck state with a stale AccountCard snapshot.
        if (previousDeckCommentEnabled != null &&
            previousDeckCommentEnabled != updated.deckCommentEnabled
        ) {
            // Seed from the persisted pre-toggle value first. Otherwise an OFF tap
            // before the service starts could create an initial OFF snapshot with
            // disableVersion=0, and a rapid ON tap would hide the cancellation.
            DeckCommentToggleRegistry.initializeIfAbsent(account.id, previousDeckCommentEnabled!!)
            DeckCommentToggleRegistry.setEnabled(account.id, updated.deckCommentEnabled)
        }

        _accounts.update { list ->
            list.map { acc -> if (acc.id == updated.id) updated else acc }
        }
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
                    val offline = acc.isOffline
                    acc.copy(
                        statusMessage = if (offline) {
                            "OFFLINE — задачи остановлены, авторизация сохранена"
                        } else statusMessage,
                        isRunning = isRunning && !offline,
                        currentTask = if (offline) "" else currentTask,
                        taskProgress = if (offline) 0f else progress,
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
        comments: String,
        eventCandies: String
    ) {
        val updated = repository.updateAccount(accountId) {
            it.copy(
                diamonds = diamonds,
                cardDrop = cardDrop,
                chapterProgress = chapters,
                commentProgress = comments,
                eventCandies = eventCandies.toIntOrNull()?.toString() ?: it.eventCandies
            )
        } ?: return

        _accounts.update { list ->
            list.map { acc -> if (acc.id == updated.id) updated else acc }
        }
    }

    private fun updateAccountMineOreBalance(accountId: String, oreBalance: Int) {
        val updated = repository.updateAccount(accountId) {
            it.copy(mineOreBalance = oreBalance, mineOreBalanceKnown = true)
        } ?: return

        _accounts.update { list ->
            list.map { acc -> if (acc.id == updated.id) updated else acc }
        }
    }

    private fun updateDailyStats(accountId: String, stats: DailyStats) {
        val updated = repository.updateAccount(accountId) {
            it.copy(
                dailyStatsDay = stats.day,
                dailyBattles = stats.battles,
                dailyBattleAttempts = stats.battleAttempts,
                totalBattleAttempts = maxOf(it.totalBattleAttempts, stats.totalBattleAttempts),
                dailyQuiz = stats.quiz,
                dailyAds = stats.ads,
                dailyMineOre = stats.mineOre,
                dailyMineExchangeOre = stats.mineExchangeOre,
                dailyMineDiamonds = stats.mineDiamonds,
                dailyReaderChapters = stats.readerChapters,
                dailyComments = stats.comments
            )
        } ?: return

        _accounts.update { list ->
            list.map { acc -> if (acc.id == updated.id) updated else acc }
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

    private fun updateTowerExpeditionEndsAt(accountId: String, endsAtMillis: Long) {
        val updated = repository.updateAccount(accountId) {
            it.copy(
                towerExpeditionEndsAtMillis = endsAtMillis,
                towerExpeditionStateKnown = true
            )
        } ?: return

        _accounts.update { list ->
            list.map { acc -> if (acc.id == updated.id) updated else acc }
        }
    }

    fun saveSettings(newSettings: GlobalSettings) {
        _settings.value = newSettings
        repository.saveSettings(newSettings)
        // A running reader checks the registry for each addHistory reward response, so
        // the Events toggle applies immediately without restarting or duplicating reading.
        _accounts.value.forEach { account ->
            EventDropToggleRegistry.setEnabled(account.id, newSettings.eventsEnabled)
        }
        addLog(
            LogEntry(
                message = "Параметры выполнения обновлены; сбор Events=" +
                    if (newSettings.eventsEnabled) "ВКЛ" else "ВЫКЛ"
            )
        )
    }

    fun runTaskForAccount(account: MangaBuffAccount, taskType: TaskType) {
        if (account.isOffline) {
            addLog(
                LogEntry(
                    username = account.username,
                    component = "OFFLINE",
                    message = "START_REJECTED account is OFFLINE"
                )
            )
            return
        }
        val accepted = MangaBuffForegroundService.startAccount(getApplication(), account.id, taskType)
        if (!accepted) {
            addLog(
                LogEntry(
                    username = account.username,
                    component = "BG",
                    message = "START_REQUEST_FAILED accountId=${account.id} taskType=${taskType.title}",
                    isError = true
                )
            )
            return
        }
        _accounts.update { list ->
            list.map { item ->
                if (item.id == account.id) {
                    item.copy(
                        isRunning = true,
                        statusMessage = "Запуск задания: ${taskType.title}…",
                        currentTask = taskType.title,
                        taskProgress = 0f
                    )
                } else item
            }
        }
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
        val eligibleAccountIds = _accounts.value.filterNot { it.isOffline }.map { it.id }.toSet()
        val requestedCount = eligibleAccountIds.size
        if (requestedCount == 0) {
            addLog(LogEntry(component = "BG", message = "START_ALL_REJECTED no_online_accounts"))
            return
        }
        val accepted = MangaBuffForegroundService.startAll(getApplication(), taskType)
        if (!accepted) {
            addLog(
                LogEntry(
                    component = "BG",
                    message = "START_ALL_REQUEST_FAILED accounts=$requestedCount taskType=${taskType.title}",
                    isError = true
                )
            )
            return
        }
        _accounts.update { list ->
            list.map { item ->
                if (item.id in eligibleAccountIds) {
                    item.copy(
                        isRunning = true,
                        statusMessage = "Запуск: ${taskType.title}…",
                        currentTask = taskType.title,
                        taskProgress = 0f
                    )
                } else item
            }
        }
        _isRunning.value = requestedCount > 0
        addLog(
            LogEntry(
                component = "BG",
                message = "START_ALL_REQUEST accounts=$requestedCount taskType=${taskType.title}"
            )
        )
    }

    fun runPromoCodeForAllAccounts(promoCode: String): Boolean {
        val code = promoCode.trim()
        if (code.isEmpty()) return false

        val eligibleAccountIds = _accounts.value.filterNot { it.isOffline }.map { it.id }.toSet()
        val requestedCount = eligibleAccountIds.size
        if (requestedCount == 0) {
            addLog(LogEntry(component = "PROMO", message = "PROMO_START_REJECTED no_online_accounts"))
            return false
        }

        val accepted = MangaBuffForegroundService.startAllWithPromoCode(getApplication(), code)
        if (!accepted) {
            addLog(
                LogEntry(
                    component = "PROMO",
                    message = "PROMO_START_REQUEST_FAILED accounts=$requestedCount",
                    isError = true
                )
            )
            return false
        }

        _accounts.update { list ->
            list.map { item ->
                if (item.id in eligibleAccountIds) {
                    item.copy(
                        isRunning = true,
                        statusMessage = "Запуск промокода…",
                        currentTask = TaskType.PROMO.title,
                        taskProgress = 0f
                    )
                } else item
            }
        }
        _isRunning.value = true
        addLog(
            LogEntry(
                component = "PROMO",
                message = "PROMO_START_REQUEST accounts=$requestedCount codeLength=" + code.length
            )
        )
        return true
    }

    private fun stopAllTasksState() {
        val visibleStatusById = _accounts.value.associate { it.id to it.statusMessage }
        _accounts.update { list ->
            list.map { acc ->
                val visibleStatus = visibleStatusById[acc.id]
                acc.copy(
                    isRunning = false,
                    taskProgress = 0f,
                    currentTask = "",
                    statusMessage = when {
                        acc.isOffline -> "OFFLINE — задачи остановлены, авторизация сохранена"
                        visibleStatus?.contains("Завершено") == true -> visibleStatus
                        else -> "Остановка запрошена…"
                    }
                )
            }
        }

        // Never write the stale UI list over the repository: service callbacks may have
        // persisted fresh stats, reader position, cookies, or other account fields meanwhile.
        // Apply only the stop-state fields to each account's latest stored record.
        _accounts.value.map { it.id }.forEach { accountId ->
            val visibleStatus = visibleStatusById[accountId]
            repository.updateAccount(accountId) { stored ->
                stored.copy(
                    isRunning = false,
                    taskProgress = 0f,
                    currentTask = "",
                    statusMessage = when {
                        stored.isOffline -> "OFFLINE — задачи остановлены, авторизация сохранена"
                        visibleStatus?.contains("Завершено") == true -> visibleStatus
                        else -> "Остановка запрошена…"
                    }
                )
            }
        }
    }

    /**
     * Toggle account automation availability without touching its saved cookies/profile.
     * Returning online only permits future manual runs; it never starts tasks implicitly.
     */
    fun setAccountOffline(accountId: String, offline: Boolean) {
        val updated = repository.updateAccount(accountId) { account ->
            account.copy(
                isOffline = offline,
                isRunning = false,
                taskProgress = 0f,
                currentTask = "",
                statusMessage = if (offline) {
                    "OFFLINE — задачи остановлены, авторизация сохранена"
                } else {
                    "ONLINE — задачи не запущены"
                }
            )
        } ?: return

        _accounts.update { list ->
            list.map { account -> if (account.id == accountId) updated else account }
        }

        if (offline) {
            // The repository state is already OFFLINE, so trade rejects any further requests.
            TradeAutomation.getInstance(getApplication()).stopForAccount(accountId)
            MangaBuffForegroundService.stopAccount(getApplication(), accountId)
        }

        _isRunning.value = _accounts.value.any { it.isRunning }
        addLog(
            LogEntry(
                username = updated.username,
                component = "OFFLINE",
                message = if (offline) {
                    "ACCOUNT_OFFLINE tasks_cancelled auth_preserved"
                } else {
                    "ACCOUNT_ONLINE ready_for_manual_start"
                }
            )
        )
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
        _logs.update { appendLogEntry(it, entry) }
    }
}
