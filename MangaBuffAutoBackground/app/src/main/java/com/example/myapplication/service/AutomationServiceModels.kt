package com.example.myapplication.service

import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class AutomationServiceState(
    val isRunning: Boolean = false,
    val activeAccountId: String? = null,
    val taskType: TaskType? = null,
    val mode: String = "IDLE",
    val status: String = "Готов"
)

sealed interface AutomationServiceEvent {
    data class Log(val entry: LogEntry) : AutomationServiceEvent

    data class AccountStatus(
        val accountId: String,
        val statusMessage: String,
        val isRunning: Boolean,
        val currentTask: String,
        val progress: Float
    ) : AutomationServiceEvent

    data class MangaActive(
        val accountId: String,
        val url: String,
        val title: String
    ) : AutomationServiceEvent

    data class AccountStats(
        val accountId: String,
        val diamonds: String,
        val cardDrop: String,
        val chapters: String,
        val comments: String
    ) : AutomationServiceEvent

    data class DailyStatsUpdate(
        val accountId: String,
        val stats: DailyStats
    ) : AutomationServiceEvent
}

class AutomationServiceEventBus {
    val events = MutableSharedFlow<AutomationServiceEvent>(
        replay = 100,
        extraBufferCapacity = 500
    )

    val state = MutableStateFlow(AutomationServiceState())
}
