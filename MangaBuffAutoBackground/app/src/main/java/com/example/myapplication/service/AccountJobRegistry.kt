package com.example.myapplication.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** Thread-safe per-account job lookup shared by start, stop and watchdog paths. */
internal class AccountJobRegistry :
    ConcurrentMap<String, Job> by ConcurrentHashMap<String, Job>() {

    private val finishingJobs = ConcurrentHashMap<String, Job>()

    fun cancelAndRemove(accountId: String): Job? =
        remove(accountId)?.also { it.cancel() }

    fun removeIfCurrent(accountId: String, job: Job): Boolean =
        remove(accountId, job)

    /**
     * Atomically moves the current account job into its finishing phase.
     * Finishing jobs remain stoppable, but are excluded from active-task watchdog/network handling.
     */
    @Synchronized
    fun markFinishing(accountId: String, job: Job): Boolean {
        if (!remove(accountId, job)) return false
        finishingJobs[accountId] = job
        return true
    }

    @Synchronized
    fun cancelFinishingAndRemove(accountId: String, reason: String): Job? =
        finishingJobs.remove(accountId)?.also {
            it.cancel(CancellationException(reason))
        }

    @Synchronized
    fun removeFinishingIfCurrent(accountId: String, job: Job): Boolean =
        finishingJobs.remove(accountId, job)

    fun isFinishing(accountId: String, job: Job): Boolean =
        finishingJobs[accountId] === job

    fun hasFinishingJobs(): Boolean =
        finishingJobs.values.any { it.isActive }

    fun allAccountIds(): Set<String> =
        (keys + finishingJobs.keys).toSet()
}
