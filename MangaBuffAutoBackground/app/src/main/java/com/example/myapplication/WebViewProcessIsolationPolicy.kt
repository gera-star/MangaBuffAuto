package com.example.myapplication

/**
 * Maps only explicitly declared WebView-owning processes to their dedicated data-directory suffix.
 *
 * Android process names use "<package>:<process>". Require the exact application ID as well as
 * one known process suffix; a similarly named or malformed process must never be admitted.
 */
internal object WebViewProcessIsolationPolicy {
    fun suffixFor(processName: String?, applicationId: String): String? {
        if (processName.isNullOrBlank() || applicationId.isBlank()) return null
        val expectedPrefix = "$applicationId:"
        if (!processName.startsWith(expectedPrefix)) return null

        val process = processName.removePrefix(expectedPrefix)
        if (process.isEmpty() || ':' in process) return null

        return when (process) {
            "slot0", "slot1", "slot2", "w0", "w1", "w2", "w3", "webviewer" -> process
            else -> null
        }
    }
}
