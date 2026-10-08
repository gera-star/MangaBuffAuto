package com.example.myapplication.automation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide device state only. This is deliberately NOT account state:
 * screen on/off is a property of the device, while all automation state remains
 * partitioned by accountId inside AutomationRuntime/MangaBuffAutomation.
 */
object BackgroundExecutionState {
    private val screenOff = AtomicBoolean(false)

    fun setScreenOff(value: Boolean) {
        screenOff.set(value)
    }

    fun isScreenOff(): Boolean = screenOff.get()
}
