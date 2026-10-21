package com.example.myapplication.service

internal enum class AccountTaskState {
    IDLE, STARTING, RUNNING, STOPPING, CLEANING, WAITING_NETWORK, READY_NEXT_ACCOUNT, RECOVERING, FINISHED, ERROR
}

internal enum class AccountTaskEvent {
    QUEUED, STARTED, NETWORK_LOST, NETWORK_RESTORED, NETWORK_WAIT_STARTED,
    RECOVERY_REQUESTED, STOP_REQUESTED, CLEANUP_STARTED, READY_NEXT_ACCOUNT,
    COMPLETED, FAILED, STOPPED
}

internal data class AccountTaskTransition(
    val from: AccountTaskState,
    val to: AccountTaskState
)

/** Explicit account-aware state transitions, testable without Android dependencies. */
internal object AccountTaskLifecycle {
    fun transition(current: AccountTaskState, event: AccountTaskEvent): AccountTaskState =
        when (event) {
            AccountTaskEvent.QUEUED -> AccountTaskState.STARTING

            AccountTaskEvent.STARTED -> when (current) {
                AccountTaskState.IDLE,
                AccountTaskState.STARTING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.READY_NEXT_ACCOUNT,
                AccountTaskState.RECOVERING -> AccountTaskState.RUNNING
                else -> current
            }

            AccountTaskEvent.NETWORK_LOST -> when (current) {
                AccountTaskState.STARTING,
                AccountTaskState.RUNNING,
                AccountTaskState.READY_NEXT_ACCOUNT -> AccountTaskState.WAITING_NETWORK
                else -> current
            }

            AccountTaskEvent.NETWORK_WAIT_STARTED -> when (current) {
                AccountTaskState.STARTING,
                AccountTaskState.RUNNING,
                AccountTaskState.CLEANING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.READY_NEXT_ACCOUNT -> AccountTaskState.WAITING_NETWORK
                else -> current
            }

            AccountTaskEvent.NETWORK_RESTORED -> when (current) {
                AccountTaskState.WAITING_NETWORK -> AccountTaskState.READY_NEXT_ACCOUNT
                else -> current
            }

            AccountTaskEvent.RECOVERY_REQUESTED -> when (current) {
                AccountTaskState.RUNNING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.CLEANING,
                AccountTaskState.STOPPING -> AccountTaskState.RECOVERING
                else -> current
            }

            AccountTaskEvent.STOP_REQUESTED -> when (current) {
                AccountTaskState.STARTING,
                AccountTaskState.RUNNING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.READY_NEXT_ACCOUNT,
                AccountTaskState.RECOVERING -> AccountTaskState.STOPPING
                else -> current
            }

            AccountTaskEvent.CLEANUP_STARTED -> when (current) {
                AccountTaskState.STARTING,
                AccountTaskState.RUNNING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.READY_NEXT_ACCOUNT,
                AccountTaskState.RECOVERING,
                AccountTaskState.STOPPING -> AccountTaskState.CLEANING
                else -> current
            }

            AccountTaskEvent.READY_NEXT_ACCOUNT -> when (current) {
                AccountTaskState.CLEANING,
                AccountTaskState.WAITING_NETWORK -> AccountTaskState.READY_NEXT_ACCOUNT
                else -> current
            }

            AccountTaskEvent.COMPLETED -> when (current) {
                AccountTaskState.RUNNING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.READY_NEXT_ACCOUNT,
                AccountTaskState.RECOVERING,
                AccountTaskState.CLEANING -> AccountTaskState.FINISHED
                else -> current
            }

            AccountTaskEvent.FAILED -> when (current) {
                AccountTaskState.STARTING,
                AccountTaskState.RUNNING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.READY_NEXT_ACCOUNT,
                AccountTaskState.RECOVERING,
                AccountTaskState.STOPPING,
                AccountTaskState.CLEANING -> AccountTaskState.ERROR
                else -> current
            }

            AccountTaskEvent.STOPPED -> when (current) {
                AccountTaskState.STARTING,
                AccountTaskState.RUNNING,
                AccountTaskState.WAITING_NETWORK,
                AccountTaskState.READY_NEXT_ACCOUNT,
                AccountTaskState.RECOVERING,
                AccountTaskState.STOPPING,
                AccountTaskState.CLEANING -> AccountTaskState.IDLE
                else -> current
            }
        }
}

internal class AccountTaskStateRegistry {
    private val states = java.util.concurrent.ConcurrentHashMap<String, AccountTaskState>()

    fun current(accountId: String): AccountTaskState =
        states[accountId] ?: AccountTaskState.IDLE

    fun transition(accountId: String, event: AccountTaskEvent): AccountTaskTransition {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        var from = AccountTaskState.IDLE
        var to = AccountTaskState.IDLE
        states.compute(accountId) { _, current ->
            from = current ?: AccountTaskState.IDLE
            to = AccountTaskLifecycle.transition(from, event)
            to
        }
        return AccountTaskTransition(from, to)
    }

    fun remove(accountId: String) {
        states.remove(accountId)
    }
}
