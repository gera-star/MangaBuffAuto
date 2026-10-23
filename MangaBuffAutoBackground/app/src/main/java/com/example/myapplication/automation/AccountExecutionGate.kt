package com.example.myapplication.automation

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

internal class ExecutionGateQuarantinedException(message: String) : IllegalStateException(message)

/** Separately instantiable so quarantine can be tested without poisoning the app singleton. */
internal class ExecutionGateController {
    private val mutex = Mutex()
    @Volatile private var quarantineReason: String? = null

    suspend fun acquire(): AccountExecutionGate.Permit {
        mutex.lock()
        val reason = quarantineReason
        if (reason != null) {
            mutex.unlock()
            throw ExecutionGateQuarantinedException("Execution gate quarantined: $reason")
        }
        return AccountExecutionGate.Permit(
            unlockAction = { mutex.unlock() },
            quarantineAction = { why -> quarantine(why) }
        )
    }

    fun quarantine(reason: String) {
        if (reason.isBlank()) return
        synchronized(this) { if (quarantineReason == null) quarantineReason = reason }
    }
}

/** Process-wide gate. Uncertain cleanup permanently blocks further account/trade admissions. */
internal object AccountExecutionGate {
    private val controller = ExecutionGateController()
    suspend fun acquire(): Permit = controller.acquire()
    fun quarantine(reason: String) = controller.quarantine(reason)

    class Permit internal constructor(
        private val unlockAction: () -> Unit,
        private val quarantineAction: (String) -> Unit
    ) {
        private val released = AtomicBoolean(false)
        private val quarantined = AtomicBoolean(false)

        fun quarantine(reason: String) {
            if (reason.isNotBlank() && !released.get() && quarantined.compareAndSet(false, true)) {
                quarantineAction(reason)
            }
        }

        fun release() {
            if (released.compareAndSet(false, true)) unlockAction()
        }
    }
}
