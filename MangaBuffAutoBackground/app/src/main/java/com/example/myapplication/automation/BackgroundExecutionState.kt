package com.example.myapplication.automation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide screen state used by background reader automation.
 * The foreground service updates it from SCREEN_ON/SCREEN_OFF broadcasts.
 */
object BackgroundExecutionState {
    private val screenOff = AtomicBoolean(false)

    fun setScreenOff(value: Boolean) {
        screenOff.set(value)
    }

    fun isScreenOff(): Boolean = screenOff.get()
}
