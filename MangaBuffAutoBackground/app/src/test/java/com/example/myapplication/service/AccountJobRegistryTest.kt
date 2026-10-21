package com.example.myapplication.service

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

class AccountJobRegistryTest {
    @Test
    fun cancellingAccountADoesNotCancelAccountB() = runBlocking {
        val registry = AccountJobRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val jobA = scope.launch { awaitCancellation() }
            val jobB = scope.launch { awaitCancellation() }
            registry["A"] = jobA
            registry["B"] = jobB

            registry.cancelAndRemove("A")

            assertFalse(jobA.isActive)
            assertTrue(jobB.isActive)
            assertSame(jobB, registry["B"])
            assertNull(registry["A"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun finishingAccountCanBeStoppedWithoutCancellingAnotherAccount() = runBlocking {
        val registry = AccountJobRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val jobA = scope.launch { awaitCancellation() }
            val jobB = scope.launch { awaitCancellation() }
            registry["A"] = jobA
            registry["B"] = jobB

            assertTrue(registry.markFinishing("A", jobA))
            assertNull(registry["A"])
            assertTrue(registry.isFinishing("A", jobA))
            assertTrue(registry.hasFinishingJobs())

            registry.cancelFinishingAndRemove("A", "EXPLICIT_ACCOUNT_STOP")

            assertFalse(jobA.isActive)
            assertFalse(registry.isFinishing("A", jobA))
            assertTrue(jobB.isActive)
            assertSame(jobB, registry["B"])
            assertFalse(registry.hasFinishingJobs())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failureAndFinallyCleanupForAccountADoNotRemoveOrCancelAccountB() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, throwable -> failures += throwable }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
        val registry = AccountJobRegistry()
        try {
            val bStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val jobB = scope.launch {
                bStarted.complete(Unit)
                awaitCancellation()
            }
            registry["B"] = jobB

            val jobA = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    throw IllegalStateException("account-A failure")
                } finally {
                    coroutineContext[Job]?.let { registry.removeIfCurrent("A", it) }
                }
            }
            registry["A"] = jobA
            jobA.start()
            bStarted.await()
            jobA.join()

            assertFalse(jobA.isActive)
            assertTrue(failures.any { it.message == "account-A failure" })
            assertTrue(jobB.isActive)
            assertSame(jobB, registry["B"])
            assertNull(registry["A"])
        } finally {
            scope.cancel()
        }
    }
}
