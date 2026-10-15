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
    private val queuedJobs = ConcurrentHashMap<String, Job>()

    fun markQueued(accountId: String, job: Job) {
        queuedJobs[accountId] = job
    }

    fun markAcquired(accountId: String, job: Job) {
        queuedJobs.remove(accountId, job)
    }

    fun forget(accountId: String, job: Job) {
        queuedJobs.remove(accountId, job)
    }

    fun isQueued(accountId: String, job: Job): Boolean =
        queuedJobs[accountId] === job

    suspend fun acquire() {
        permit.acquire()
    }

    fun release() {
        permit.release()
    }
}
