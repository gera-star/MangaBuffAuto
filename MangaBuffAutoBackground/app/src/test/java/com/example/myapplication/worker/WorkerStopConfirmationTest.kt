package com.example.myapplication.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerStopConfirmationTest {
    private val activeRun = WorkerRunIdentity("request-new", "account-1", "run-new", "mb_account-1", "w0", "system")

    @Test
    fun lateStopAckFromPriorRunIdCannotConfirmNewRun() {
        val oldRun = activeRun.copy(requestId = "request-old", runId = "run-old")
        assertFalse(WorkerStopConfirmation.accepts(activeRun, oldRun, stopConfirmed = true))
    }

    @Test
    fun lostStopIpcIsNotAConfirmedStop() {
        assertFalse(WorkerStopConfirmation.accepts(activeRun, received = null, stopConfirmed = false))
    }

    @Test
    fun staleOrNonFreeStateCannotConfirmWorkerRelease() {
        assertFalse(
            WorkerStopConfirmation.acceptsRelease(
                activeRun, activeRun.copy(runId = "old-run"), state = "FREE"
            )
        )
        assertFalse(WorkerStopConfirmation.acceptsRelease(activeRun, activeRun, state = "VERIFIED_CLOSED"))
        assertTrue(WorkerStopConfirmation.acceptsRelease(activeRun, activeRun, state = "FREE"))
    }

    @Test
    fun doesNotSendDuplicateStopWhenStoppedEventWasAlreadyAccepted() {
        assertFalse(WorkerStopConfirmation.shouldSendStopCommand(stopEventAlreadyAccepted = true))
        assertTrue(WorkerStopConfirmation.shouldSendStopCommand(stopEventAlreadyAccepted = false))
    }

    @Test
    fun everyIdentityFieldMustMatchForStopAndRelease() {
        val staleVariants = listOf(
            activeRun.copy(requestId = "older-request"),
            activeRun.copy(accountId = "other-account"),
            activeRun.copy(runId = "older-run"),
            activeRun.copy(profileId = "other-profile"),
            activeRun.copy(containerId = "w1"),
            activeRun.copy(routeId = "proxy:other:8080")
        )
        staleVariants.forEach { stale ->
            assertFalse(WorkerStopConfirmation.matchesIdentity(activeRun, stale))
            assertFalse(WorkerStopConfirmation.accepts(activeRun, stale, stopConfirmed = true))
            assertFalse(WorkerStopConfirmation.acceptsRelease(activeRun, stale, state = "FREE"))
        }
        assertTrue(WorkerStopConfirmation.matchesIdentity(activeRun, activeRun))
    }

    @Test
    fun failedCleanupUncertainRouteOrPoisonedWorkerCannotConfirmStop() {
        assertFalse(WorkerStopConfirmation.shouldEmitStopped(
            runtimeCleanupSucceeded = false,
            routeStateCertain = true,
            poisoned = false
        ))
        assertFalse(WorkerStopConfirmation.shouldEmitStopped(
            runtimeCleanupSucceeded = true,
            routeStateCertain = false,
            poisoned = false
        ))
        assertFalse(WorkerStopConfirmation.shouldEmitStopped(
            runtimeCleanupSucceeded = true,
            routeStateCertain = true,
            poisoned = true
        ))
        assertTrue(WorkerStopConfirmation.shouldEmitStopped(
            runtimeCleanupSucceeded = true,
            routeStateCertain = true,
            poisoned = false
        ))
    }

    @Test
    fun exactRunIdentityAndPositiveAckAreRequired() {
        assertTrue(WorkerStopConfirmation.accepts(activeRun, activeRun, stopConfirmed = true))
        assertFalse(WorkerStopConfirmation.accepts(activeRun, activeRun, stopConfirmed = false))
    }
}
