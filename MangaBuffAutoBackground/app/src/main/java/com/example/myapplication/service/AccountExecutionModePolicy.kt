package com.example.myapplication.service

import com.example.myapplication.network.AppProxyConfig
import java.util.Locale

/**
 * WebView's ProxyController override is process-wide. Profiles/cookies/clients are account-local,
 * but running WebViews concurrently with different proxy routes would cross-route account traffic.
 */
internal object AccountExecutionModePolicy {
    fun areProxyRoutesCompatible(configs: List<AppProxyConfig>): Boolean =
        configs.map(::proxyRouteKey).distinct().size <= 1

    fun shouldUseParallel(
        requestedParallel: Boolean,
        configs: List<AppProxyConfig>
    ): Boolean = requestedParallel && areProxyRoutesCompatible(configs)

    private fun proxyRouteKey(config: AppProxyConfig): String {
        if (!config.enabled) return "system"
        return "proxy:${config.host.trim().lowercase(Locale.ROOT)}:${config.port}"
    }
}
