package com.example.myapplication.slot

/**
 * A WebView proxy override and data-directory suffix are process-wide. A slot process therefore
 * binds to one immutable route for its entire lifetime. Closing an Activity does not release the
 * binding: Chromium may still own requests/sockets and ProxyController has no per-WebView reset.
 *
 * The SOCKS5 bridge is intentionally owned here (process lifetime), not by an Activity, so its
 * loopback endpoint does not change when a slot Activity is recreated.
 */
internal class SlotProcessRouteOwner(
    private val socksBridgeFactory: (SlotRoute) -> Socks5ConnectBridge = { route ->
        val bridge = Socks5ConnectBridge(route.host.trim(), route.port, route.username, route.password)
        try {
            bridge.start()
        } catch (e: Exception) {
            bridge.close()
            throw e
        }
    }
) {
    private var binding: SlotProcessRouteBinding? = null
    private var unsafeReason: String? = null

    @Synchronized
    fun reserve(route: SlotRoute): SlotRouteReservation {
        if (unsafeReason != null) {
            return SlotRouteReservation.Rejected(
                "Слот заблокирован после ошибки маршрута. Перезапусти приложение перед повторным использованием."
            )
        }
        route.validate()?.let { return SlotRouteReservation.Rejected(it) }

        val requestedKey = route.bindingKey()
        val current = binding
        if (current != null) {
            if (current.routeKey != requestedKey) {
                return SlotRouteReservation.Rejected(
                    "Этот процесс уже закреплён за другим маршрутом. Смена прокси/режима «без прокси» до перезапуска процесса запрещена."
                )
            }
            if (current.proxyRule != null && !current.proxyApplied) {
                return SlotRouteReservation.Rejected(
                    "Применение прокси не подтверждено. Слот нельзя безопасно использовать повторно; перезапусти приложение."
                )
            }
            return SlotRouteReservation.Accepted(binding = current, applyProxyOverride = false)
        }

        val created = try {
            when (route.type) {
                SlotProxyType.NONE -> SlotProcessRouteBinding(
                    routeKey = requestedKey,
                    proxyRule = null,
                    proxyUser = "",
                    proxyPassword = "",
                    proxyAuthority = "",
                    socksBridge = null,
                    proxyApplied = true
                )
                SlotProxyType.HTTP -> SlotProcessRouteBinding(
                    routeKey = requestedKey,
                    proxyRule = "http://${route.authorityHost()}:${route.port}",
                    proxyUser = route.username,
                    proxyPassword = route.password,
                    proxyAuthority = route.host.trim(),
                    socksBridge = null,
                    proxyApplied = false
                )
                SlotProxyType.SOCKS5 -> {
                    val bridge = socksBridgeFactory(route)
                    SlotProcessRouteBinding(
                        routeKey = requestedKey,
                        proxyRule = "http://127.0.0.1:${bridge.port}",
                        proxyUser = Socks5ConnectBridge.AUTH_USER,
                        proxyPassword = bridge.accessToken,
                        proxyAuthority = "127.0.0.1",
                        socksBridge = bridge,
                        proxyApplied = false
                    )
                }
            }
        } catch (_: Exception) {
            unsafeReason = "ROUTE_INITIALIZATION_FAILED"
            return SlotRouteReservation.Rejected(
                "Не удалось инициализировать прокси. Слот заблокирован до перезапуска приложения."
            )
        }
        binding = created
        return SlotRouteReservation.Accepted(
            binding = created,
            applyProxyOverride = created.proxyRule != null
        )
    }

    @Synchronized
    fun markProxyApplied(candidate: SlotProcessRouteBinding): Boolean {
        if (binding !== candidate || unsafeReason != null) return false
        candidate.proxyApplied = true
        return true
    }

    fun quarantine(candidate: SlotProcessRouteBinding, reason: String) {
        val closeBridge = synchronized(this) {
            if (binding !== candidate) return
            unsafeReason = reason.ifBlank { "PROXY_STATE_UNCERTAIN" }
            candidate.socksBridge
        }
        runCatching { closeBridge?.close() }
    }

    @Synchronized
    internal fun isBoundForTest(): Boolean = binding != null

    @Synchronized
    internal fun isQuarantinedForTest(): Boolean = unsafeReason != null
}

internal class SlotProcessRouteBinding(
    val routeKey: String,
    val proxyRule: String?,
    val proxyUser: String,
    val proxyPassword: String,
    val proxyAuthority: String,
    val socksBridge: Socks5ConnectBridge?,
    @Volatile var proxyApplied: Boolean
)

internal sealed class SlotRouteReservation {
    data class Accepted(
        val binding: SlotProcessRouteBinding,
        val applyProxyOverride: Boolean
    ) : SlotRouteReservation()

    data class Rejected(val message: String) : SlotRouteReservation()
}

/** This singleton exists independently inside each :slotN Android process. */
internal object SlotProcessRouteOwnerRegistry {
    val owner = SlotProcessRouteOwner()
}
