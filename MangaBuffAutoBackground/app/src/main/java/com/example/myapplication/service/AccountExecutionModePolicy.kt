package com.example.myapplication.service

import com.example.myapplication.network.AppProxyConfig
import java.util.Locale

/**
 * WebView's ProxyController override is process-wide. Profiles/cookies/clients are account-local,
 * but running WebViews concurrently with different proxy routes would cross-route account traffic.
 */
internal object AccountExecutionModePolicy {
    /** Strict serial mode is independent of the user-facing parallelAccounts preference. */
    const val STRICT_SERIAL_MODE = true

    fun areProxyRoutesCompatible(configs: List<AppProxyConfig>): Boolean =
        configs.map(::proxyRouteKey).distinct().size <= 1

    /** Whether the UI may offer parallel execution at all. Keep false while safety review is open. */
    fun isParallelExecutionAvailable(
        configs: List<AppProxyConfig>,
        workersSupported: Boolean
    ): Boolean = !STRICT_SERIAL_MODE &&
        (workersSupported || areProxyRoutesCompatible(configs))

    /**
     * Одновременный запуск разрешён, если маршруты совпадают либо устройство умеет запускать
     * WebView в отдельных процессах (тогда у каждого маршрута свой процесс и свой прокси).
     */
    fun shouldUseParallel(
        requestedParallel: Boolean,
        configs: List<AppProxyConfig>,
        workersSupported: Boolean = false
    ): Boolean = !STRICT_SERIAL_MODE && requestedParallel &&
        (workersSupported || areProxyRoutesCompatible(configs))

    internal fun proxyRouteKey(config: AppProxyConfig): String {
        if (!config.enabled) return "system"
        config.validateForRouting()
        return "proxy:${config.host.trim().lowercase(Locale.ROOT)}:${config.port}"
    }
}
