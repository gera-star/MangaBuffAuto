package com.example.myapplication.service

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** Thread-safe per-account job lookup shared by start, stop and watchdog paths. */
internal class AccountJobRegistry :
    ConcurrentMap<String, Job> by ConcurrentHashMap<String, Job>() {

    fun cancelAndRemove(accountId: String): Job? =
        remove(accountId)?.also { it.cancel() }

    fun removeIfCurrent(accountId: String, job: Job): Boolean =
        remove(accountId, job)
}
