package com.example.myapplication.web

import com.example.myapplication.network.AppProxyConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * ProxyController configures every WebView in this Android process, not a single WebView.
 * Therefore the account viewer process must keep one immutable route for its entire lifetime.
 * A failed or timed-out apply permanently quarantines this owner; Activity recreation is not a reset.
 */
internal class AccountWebViewerProcessRouteOwner {
    private var boundKey: String? = null
    private var applied = false
    private var quarantined = false

    @Synchronized
    fun reserve(config: AppProxyConfig): AccountWebViewerRouteReservation {
        if (quarantined) {
            return AccountWebViewerRouteReservation.Rejected("Viewer proxy state is uncertain; restart the viewer process.")
        }
        try {
            config.validateForRouting()
        } catch (_: IllegalArgumentException) {
            return AccountWebViewerRouteReservation.Rejected("Invalid proxy configuration.")
        }

        val requestedKey = routeKey(config)
        val current = boundKey
        if (current != null) {
            if (current != requestedKey) {
                return AccountWebViewerRouteReservation.Rejected(
                    "This viewer process is bound to another proxy route. Use the same route or restart the process."
                )
            }
            if (!applied) {
                quarantined = true
                return AccountWebViewerRouteReservation.Rejected(
                    "Proxy application is still pending; the viewer process has been quarantined."
                )
            }
            return AccountWebViewerRouteReservation.Accepted(requestedKey, applyProxyOverride = false)
        }

        boundKey = requestedKey
        applied = false
        return AccountWebViewerRouteReservation.Accepted(requestedKey, applyProxyOverride = true)
    }

    @Synchronized
    fun markApplied(key: String): Boolean {
        if (quarantined || boundKey != key || applied) return false
        applied = true
        return true
    }

    @Synchronized
    fun quarantine() {
        quarantined = true
    }

    @Synchronized
    internal fun isQuarantinedForTest(): Boolean = quarantined

    @Synchronized
    internal fun isAppliedForTest(): Boolean = applied

    private fun routeKey(config: AppProxyConfig): String {
        if (!config.enabled) return "system-route"
        val canonical = listOf(
            "proxy",
            config.host.trim().lowercase(Locale.ROOT),
            config.port.toString(),
            config.username,
            config.password
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal sealed class AccountWebViewerRouteReservation {
    data class Accepted(val key: String, val applyProxyOverride: Boolean) : AccountWebViewerRouteReservation()
    data class Rejected(val reason: String) : AccountWebViewerRouteReservation()
}

/** Process-local singleton survives AccountWebViewerActivity recreation. */
internal object AccountWebViewerProcessRouteOwnerRegistry {
    val owner = AccountWebViewerProcessRouteOwner()
}
