package com.example.mangabuffauto.automation

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.mangabuffauto.AndroidBotLog
import com.example.mangabuffauto.MainActivity

class AutomationForegroundService : Service() {


    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i("MangaBuffAuto", "[BG] SCREEN_OFF")
                    AndroidBotLog.log("[BG] SCREEN_OFF")
                    AutomationRuntime.setScreenOn(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i("MangaBuffAuto", "[BG] SCREEN_ON")
                    AndroidBotLog.log("[BG] SCREEN_ON")
                    AutomationRuntime.setScreenOn(true)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "mangabuff_automation_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("MangaBuffAuto", "[BG] SERVICE_CREATED")
        AndroidBotLog.log("[BG] SERVICE_OWNS_RUNTIME_KEEPALIVE")

        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            Log.i("MangaBuffAuto", "[BG] SERVICE_STOPPED")
            AndroidBotLog.log("[BG] SERVICE_STOPPED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            AutomationRuntime.setAutoModeEnabled(false)
            AutomationRuntime.stop("Foreground service stop")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i("MangaBuffAuto", "[BG] SERVICE_STARTED")
        AndroidBotLog.log("[BG] SERVICE_STARTED")

        val profile = MultiProfileManager(applicationContext).activeProfile()
        val notification = createNotification(profile)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("MangaBuffAuto", "[BG] startForeground error: ${e.message}")
            return START_NOT_STICKY
        }

        // После startForeground() можно безопасно делать тяжёлую WebView-инициализацию.
        val profiles = MultiProfileManager(applicationContext)
        profiles.ensureProfile(profiles.activeProfile())
        val savedAutoMode = PersistentAutomationState(applicationContext).autoModeEnabled()
        // Restore the persistent automation flag before WebView work starts so
        // the CPU keep-alive is already active during locked-screen recovery.
        AutomationRuntime.setAutoModeEnabled(savedAutoMode)
        AutomationRuntime.ensure(applicationContext, profiles.activeProfile())
        if (savedAutoMode) {
            AndroidBotLog.log("[BG] KEEPALIVE_READY foreground+wakelock")
        }
        AndroidBotLog.log("[BG] AUTO_MODE_RESTORED=$savedAutoMode")

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val name = "MangaBuff Background Service"
        val descriptionText = "Удержание автоматизации MangaBuff в фоне"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun createNotification(profileName: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaBuff Auto · $profileName")
            .setContentText("Автоматизация работает в фоне")
            .setSmallIcon(R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not treat removal of the Activity task as a request to stop automation.
        // HyperOS may remove the app task while the foreground service should remain alive.
        val state = PersistentAutomationState(applicationContext)
        if (state.autoModeEnabled()) {
            try {
                val restartIntent = Intent(applicationContext, AutomationForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restartIntent)
                } else {
                    applicationContext.startService(restartIntent)
                }
                AndroidBotLog.log("[BG] TASK_REMOVED auto=true service-restart-requested")
            } catch (e: Exception) {
                AndroidBotLog.log("[BG] TASK_REMOVED restart failed: ${e.message}")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // IMPORTANT: Android/HyperOS may recreate the service without the user
        // asking automation to stop. Do not stop Engine/WebView here.
        // The explicit ACTION_STOP path above is the only normal stop command.
        unregisterReceiver(screenStateReceiver)
        Log.i("MangaBuffAuto", "[BG] SERVICE_DESTROYED - runtime preserved")
        AndroidBotLog.log("[BG] SERVICE_DESTROYED - runtime preserved")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
