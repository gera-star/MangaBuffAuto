package com.example.mangabuffauto.automation

import android.content.Context
import com.example.mangabuffauto.AndroidBotLog

class RecoveryManager(context: Context) {
    private val state = PersistentAutomationState(context.applicationContext)
    fun inspect(profile: String): PersistentAutomationState.Snapshot? {
        val snapshot = state.interruptedTask(profile)
        if (snapshot != null) AndroidBotLog.log("RECOVERY: previous execution interrupted task=" + snapshot.taskId + " attempt=" + snapshot.taskAttempt)
        return snapshot
    }
    fun acknowledgeInterruption(profile: String) { state.clearInterruptedTask(profile) }
}
