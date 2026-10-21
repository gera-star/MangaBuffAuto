package com.example.myapplication.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountTaskLifecycleTest {
    @Test
    fun startAndNetworkWaitMoveThroughExplicitLifecycleStates() {
        var state = AccountTaskState.IDLE
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.QUEUED)
        assertEquals(AccountTaskState.STARTING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.NETWORK_LOST)
        assertEquals(AccountTaskState.WAITING_NETWORK, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.NETWORK_RESTORED)
        assertEquals(AccountTaskState.READY_NEXT_ACCOUNT, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.STARTED)
        assertEquals(AccountTaskState.RUNNING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.CLEANUP_STARTED)
        assertEquals(AccountTaskState.CLEANING, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.COMPLETED)
        assertEquals(AccountTaskState.FINISHED, state)
    }

    @Test
    fun resetWaitsForValidatedNetworkBeforeTheNextAccountIsReady() {
        var state = AccountTaskLifecycle.transition(AccountTaskState.RUNNING, AccountTaskEvent.CLEANUP_STARTED)
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.NETWORK_WAIT_STARTED)
        assertEquals(AccountTaskState.WAITING_NETWORK, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.NETWORK_RESTORED)
        assertEquals(AccountTaskState.READY_NEXT_ACCOUNT, state)

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.COMPLETED)
        assertEquals(AccountTaskState.FINISHED, state)
    }

    @Test
    fun stopMovesThroughStoppingAndCleaningBeforeReturningToIdle() {
        var state = AccountTaskLifecycle.transition(AccountTaskState.RUNNING, AccountTaskEvent.STOP_REQUESTED)
        assertEquals(AccountTaskState.STOPPING, state)
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.CLEANUP_STARTED)
        assertEquals(AccountTaskState.CLEANING, state)
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.STOPPED)
        assertEquals(AccountTaskState.IDLE, state)
    }

    @Test
    fun failedAndFinishedTasksCannotResumeWithoutANewQueuedAttempt() {
        var state = AccountTaskLifecycle.transition(AccountTaskState.STARTING, AccountTaskEvent.FAILED)
        assertEquals(AccountTaskState.ERROR, state)
        assertNotEquals(AccountTaskState.RUNNING, AccountTaskLifecycle.transition(state, AccountTaskEvent.STARTED))

        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.QUEUED)
        assertEquals(AccountTaskState.STARTING, state)

        state = AccountTaskLifecycle.transition(AccountTaskState.RUNNING, AccountTaskEvent.CLEANUP_STARTED)
        state = AccountTaskLifecycle.transition(state, AccountTaskEvent.COMPLETED)
        assertEquals(AccountTaskState.FINISHED, state)
        assertEquals(AccountTaskState.FINISHED, AccountTaskLifecycle.transition(state, AccountTaskEvent.FAILED))
    }

    @Test
    fun accountStatesRemainIndependent() {
        val states = AccountTaskStateRegistry()
        states.transition("A", AccountTaskEvent.QUEUED)
        states.transition("A", AccountTaskEvent.STARTED)
        states.transition("B", AccountTaskEvent.QUEUED)
        states.transition("B", AccountTaskEvent.STARTED)

        states.transition("A", AccountTaskEvent.FAILED)
        assertEquals(AccountTaskState.ERROR, states.current("A"))
        assertEquals(AccountTaskState.RUNNING, states.current("B"))

        states.transition("A", AccountTaskEvent.QUEUED)
        assertEquals(AccountTaskState.STARTING, states.current("A"))
        assertEquals(AccountTaskState.RUNNING, states.current("B"))
    }
}
