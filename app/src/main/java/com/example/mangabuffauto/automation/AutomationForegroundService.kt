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
                Intent.ACTION_SCREEN_OFF -> { Log.i("MangaBuffAuto","[BG] SCREEN_OFF"); AndroidBotLog.log("[BG] SCREEN_OFF"); AutomationRuntime.setScreenOn(false) }
                Intent.ACTION_SCREEN_ON -> { Log.i("MangaBuffAuto","[BG] SCREEN_ON"); AndroidBotLog.log("[BG] SCREEN_ON"); AutomationRuntime.setScreenOn(true) }
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
        createNotificationChannel()
        val filter=IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        registerReceiver(screenStateReceiver, filter)
        AndroidBotLog.log("[BG] SERVICE_OWNS_RUNTIME_KEEPALIVE")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            AutomationRuntime.setAutoModeEnabled(false)
            AutomationRuntime.stop("Foreground service stop")
            stopSelf()
            return START_NOT_STICKY
        }
        val profile=MultiProfileManager(applicationContext).activeProfile()
        try {
            val notification=createNotification(profile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID,notification)
        } catch (e: Exception) {
            Log.e("MangaBuffAuto","[BG] startForeground error: "+e.message)
            return START_NOT_STICKY
        }
        val profiles=MultiProfileManager(applicationContext)
        profiles.ensureProfile(profiles.activeProfile())
        val savedAutoMode=PersistentAutomationState(applicationContext).autoModeEnabled()
        AutomationRuntime.setAutoModeEnabled(savedAutoMode)
        AutomationRuntime.ensure(applicationContext,profiles.activeProfile())
        AndroidBotLog.log("[BG] AUTO_MODE_RESTORED="+savedAutoMode)
        return START_STICKY
    }
    private fun createNotificationChannel() {
        val channel=NotificationChannel(CHANNEL_ID,"MangaBuff Background Service",NotificationManager.IMPORTANCE_LOW)
        channel.description="Удержание автоматизации MangaBuff в фоне"
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
    private fun createNotification(profileName:String):Notification {
        val intent=Intent(this,MainActivity::class.java).apply { flags=Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL_ID)
            .setContentTitle("MangaBuff Auto · $profileName")
            .setContentText("Автоматизация работает в фоне")
            .setSmallIcon(R.drawable.ic_menu_upload)
            .setContentIntent(pi).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }
    override fun onTaskRemoved(rootIntent:Intent?) {
        if(PersistentAutomationState(applicationContext).autoModeEnabled()) {
            try {
                val i=Intent(applicationContext,AutomationForegroundService::class.java)
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) applicationContext.startForegroundService(i) else applicationContext.startService(i)
                AndroidBotLog.log("[BG] TASK_REMOVED auto=true service-restart-requested")
            } catch(e:Exception){ AndroidBotLog.log("[BG] TASK_REMOVED restart failed: "+e.message) }
        }
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        unregisterReceiver(screenStateReceiver)
        AndroidBotLog.log("[BG] SERVICE_DESTROYED - runtime preserved")
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
