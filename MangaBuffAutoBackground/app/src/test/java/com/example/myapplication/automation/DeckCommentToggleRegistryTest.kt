package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DeckCommentToggleRegistryTest {

    @Test
    fun disablingInvalidatesActiveRunEvenAfterReenable() {
        val accountId = "deck-toggle-test-${UUID.randomUUID()}"
        try {
            DeckCommentToggleRegistry.initializeIfAbsent(accountId, true)
            val activeRunState = DeckCommentToggleRegistry.stateFor(accountId, false)
            val runDisableVersion = activeRunState.value.disableVersion

            assertTrue(activeRunState.value.enabled)

            DeckCommentToggleRegistry.setEnabled(accountId, false)
            assertFalse(activeRunState.value.enabled)
            assertEquals(runDisableVersion + 1L, activeRunState.value.disableVersion)
            val disabledAt = activeRunState.value.disabledAtElapsedRealtimeMs
            assertTrue(disabledAt > 0L)

            DeckCommentToggleRegistry.setEnabled(accountId, true)
            assertTrue(activeRunState.value.enabled)
            assertEquals(runDisableVersion + 1L, activeRunState.value.disableVersion)
            // Preserve the OFF timestamp through a rapid ON so shutdown latency remains
            // measurable even if the setting is re-enabled before the coroutine resumes.
            assertEquals(disabledAt, activeRunState.value.disabledAtElapsedRealtimeMs)

            // The original run checks both enabled and its captured version;
            // the ON transition must not make that run valid again.
            assertFalse(
                activeRunState.value.enabled &&
                    activeRunState.value.disableVersion == runDisableVersion
            )
        } finally {
            DeckCommentToggleRegistry.remove(accountId)
        }
    }

    @Test
    fun queuedToggleIsNotOverwrittenByRunInitialization() {
        val accountId = "deck-toggle-queued-test-${UUID.randomUUID()}"
        try {
            DeckCommentToggleRegistry.setEnabled(accountId, false)

            // The account snapshot supplied to the runner may be stale (still ON).
            DeckCommentToggleRegistry.initializeIfAbsent(accountId, true)

            assertFalse(DeckCommentToggleRegistry.stateFor(accountId, true).value.enabled)
        } finally {
            DeckCommentToggleRegistry.remove(accountId)
        }
    }
}
