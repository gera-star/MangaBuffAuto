package com.example.myapplication.service

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local leases for WebView's process-wide ProxyController.
 *
 * Several accounts may hold a lease concurrently only while they use the same proxy route.
 * A different route waits for every current lease to be released, preventing an override change
 * from rerouting network requests of a WebView that is already running.
 */
internal class ProxyRouteExecutionGate {
    private val lock = Any()
    private var activeRoute: String? = null
    private var activeLeases = 0
    private var changed = CompletableDeferred<Unit>()

    suspend fun acquire(route: String): Permit {
        while (true) {
            val signalToAwait: CompletableDeferred<Unit>?
            synchronized(lock) {
                val canJoinActiveRoute = activeLeases > 0 && activeRoute == route
                val canStartRoute = activeLeases == 0
                if (canJoinActiveRoute || canStartRoute) {
                    if (canStartRoute) activeRoute = route
                    activeLeases++
                    return Permit(route) { release(route) }
                }
                signalToAwait = changed
            }
            // Await outside the monitor so release() can update the state and signal all waiters.
            signalToAwait!!.await()
        }
    }

    private fun release(route: String) {
        val signal = synchronized(lock) {
            check(activeLeases > 0 && activeRoute == route) {
                "Proxy route lease released for a route that is not active"
            }
            activeLeases--
            if (activeLeases == 0) {
                activeRoute = null
                val completed = changed
                changed = CompletableDeferred()
                completed
            } else {
                null
            }
        }
        signal?.complete(Unit)
    }

    internal class Permit(
        private val route: String,
        private val releaseAction: (String) -> Unit
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) releaseAction(route)
        }
    }
}
