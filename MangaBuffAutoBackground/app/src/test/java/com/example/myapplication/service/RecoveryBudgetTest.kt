package com.example.myapplication.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryBudgetTest {

    @Test
    fun allowsUpToTheLimitThenRefuses() {
        val budget = RecoveryBudget(maxRecoveries = 3, windowMs = 10_000L)

        assertTrue(budget.tryConsume("a", 0L))
        assertTrue(budget.tryConsume("a", 1_000L))
        assertTrue(budget.tryConsume("a", 2_000L))
        assertFalse(budget.tryConsume("a", 3_000L))
        assertFalse(budget.tryConsume("a", 3_500L))
    }

    @Test
    fun oldRecoveriesLeaveTheWindow() {
        val budget = RecoveryBudget(maxRecoveries = 2, windowMs = 10_000L)

        assertTrue(budget.tryConsume("a", 0L))
        assertTrue(budget.tryConsume("a", 5_000L))
        assertFalse(budget.tryConsume("a", 9_999L))

        // The first entry (t=0) leaves the window exactly at t=10_000.
        assertTrue(budget.tryConsume("a", 10_000L))
        assertFalse(budget.tryConsume("a", 10_001L))
    }

    @Test
    fun accountsHaveIndependentBudgets() {
        val budget = RecoveryBudget(maxRecoveries = 1, windowMs = 10_000L)

        assertTrue(budget.tryConsume("a", 0L))
        assertFalse(budget.tryConsume("a", 1L))
        assertTrue(budget.tryConsume("b", 1L))
    }

    @Test
    fun refusedAttemptDoesNotExtendTheWindow() {
        val budget = RecoveryBudget(maxRecoveries = 1, windowMs = 10_000L)

        assertTrue(budget.tryConsume("a", 0L))
        // Hammering while exhausted must not push the recovery point further out.
        repeat(50) { assertFalse(budget.tryConsume("a", 5_000L)) }

        assertTrue(budget.tryConsume("a", 10_000L))
    }

    @Test
    fun resetOpensAFreshBudgetForOneAccountOnly() {
        val budget = RecoveryBudget(maxRecoveries = 1, windowMs = 10_000L)
        assertTrue(budget.tryConsume("a", 0L))
        assertTrue(budget.tryConsume("b", 0L))

        budget.reset("a")

        assertTrue(budget.tryConsume("a", 1L))
        assertFalse(budget.tryConsume("b", 1L))
    }

    @Test
    fun resetAllClearsEveryAccount() {
        val budget = RecoveryBudget(maxRecoveries = 1, windowMs = 10_000L)
        assertTrue(budget.tryConsume("a", 0L))
        assertTrue(budget.tryConsume("b", 0L))

        budget.resetAll()

        assertEquals(0, budget.used("a", 1L))
        assertTrue(budget.tryConsume("a", 1L))
        assertTrue(budget.tryConsume("b", 1L))
    }

    @Test
    fun clockGoingBackwardsDoesNotReopenTheBudget() {
        val budget = RecoveryBudget(maxRecoveries = 1, windowMs = 10_000L)
        assertTrue(budget.tryConsume("a", 100_000L))

        assertFalse(budget.tryConsume("a", 50_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveLimit() {
        RecoveryBudget(maxRecoveries = 0, windowMs = 1_000L)
    }
}
