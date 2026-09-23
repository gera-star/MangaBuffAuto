package com.example.mangabuffauto.task

data class AutomationTaskSpec(
    val id: String,
    val targetUrl: String,
    val timeoutMs: Long = 10 * 60 * 1000L,
    val maxRetries: Int = 1,
    val script: String
)

interface AutomationTask {
    fun isUrlExpected(url: String): Boolean {
        val target = spec.targetUrl.substringBefore("?")
        return url.startsWith(target)
    }
    fun shouldInjectForDocument(url: String): Boolean = true
    val spec: AutomationTaskSpec
}
