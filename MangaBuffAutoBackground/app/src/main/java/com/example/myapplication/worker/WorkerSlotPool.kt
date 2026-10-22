package com.example.myapplication.worker

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exclusive leases for worker processes. A worker becomes reusable only after the caller
 * confirms that its run's WebView/runtime was closed. Any unverified release quarantines it.
 */
internal class WorkerSlotPool(private val size: Int) {
    private val lock = Any()
    private val activeRunIds = arrayOfNulls<String>(size)
    private val lastRoute = arrayOfNulls<String>(size)
    private val leaseCounts = IntArray(size)
    private val quarantined = BooleanArray(size)
    private val quarantineReasons = arrayOfNulls<String>(size)
    private var changed = CompletableDeferred<Unit>()

    init {
        require(size > 0)
    }

    /** Legacy/test convenience. Production callers should supply the complete run identity. */
    suspend fun acquire(route: String): Lease =
        acquire(accountId = "", profileId = "", route = route, runId = UUID.randomUUID().toString())

    suspend fun acquire(
        accountId: String,
        profileId: String,
        route: String,
        runId: String
    ): Lease {
        require(route.isNotBlank()) { "routeId is required" }
        require(runId.isNotBlank()) { "runId is required" }

        while (true) {
            val signal: CompletableDeferred<Unit>
            synchronized(lock) {
                if (quarantined.all { it }) {
                    throw WorkerPoolUnavailableException("All worker slots are quarantined")
                }
                val free = (0 until size).filter { leaseCounts[it] == 0 && !quarantined[it] }
                // Prefer an idle process already configured for this route, but never share a
                // live worker between two reservations.
                val index = free.firstOrNull { lastRoute[it] == route } ?: free.firstOrNull()
                if (index != null) {
                    leaseCounts[index] = 1
                    activeRunIds[index] = runId
                    lastRoute[index] = route
                    return Lease(
                        index = index,
                        accountId = accountId,
                        profileId = profileId,
                        containerId = "w$index",
                        route = route,
                        runId = runId
                    ) { verifiedClosed, failureReason ->
                        release(index, runId, verifiedClosed, failureReason)
                    }
                }
                signal = changed
            }
            signal.await()
        }
    }

    private fun release(index: Int, runId: String, verifiedClosed: Boolean, failureReason: String?) {
        val signal = synchronized(lock) {
            check(leaseCounts[index] == 1 && activeRunIds[index] == runId) {
                "Worker lease identity changed before release"
            }
            leaseCounts[index] = 0
            activeRunIds[index] = null
            if (!verifiedClosed || failureReason != null) {
                quarantined[index] = true
                quarantineReasons[index] = failureReason ?: "STOP_NOT_CONFIRMED"
            }
            val previous = changed
            changed = CompletableDeferred()
            previous
        }
        signal.complete(Unit)
    }

    internal fun isQuarantined(index: Int): Boolean = synchronized(lock) {
        require(index in 0 until size)
        quarantined[index]
    }

    internal fun quarantineReason(index: Int): String? = synchronized(lock) {
        require(index in 0 until size)
        quarantineReasons[index]
    }

    class Lease internal constructor(
        val index: Int,
        val accountId: String,
        val profileId: String,
        val containerId: String,
        val route: String,
        val runId: String,
        private val releaseAction: (verifiedClosed: Boolean, failureReason: String?) -> Unit
    ) {
        private val released = AtomicBoolean(false)
        private val verifiedClosed = AtomicBoolean(false)
        @Volatile private var failureReason: String? = null

        /** Call only after EV_STOPPED for this exact account/profile/container/route/run. */
        fun markVerifiedClosed() {
            check(!released.get()) { "Cannot verify a released worker lease" }
            verifiedClosed.set(true)
        }

        fun quarantine(reason: String) {
            if (reason.isNotBlank() && !released.get()) failureReason = reason
        }

        /** Without a matching stop acknowledgement, release quarantines rather than frees the process. */
        fun release() {
            if (released.compareAndSet(false, true)) {
                releaseAction(verifiedClosed.get() && failureReason == null, failureReason)
            }
        }
    }
}

internal class WorkerPoolUnavailableException(message: String) : IllegalStateException(message)
