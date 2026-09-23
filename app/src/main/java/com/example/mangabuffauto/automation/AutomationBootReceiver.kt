package com.example.mangabuffauto.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.mangabuffauto.AndroidBotLog

class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val state = PersistentAutomationState(context.applicationContext)
        if (!state.autoModeEnabled()) return
        val serviceIntent = Intent(context, AutomationForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
            AndroidBotLog.log("[BG] BOOT_RECOVERY service requested action=" + intent?.action)
        } catch (e: Exception) {
            AndroidBotLog.log("[BG] BOOT_RECOVERY failed: " + e.message)
        }
    }
}
