package com.example.myapplication.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountExecutionModePolicyTest {
    @Test
    fun allowsParallelModeOnlyWhenNoAccountProxyIsEnabled() {
        assertTrue(
            AccountExecutionModePolicy.shouldUseParallel(
                requestedParallel = true,
                anyEnabledAccountProxy = false
            )
        )
        assertFalse(
            AccountExecutionModePolicy.shouldUseParallel(
                requestedParallel = true,
                anyEnabledAccountProxy = true
            )
        )
        assertFalse(
            AccountExecutionModePolicy.shouldUseParallel(
                requestedParallel = false,
                anyEnabledAccountProxy = false
            )
        )
    }
}
