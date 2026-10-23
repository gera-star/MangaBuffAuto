package com.example.myapplication.slot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotWebViewCleanupTest {
    @Test
    fun successfulCleanupDoesNotQuarantine() {
        var failures = 0
        val cleaned = SlotWebViewCleanup.cleanup(
            stopLoading = {},
            destroy = {},
            onFailure = { failures++ }
        )
        assertTrue(cleaned)
        assertTrue(failures == 0)
    }

    @Test
    fun destroyFailureIsReportedAndBothOperationsAreAttempted() {
        var stopAttempts = 0
        var destroyAttempts = 0
        var quarantineRequests = 0
        val cleaned = SlotWebViewCleanup.cleanup(
            stopLoading = { stopAttempts++ },
            destroy = { destroyAttempts++; throw IllegalStateException("destroy failed") },
            onFailure = { quarantineRequests++ }
        )
        assertFalse(cleaned)
        assertTrue(stopAttempts == 1)
        assertTrue(destroyAttempts == 1)
        assertTrue(quarantineRequests == 1)
    }

    @Test
    fun stopLoadingFailureDoesNotSkipDestroyAndRequiresQuarantine() {
        var destroyAttempts = 0
        var quarantineRequests = 0
        val cleaned = SlotWebViewCleanup.cleanup(
            stopLoading = { throw IllegalStateException("stop failed") },
            destroy = { destroyAttempts++ },
            onFailure = { quarantineRequests++ }
        )
        assertFalse(cleaned)
        assertTrue(destroyAttempts == 1)
        assertTrue(quarantineRequests == 1)
    }
}
