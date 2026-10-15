package com.example.myapplication.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountExecutionQueueTest {
    @Test
    fun secondAccountWaitsUntilFirstReleasesPermit() = runBlocking {
        val queue = AccountExecutionQueue()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val firstJob = launch {
            queue.acquire()
            try {
                firstEntered.complete(Unit)
                releaseFirst.await()
            } finally {
                queue.release()
            }
        }

        firstEntered.await()
        val secondJob = launch {
            queue.acquire()
            try {
                secondEntered.complete(Unit)
            } finally {
                queue.release()
            }
        }

        yield()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        secondEntered.await()
        firstJob.join()
        secondJob.join()
    }

    @Test
    fun queuedMarkerIsSpecificToTheCurrentJob() = runBlocking {
        val queue = AccountExecutionQueue()
        val oldJob = Job()
        val newJob = Job()

        queue.markQueued("account-1", oldJob)
        assertTrue(queue.isQueued("account-1", oldJob))
        assertFalse(queue.isQueued("account-1", newJob))

        queue.markQueued("account-1", newJob)
        queue.forget("account-1", oldJob)
        assertTrue(queue.isQueued("account-1", newJob))

        queue.markAcquired("account-1", newJob)
        assertFalse(queue.isQueued("account-1", newJob))
    }

    @Test
    fun cancelledWaiterDoesNotConsumePermit() = runBlocking {
        val queue = AccountExecutionQueue()
        queue.acquire()

        val waiter = launch { queue.acquire() }
        yield()
        waiter.cancelAndJoin()
        queue.release()

        val acquired = async {
            queue.acquire()
            try {
                "acquired"
            } finally {
                queue.release()
            }
        }
        assertEquals("acquired", acquired.await())
    }
}
