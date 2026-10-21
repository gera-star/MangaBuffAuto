package com.example.myapplication.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPauseResumePolicyTest {
    @Test
    fun resumesOnlyTheSamePersistedTaskAfterNetworkPause() {
        assertTrue(
            NetworkPauseResumePolicy.shouldResume(
                networkPauseRequested = true,
                persistedTaskMatches = true,
                stoppingExplicitly = false,
                accountOffline = false
            )
        )
    }

    @Test
    fun neverResumesAfterExplicitStopOfflineOrTaskReplacement() {
        assertFalse(
            NetworkPauseResumePolicy.shouldResume(
                networkPauseRequested = true,
                persistedTaskMatches = true,
                stoppingExplicitly = true,
                accountOffline = false
            )
        )
        assertFalse(
            NetworkPauseResumePolicy.shouldResume(
                networkPauseRequested = true,
                persistedTaskMatches = true,
                stoppingExplicitly = false,
                accountOffline = true
            )
        )
        assertFalse(
            NetworkPauseResumePolicy.shouldResume(
                networkPauseRequested = true,
                persistedTaskMatches = false,
                stoppingExplicitly = false,
                accountOffline = false
            )
        )
        assertFalse(
            NetworkPauseResumePolicy.shouldResume(
                networkPauseRequested = false,
                persistedTaskMatches = true,
                stoppingExplicitly = false,
                accountOffline = false
            )
        )
    }
}
