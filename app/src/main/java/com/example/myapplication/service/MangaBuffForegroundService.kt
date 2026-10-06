package com.example.myapplication.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.automation.BackgroundExecutionState
import com.example.myapplication.data.LogEntry

class MangaBuffForegroundService : Service() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    BackgroundExecutionState.setScreenOff(true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    BackgroundExecutionState.setScreenOff(false)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "mangabuff_bot_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_FOREGROUND"
        const val ACTION_STOP = "ACTION_STOP_FOREGROUND"
        const val ACTION_UPDATE = "ACTION_UPDATE_STATUS"

        const val EXTRA_STATUS = "EXTRA_STATUS_TEXT"

        fun startService(context: Context, initialStatus: String = "Бот работает в фоновом режиме") {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS, initialStatus)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Ignore service start exceptions
            }
        }

        fun updateStatus(context: Context, status: String) {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_STATUS, status)
                }
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore service update exceptions
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, MangaBuffForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore service stop exceptions
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        BackgroundExecutionState.setScreenOff(!getSystemService(android.os.PowerManager::class.java).isInteractive)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Фоновая автоматизация активна"
                startForeground(NOTIFICATION_ID, buildNotification(status))
            }
            ACTION_UPDATE -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Выполнение задач..."
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
                }
            }
            ACTION_STOP -> {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    // Fallback
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая работа MangaBuff Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о выполнении задач авто-бота MangaBuff в фоновом режиме"
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MangaBuffForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaBuff Bot (Фоновый режим)")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", pendingStop)
            .build()
    }
}
