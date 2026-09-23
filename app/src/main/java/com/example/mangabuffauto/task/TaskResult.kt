package com.example.mangabuffauto.task

sealed class TaskResult {
    data class Success(val message: String) : TaskResult()
    data class Failure(val message: String, val canRetry: Boolean = true) : TaskResult()
    object LimitReached : TaskResult()
    object Blocked : TaskResult()
    object Captcha : TaskResult()
    object Timeout : TaskResult()
}
