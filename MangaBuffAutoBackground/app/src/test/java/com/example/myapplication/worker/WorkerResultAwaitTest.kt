package com.example.myapplication.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerResultAwaitTest {
    @Test
    fun completedResultDoesNotNeedStopEventFirst() = runBlocking {
        val result = CompletableDeferred("done")
        val stopped = CompletableDeferred<Unit>()
        assertEquals("done", awaitWorkerResultOrStopped(result, stopped, 500, 100))
    }

    @Test
    fun lostResultTimesOutAfterStopEvent() = runBlocking {
        val result = CompletableDeferred<String>()
        val stopped = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val failure = runCatching {
            awaitWorkerResultOrStopped(result, stopped, runTimeoutMs = 500, resultAfterStopTimeoutMs = 40)
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
    }

    @Test
    fun lostTerminalIpcSequenceHasFiniteRunDeadline() = runBlocking {
        val result = CompletableDeferred<String>()
        val stopped = CompletableDeferred<Unit>()
        val failure = runCatching {
            awaitWorkerResultOrStopped(result, stopped, runTimeoutMs = 40, resultAfterStopTimeoutMs = 500)
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
    }
}
