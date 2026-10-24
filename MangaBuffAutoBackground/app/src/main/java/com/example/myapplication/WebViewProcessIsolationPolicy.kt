package com.example.myapplication

/**
 * Maps only explicitly declared WebView-owning processes to their dedicated data-directory suffix.
 *
 * Android process names use "<package>:<process>". Accept exactly one process separator and
 * known process names only, so malformed names cannot be mistaken for isolated containers.
 */
internal object WebViewProcessIsolationPolicy {
    fun suffixFor(processName: String?): String? {
        if (processName.isNullOrBlank()) return null
        val separator = processName.indexOf(':')
        if (separator <= 0 || separator != processName.lastIndexOf(':')) return null

        return when (val process = processName.substring(separator + 1)) {
            "slot0", "slot1", "slot2", "w0", "w1", "w2", "w3", "webviewer" -> process
            else -> null
        }
    }
}
