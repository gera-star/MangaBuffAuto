package com.example.myapplication.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes account automation and tracks jobs waiting for the permit.
 *
 * A queued job does not own a WebView yet. The service watchdog uses [isQueued]
 * to avoid treating that expected state as a missing-WebView failure.
 */
internal class AccountExecutionQueue {
    private val permit = Semaphore(1)
    /** Jobs waiting either for the local permit or the shared trade/automation gate. */
    private val queuedJobs = ConcurrentHashMap<String, Job>()
    /** Only these jobs have not acquired the local permit yet and should defer network cancellation. */
    private val localQueueWaiters = ConcurrentHashMap<String, Job>()

    fun markQueued(accountId: String, job: Job) {
        queuedJobs[accountId] = job
        localQueueWaiters[accountId] = job
    }

    /** Call after the local permit is acquired and before waiting for AccountExecutionGate. */
    fun markWaitingForSharedGate(accountId: String, job: Job) {
        localQueueWaiters.remove(accountId, job)
    }

    fun markAcquired(accountId: String, job: Job) {
        localQueueWaiters.remove(accountId, job)
        queuedJobs.remove(accountId, job)
    }

    fun forget(accountId: String, job: Job) {
        localQueueWaiters.remove(accountId, job)
        queuedJobs.remove(accountId, job)
    }

    fun isQueued(accountId: String, job: Job): Boolean =
        queuedJobs[accountId] === job

    fun isWaitingForLocalPermit(accountId: String, job: Job): Boolean =
        localQueueWaiters[accountId] === job

    suspend fun acquire() {
        permit.acquire()
    }

    fun release() {
        permit.release()
    }
}
