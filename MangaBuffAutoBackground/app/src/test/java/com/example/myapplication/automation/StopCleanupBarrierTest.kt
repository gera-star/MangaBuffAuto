package com.example.myapplication.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopCleanupBarrierTest {
    @Test
    fun waitsForRunJobAndHttpDrainBeforeDestroy() = runBlocking {
        val finallyGate = CompletableDeferred<Unit>()
        val httpGate = CompletableDeferred<Unit>()
        val jobStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            jobStarted.complete(Unit)
            try { CompletableDeferred<Unit>().await() }
            finally { withContext(NonCancellable) { finallyGate.await() } }
        }
        jobStarted.await()
        val events = mutableListOf<String>()
        val cleanup = async {
            StopCleanupBarrier.execute(timeoutMs = 1_000L) {
                job.cancel()
                job.join()
                events += "run-job-finished"
                httpGate.await()
                events += "http-drained"
                events += "webview-destroyed"
            }
        }
        delay(30L)
        assertFalse(cleanup.isCompleted)
        assertTrue(events.isEmpty())
        finallyGate.complete(Unit)
        while (events.isEmpty()) delay(5L)
        assertEquals(listOf("run-job-finished"), events)
        assertFalse(cleanup.isCompleted)
        httpGate.complete(Unit)
        cleanup.await()
        assertEquals(listOf("run-job-finished", "http-drained", "webview-destroyed"), events)
        assertTrue(job.isCompleted)
    }

    @Test
    fun stopTimeoutDoesNotAdvanceToHttpDrainOrDestroy() = runBlocking {
        val finallyGate = CompletableDeferred<Unit>()
        val jobStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            jobStarted.complete(Unit)
            try { CompletableDeferred<Unit>().await() }
            finally { withContext(NonCancellable) { finallyGate.await() } }
        }
        jobStarted.await()
        val events = mutableListOf<String>()
        var timedOut = false
        try {
            StopCleanupBarrier.execute(timeoutMs = 40L) {
                job.cancel()
                job.join()
                events += "run-job-finished"
                events += "http-drained"
                events += "webview-destroyed"
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        } finally {
            finallyGate.complete(Unit)
            job.join()
        }
        assertTrue("unfinished Job must hit finite stop timeout", timedOut)
        assertTrue("HTTP drain and WebView destroy cannot be claimed", events.isEmpty())
    }

    @Test
    fun webViewDestroyErrorPreventsCleanupConfirmation() = runBlocking {
        val events = mutableListOf<String>()
        var cleanupConfirmed = false
        try {
            StopCleanupBarrier.execute(timeoutMs = 1_000L) {
                events += "run-job-finished"
                events += "http-drained"
                throw IllegalStateException("WEBVIEW_DESTROY_FAILED")
            }
            cleanupConfirmed = true
        } catch (e: IllegalStateException) {
            assertEquals("WEBVIEW_DESTROY_FAILED", e.message)
        }
        assertFalse("failed destroy cannot confirm cleanup", cleanupConfirmed)
        assertEquals(listOf("run-job-finished", "http-drained"), events)
    }
}
