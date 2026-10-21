package com.example.myapplication.service

/**
 * WebView's ProxyController override is process-wide. Separate account profiles are safe to run
 * concurrently only when no account-specific WebView proxy is enabled.
 */
internal object AccountExecutionModePolicy {
    fun shouldUseParallel(
        requestedParallel: Boolean,
        anyEnabledAccountProxy: Boolean
    ): Boolean = requestedParallel && !anyEnabledAccountProxy
}
