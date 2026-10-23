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
    fun exactRunIdentityAndPositiveAckAreRequired() {
        assertTrue(WorkerStopConfirmation.accepts(activeRun, activeRun, stopConfirmed = true))
        assertFalse(WorkerStopConfirmation.accepts(activeRun, activeRun, stopConfirmed = false))
    }
}
