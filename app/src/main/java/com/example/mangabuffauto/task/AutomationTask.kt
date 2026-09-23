package com.example.mangabuffauto.task

data class AutomationTaskSpec(
    val id: String,
    val targetUrl: String,
    val timeoutMs: Long = 10 * 60 * 1000L,
    val maxRetries: Int = 1,
    val script: String
)

interface AutomationTask {

    val spec: AutomationTaskSpec

    /**
     * Determines if the current URL is part of this task's intended lifecycle.
     */
    fun isUrlExpected(url: String): Boolean {
        val target = spec.targetUrl.substringBefore("?")
        return url.startsWith(target)
    }

    /**
     * Determines if a new document loading requires a fresh script injection.
     */
    fun shouldInjectForDocument(url: String): Boolean = true
}