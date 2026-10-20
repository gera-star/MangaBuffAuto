package com.example.myapplication.service

internal enum class AccountTaskState {
    WAITING,
    RUNNING,
    PAUSED,
    RECOVERING,
    COMPLETED,
    ERROR
}

internal enum class AccountTaskEvent {
    QUEUED,
    STARTED,
    NETWORK_LOST,
    NETWORK_RESTORED,
    RECOVERY_REQUESTED,
    COMPLETED,
    FAILED,
    STOPPED
}

/** Pure transition rules for per-account task lifecycle, intentionally testable without Android. */
internal object AccountTaskLifecycle {
    fun transition(current: AccountTaskState, event: AccountTaskEvent): AccountTaskState =
        when (event) {
            AccountTaskEvent.QUEUED -> AccountTaskState.WAITING

            AccountTaskEvent.STARTED -> when (current) {
                AccountTaskState.WAITING,
                AccountTaskState.PAUSED,
                AccountTaskState.RECOVERING -> AccountTaskState.RUNNING
                AccountTaskState.RUNNING,
                AccountTaskState.COMPLETED,
                AccountTaskState.ERROR -> current
            }

            AccountTaskEvent.NETWORK_LOST -> when (current) {
                AccountTaskState.WAITING,
                AccountTaskState.RUNNING -> AccountTaskState.PAUSED
                else -> current
            }

            AccountTaskEvent.NETWORK_RESTORED -> when (current) {
                AccountTaskState.PAUSED -> AccountTaskState.RUNNING
                else -> current
            }

            AccountTaskEvent.RECOVERY_REQUESTED -> when (current) {
                AccountTaskState.RUNNING,
                AccountTaskState.PAUSED -> AccountTaskState.RECOVERING
                else -> current
            }

            AccountTaskEvent.COMPLETED -> when (current) {
                AccountTaskState.RUNNING,
                AccountTaskState.PAUSED,
                AccountTaskState.RECOVERING -> AccountTaskState.COMPLETED
                else -> current
            }

            AccountTaskEvent.FAILED -> when (current) {
                AccountTaskState.WAITING,
                AccountTaskState.RUNNING,
                AccountTaskState.PAUSED,
                AccountTaskState.RECOVERING -> AccountTaskState.ERROR
                else -> current
            }

            AccountTaskEvent.STOPPED -> when (current) {
                AccountTaskState.WAITING,
                AccountTaskState.RUNNING,
                AccountTaskState.PAUSED,
                AccountTaskState.RECOVERING -> AccountTaskState.WAITING
                else -> current
            }
        }
}
