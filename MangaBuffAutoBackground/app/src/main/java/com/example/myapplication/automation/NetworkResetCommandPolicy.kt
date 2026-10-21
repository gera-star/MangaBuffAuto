package com.example.myapplication.automation

/** Allowlist for the shell-identity Shizuku service; no arbitrary shell strings are executed. */
internal object NetworkResetCommandPolicy {
    private const val AIRPLANE_ENABLE = "cmd connectivity airplane-mode enable"
    private const val AIRPLANE_DISABLE = "cmd connectivity airplane-mode disable"

    fun argumentsFor(command: String): List<String>? =
        when (command.trim()) {
            AIRPLANE_ENABLE -> listOf("cmd", "connectivity", "airplane-mode", "enable")
            AIRPLANE_DISABLE -> listOf("cmd", "connectivity", "airplane-mode", "disable")
            else -> null
        }
}
