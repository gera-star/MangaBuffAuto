package com.example.myapplication.automation

/** Keeps native HTTP headers consistent with the real WebView UA without overriding explicit account values. */
internal object UserAgentConsistencyPolicy {
    fun shouldAdoptWebViewUserAgent(
        storedUserAgent: String?,
        legacyDefaultUserAgent: String,
        webViewUserAgent: String
    ): Boolean {
        val stored = storedUserAgent?.trim().orEmpty()
        val fallback = legacyDefaultUserAgent.trim()
        val actual = webViewUserAgent.trim()
        return actual.isNotEmpty() && (stored.isEmpty() || stored == fallback)
    }
}
