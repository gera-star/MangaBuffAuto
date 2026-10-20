package com.example.myapplication.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTaskLifecycleTest {
    @Test
    fun networkLossPausesAndResumeContinuesTheSameRun() {
        var state = AccountTaskState.WAITING
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.QUEUED)
        assertEquals(AccountTaskState.WAITING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.STARTED)
        assertEquals(AccountTaskState.RUNNING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.NETWORK_LOST)
        assertEquals(AccountTaskState.PAUSED, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.NETWORK_RESTORED)
        assertEquals(AccountTaskState.RUNNING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.COMPLETED)
        assertEquals(AccountTaskState.COMPLETED, state)
    }

    @Test
    fun recoveryCanRestartButAnInvalidEventCannotReopenCompletedTask() {
        var state = AccountTaskState.RUNNING
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.RECOVERY_REQUESTED)
        assertEquals(AccountTaskState.RECOVERING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.STARTED)
        assertEquals(AccountTaskState.RUNNING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.COMPLETED)
        assertEquals(AccountTaskState.COMPLETED, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.NETWORK_RESTORED)
        assertEquals(AccountTaskState.COMPLETED, state)
    }

    @Test
    fun aFailureIsTerminalUntilANewRunIsQueued() {
        var state = AccountTaskState.WAITING
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.FAILED)
        assertEquals(AccountTaskState.ERROR, state)
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.STARTED)
        assertEquals(AccountTaskState.ERROR, state)
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.QUEUED)
        assertEquals(AccountTaskState.WAITING, state)
    }

    @Test
    fun failingOrStoppingAccountADoesNotChangeAccountB() {
        val states = AccountTaskStateRegistry()
        states.transition("account-A", AccountTaskEvent.QUEUED)
        states.transition("account-A", AccountTaskEvent.STARTED)
        states.transition("account-B", AccountTaskEvent.QUEUED)
        states.transition("account-B", AccountTaskEvent.STARTED)

        states.transition("account-A", AccountTaskEvent.FAILED)
        assertEquals(AccountTaskState.ERROR, states.current("account-A"))
        assertEquals(AccountTaskState.RUNNING, states.current("account-B"))

        states.transition("account-A", AccountTaskEvent.QUEUED)
        states.transition("account-A", AccountTaskEvent.STARTED)
        states.transition("account-A", AccountTaskEvent.STOPPED)
        assertEquals(AccountTaskState.WAITING, states.current("account-A"))
        assertEquals(AccountTaskState.RUNNING, states.current("account-B"))
    }

    @Test
    fun stoppingAnActiveRunReturnsItToWaiting() {
        val next = AccountTaskLifecycle.transition(AccountTaskState.RUNNING, AccountTaskEvent.STOPPED)
        assertEquals(AccountTaskState.WAITING, next)
    }
}
