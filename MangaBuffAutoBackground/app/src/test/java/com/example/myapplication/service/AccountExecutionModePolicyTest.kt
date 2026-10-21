package com.example.myapplication.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountExecutionModePolicyTest {
    @Test
    fun neverAllowsConcurrentAccountExecution() {
        assertFalse(AccountExecutionModePolicy.shouldUseParallel())
    }
}
