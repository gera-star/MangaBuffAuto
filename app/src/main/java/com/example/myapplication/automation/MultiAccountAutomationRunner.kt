package com.example.myapplication.automation

import android.content.Context
import android.webkit.WebView
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.TaskType

class MultiAccountAutomationRunner(
    context: Context,
    private val onLog: (LogEntry) -> Unit,
    onAccountStatusUpdate: (accountId: String, statusMessage: String, isRunning: Boolean, currentTask: String, progress: Float) -> Unit,
    onMangaActiveUrlUpdate: (accountId: String, url: String, title: String) -> Unit,
    onAccountStatsUpdate: (accountId: String, diamonds: String, cardDrop: String, chapters: String, comments: String) -> Unit = { _, _, _, _, _ -> },
    onDailyStatsUpdate: (accountId: String, stats: DailyStats) -> Unit = { _, _ -> },
    onWebViewAssigned: (accountId: String, webView: WebView) -> Unit = { _, _ -> },
    onWebViewCleared: (accountId: String, webView: WebView) -> Unit = { _, _ -> }
) {
    private val runtime = AutomationRuntime(
        context,
        onLog,
        onAccountStatusUpdate,
        onMangaActiveUrlUpdate,
        onAccountStatsUpdate,
        onDailyStatsUpdate,
        onWebViewAssigned,
        onWebViewCleared
    )
    suspend fun runForAccount(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        taskType: TaskType
    ) {
        onLog(LogEntry(username = account.username, component = "RUNNER", message = "START accountId=${account.id}"))
        onLog(LogEntry(username = account.username, component = "RUNNER", message = "PREPARE accountId=${account.id}"))

        val accountRuntime = runtime.prepareAccount(account)

        onLog(LogEntry(username = account.username, component = "RUNNER", message = "READY accountId=${account.id} profile=${accountRuntime.profileName}"))

        runtime.executeTask(
            activeAccountId = account.id,
            account = account,
            settings = settings,
            taskType = taskType,
            expectedProfileName = accountRuntime.profileName
        )
    }

    fun skipCurrentManga(accountId: String): Boolean {
        val skipped = runtime.skipCurrentManga(accountId)
        onLog(
            LogEntry(
                username = accountId,
                component = "RUNNER",
                message = if (skipped) "READER: SKIP_MANGA_DISPATCHED" else "READER: SKIP_MANGA_NOT_AVAILABLE"
            )
        )
        return skipped
    }

    fun markCurrentMangaAsRead(accountId: String): Boolean {
        val marked = runtime.markCurrentMangaAsRead(accountId)
        onLog(
            LogEntry(
                username = accountId,
                component = "RUNNER",
                message = if (marked) "READER: MARK_READ_DISPATCHED" else "READER: MARK_READ_NOT_AVAILABLE"
            )
        )
        return marked
    }

    fun stopAccount(accountId: String) {
        onLog(LogEntry(username = accountId, component = "RUNNER", message = "STOP accountId=$accountId"))
        runtime.stopAccount(accountId)
    }

    fun reloadAccount(account: MangaBuffAccount) {
        runtime.reloadAccount(account)
    }

    fun stopAll() {
        onLog(LogEntry(component = "RUNNER", message = "STOP_ALL"))
        runtime.stopAll()
    }

    fun closeAllWebViews() {
        runtime.closeAllWebViews()
    }
}
