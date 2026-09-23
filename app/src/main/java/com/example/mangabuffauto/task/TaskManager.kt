package com.example.mangabuffauto.task

class TaskManager {
    private var activeExecution: TaskExecution? = null

    @Synchronized
    fun start(task: AutomationTask, attempt: Int = 0): TaskExecution {
        val execution = TaskExecution(task = task, attempt = attempt)
        activeExecution = execution
        return execution
    }
    @Synchronized fun current(): TaskExecution? = activeExecution
    @Synchronized
    fun isCurrent(taskId: String?, runId: String?): Boolean {
        val current = activeExecution ?: return false
        return current.task.spec.id == taskId && current.runId == runId
    }
    @Synchronized
    fun finishIfCurrent(taskId: String?, runId: String?): TaskExecution? {
        val current = activeExecution ?: return null
        if (current.task.spec.id != taskId || current.runId != runId) return null
        activeExecution = null
        return current
    }
    @Synchronized fun clear() { activeExecution = null }
    @Synchronized fun hasActive(): Boolean = activeExecution != null
}
