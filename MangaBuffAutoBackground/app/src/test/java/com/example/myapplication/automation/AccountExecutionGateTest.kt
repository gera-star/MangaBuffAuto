package com.example.myapplication.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountExecutionGateTest {
    @Test
    fun secondAccountWaitsUntilCurrentPermitIsReleased() = runBlocking {
        val firstPermit = AccountExecutionGate.acquire()
        val secondAttemptStarted = CompletableDeferred<Unit>()
        val second = async {
            secondAttemptStarted.complete(Unit)
            AccountExecutionGate.acquire()
        }

        secondAttemptStarted.await()
        delay(50)
        assertFalse("second account must wait for the first account", second.isCompleted)

        firstPermit.release()
        val secondPermit = withTimeout(1_000) { second.await() }
        secondPermit.release()
    }

    @Test
    fun releasingPermitTwiceDoesNotUnlockAnotherAccount() = runBlocking {
        val first = AccountExecutionGate.acquire()
        first.release()
        first.release()

        val second = withTimeout(1_000) { AccountExecutionGate.acquire() }
        second.release()
    }
}
