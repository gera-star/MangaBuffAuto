package com.example.myapplication

import com.example.myapplication.slot.SlotLauncher

/**
 * Maps only explicitly declared WebView-owning processes to their dedicated data-directory suffix.
 *
 * Keep this mapping strict: unknown processes must not accidentally receive a shared/default
 * WebView directory while being treated as an isolated container.
 */
internal object WebViewProcessIsolationPolicy {
    private val workerProcessPattern = Regex("^.+:w([0-3])$")

    fun suffixFor(processName: String?): String? {
        if (processName.isNullOrBlank()) return null

        SlotLauncher.slotIndexOf(processName)?.let { return "slot$it" }
        if (processName.endsWith(":webviewer")) return "webviewer"

        val workerIndex = workerProcessPattern.matchEntire(processName)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return workerIndex?.let { "w$it" }
    }
}
