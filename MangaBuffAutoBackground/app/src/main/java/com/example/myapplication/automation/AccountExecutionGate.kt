package com.example.myapplication.automation

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide exclusive lease shared by regular automation and trade monitoring.
 * Keep it until account cleanup has finished.
 */
internal object AccountExecutionGate {
    private val mutex = Mutex()

    suspend fun acquire(): Permit {
        mutex.lock()
        return Permit { mutex.unlock() }
    }

    class Permit internal constructor(private val unlockAction: () -> Unit) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) unlockAction()
        }
    }
}
