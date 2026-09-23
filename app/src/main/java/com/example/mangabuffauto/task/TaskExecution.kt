package com.example.mangabuffauto.task
import java.util.UUID
data class TaskExecution(
    val task: AutomationTask,
    val attempt: Int = 0,
    val runId: String = UUID.randomUUID().toString(),
    val startedAtMs: Long = System.currentTimeMillis()
)
