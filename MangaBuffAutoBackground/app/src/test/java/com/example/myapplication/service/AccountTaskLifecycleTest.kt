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
    fun stoppingAnActiveRunReturnsItToWaiting() {
        val next = AccountTaskLifecycle.transition(AccountTaskState.RUNNING, AccountTaskEvent.STOPPED)
        assertEquals(AccountTaskState.WAITING, next)
    }
}
