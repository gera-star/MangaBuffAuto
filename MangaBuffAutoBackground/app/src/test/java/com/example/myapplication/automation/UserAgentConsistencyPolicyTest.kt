package com.example.myapplication.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentConsistencyPolicyTest {
    private val legacy = "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/124.0.0.0"
    private val actualWebView = "Mozilla/5.0 (Linux; Android 16) Chrome/155.0.0.0 Mobile"

    @Test
    fun adoptsActualWebViewUaForMissingOrLegacyFallback() {
        assertTrue(
            UserAgentConsistencyPolicy.shouldAdoptWebViewUserAgent(
                storedUserAgent = null,
                legacyDefaultUserAgent = legacy,
                webViewUserAgent = actualWebView
            )
        )
        assertTrue(
            UserAgentConsistencyPolicy.shouldAdoptWebViewUserAgent(
                storedUserAgent = legacy,
                legacyDefaultUserAgent = legacy,
                webViewUserAgent = actualWebView
            )
        )
    }

    @Test
    fun keepsExplicitStoredUserAgentStable() {
        assertFalse(
            UserAgentConsistencyPolicy.shouldAdoptWebViewUserAgent(
                storedUserAgent = "Mozilla/5.0 (custom saved login UA)",
                legacyDefaultUserAgent = legacy,
                webViewUserAgent = actualWebView
            )
        )
    }

    @Test
    fun doesNotAdoptAnEmptyRuntimeUserAgent() {
        assertFalse(
            UserAgentConsistencyPolicy.shouldAdoptWebViewUserAgent(
                storedUserAgent = legacy,
                legacyDefaultUserAgent = legacy,
                webViewUserAgent = " "
            )
        )
    }
}
