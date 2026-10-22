package com.example.myapplication.worker

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class WorkerSlotState {
    FREE, RESERVED, PROXY_READY, RUNNING, STOPPING, VERIFIED_CLOSED, QUARANTINED
}

/** A worker is reusable only after confirmation that the run's runtime and WebView have been closed. */
internal class WorkerSlotPool(private val size: Int) {
    private val lock = Any()
    private val states = Array(size) { WorkerSlotState.FREE }
    private val activeRunIds = arrayOfNulls<String>(size)
    private val lastRoute = arrayOfNulls<String>(size)
    private val quarantineReasons = arrayOfNulls<String>(size)
    private var changed = CompletableDeferred<Unit>()

    init { require(size > 0) }

    /** Test/legacy convenience. Production should supply full run identity. */
    suspend fun acquire(route: String): Lease =
        acquire("", "", route, UUID.randomUUID().toString())

    suspend fun acquire(accountId: String, profileId: String, route: String, runId: String): Lease {
        require(route.isNotBlank()) { "routeId is required" }
        require(runId.isNotBlank()) { "runId is required" }
        while (true) {
            val signal: CompletableDeferred<Unit>
            synchronized(lock) {
                if (states.all { it == WorkerSlotState.QUARANTINED }) {
                    throw WorkerPoolUnavailableException("All worker slots are quarantined")
                }
                val free = states.indices.filter { states[it] == WorkerSlotState.FREE }
                val index = free.firstOrNull { lastRoute[it] == route } ?: free.firstOrNull()
                if (index != null) {
                    states[index] = WorkerSlotState.RESERVED
                    activeRunIds[index] = runId
                    lastRoute[index] = route
                    return Lease(index, accountId, profileId, "w$index", route, runId,
                        { next -> transition(index, runId, next) },
                        { verified, reason -> release(index, runId, verified, reason) })
                }
                signal = changed
            }
            signal.await()
        }
    }

    private fun transition(index: Int, runId: String, next: WorkerSlotState) {
        synchronized(lock) {
            check(index in states.indices && activeRunIds[index] == runId) {
                "Worker state transition has stale runId"
            }
            val current = states[index]
            if (current == next) return
            val allowed = when (current) {
                WorkerSlotState.RESERVED -> setOf(WorkerSlotState.PROXY_READY, WorkerSlotState.STOPPING)
                WorkerSlotState.PROXY_READY -> setOf(WorkerSlotState.RUNNING, WorkerSlotState.STOPPING)
                WorkerSlotState.RUNNING -> setOf(WorkerSlotState.STOPPING)
                WorkerSlotState.STOPPING -> setOf(WorkerSlotState.VERIFIED_CLOSED)
                WorkerSlotState.VERIFIED_CLOSED, WorkerSlotState.FREE, WorkerSlotState.QUARANTINED -> emptySet()
            }
            check(next in allowed) { "Invalid worker transition $current -> $next" }
            states[index] = next
        }
    }

    private fun release(index: Int, runId: String, verifiedClosed: Boolean, reason: String?) {
        val signal = synchronized(lock) {
            check(index in states.indices && activeRunIds[index] == runId) {
                "Worker lease identity changed before release"
            }
            if (verifiedClosed && reason == null && states[index] == WorkerSlotState.VERIFIED_CLOSED) {
                states[index] = WorkerSlotState.FREE
            } else {
                states[index] = WorkerSlotState.QUARANTINED
                quarantineReasons[index] = reason ?: "STOP_NOT_CONFIRMED"
            }
            activeRunIds[index] = null
            val oldSignal = changed
            changed = CompletableDeferred()
            oldSignal
        }
        signal.complete(Unit)
    }

    internal fun state(index: Int): WorkerSlotState = synchronized(lock) {
        require(index in states.indices)
        states[index]
    }

    internal fun quarantineReason(index: Int): String? = synchronized(lock) {
        require(index in states.indices)
        quarantineReasons[index]
    }

    class Lease internal constructor(
        val index: Int,
        val accountId: String,
        val profileId: String,
        val containerId: String,
        val route: String,
        val runId: String,
        private val transitionAction: (WorkerSlotState) -> Unit,
        private val releaseAction: (verifiedClosed: Boolean, reason: String?) -> Unit
    ) {
        private val released = AtomicBoolean(false)
        private val verifiedClosed = AtomicBoolean(false)
        @Volatile private var failureReason: String? = null

        fun transition(next: WorkerSlotState) {
            check(!released.get()) { "Cannot change a released worker lease" }
            transitionAction(next)
        }

        fun markVerifiedClosed() {
            check(!released.get()) { "Cannot verify a released worker lease" }
            if (verifiedClosed.compareAndSet(false, true)) {
                try {
                    transitionAction(WorkerSlotState.VERIFIED_CLOSED)
                } catch (e: Exception) {
                    verifiedClosed.set(false)
                    throw e
                }
            }
        }

        fun quarantine(reason: String) {
            if (reason.isNotBlank() && !released.get()) failureReason = reason
        }

        fun release() {
            if (released.compareAndSet(false, true)) {
                releaseAction(verifiedClosed.get() && failureReason == null, failureReason)
            }
        }
    }
}

internal class WorkerPoolUnavailableException(message: String) : IllegalStateException(message)
