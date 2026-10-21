package com.example.myapplication.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity

/**
 * Delivers persisted Tower expedition deadlines after the device has been idle.
 * The receiver does no WebView work; it only hands the deadline to the foreground service.
 */
class TowerCycleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TOWER_ALARM) return

        val accountId = intent.getStringExtra(MangaBuffForegroundService.EXTRA_ACCOUNT_ID).orEmpty()
        val endsAt = intent.getLongExtra(MangaBuffForegroundService.EXTRA_TOWER_ENDS_AT_MS, -1L)
        val attempt = intent.getIntExtra(EXTRA_DELIVERY_ATTEMPT, 0).coerceAtLeast(0)
        if (accountId.isBlank() || endsAt <= 0L) {
            Log.w(TAG, "Ignoring malformed Tower alarm")
            return
        }

        val serviceIntent = Intent(context, MangaBuffForegroundService::class.java).apply {
            action = MangaBuffForegroundService.ACTION_TOWER_CYCLE_DUE
            putExtra(MangaBuffForegroundService.EXTRA_ACCOUNT_ID, accountId)
            putExtra(MangaBuffForegroundService.EXTRA_TOWER_ENDS_AT_MS, endsAt)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Delivered Tower alarm accountId=$accountId endsAt=$endsAt attempt=$attempt")
        } catch (e: Exception) {
            Log.e(TAG, "Could not deliver Tower alarm accountId=$accountId endsAt=$endsAt attempt=$attempt", e)
            retryDeliveryOrNotify(
                context = context,
                accountId = accountId,
                endsAtMillis = endsAt,
                attempt = attempt,
                reason = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun retryDeliveryOrNotify(
        context: Context,
        accountId: String,
        endsAtMillis: Long,
        attempt: Int,
        reason: String
    ) {
        val nextAttempt = TowerDeliveryRetryPolicy.nextAttempt(attempt)
        if (nextAttempt != null) {
            val now = System.currentTimeMillis()
            val retryDelayMs = TowerDeliveryRetryPolicy.retryDelayMs(attempt)
            val retryAt = TowerDeliveryRetryPolicy.retryAt(now, attempt)
                ?: return showDeliveryNotification(
                    context,
                    accountId,
                    "Не удалось запланировать повтор запуска башни. Откройте 2R2R и проверьте фоновую работу.",
                    notificationId(accountId)
                )
            val retryMinutes = ((retryDelayMs ?: TowerDeliveryRetryPolicy.FIRST_RETRY_DELAY_MS) / 60_000L)
                .toInt()
                .coerceAtLeast(1)
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    retryAt,
                    pendingIntent(context, accountId, endsAtMillis, nextAttempt)
                )
                val message = "Не удалось запустить задачу башни: $reason. Повтор через ${retryMinutes} мин. ($nextAttempt/${TowerDeliveryRetryPolicy.MAX_RETRIES})."
                Log.w(TAG, "Scheduled Tower delivery retry accountId=$accountId retryAt=$retryAt attempt=$nextAttempt reason=$reason")
                showDeliveryNotification(context, accountId, message, notificationId(accountId))
                return
            } catch (retryError: Exception) {
                Log.e(TAG, "Could not schedule Tower delivery retry accountId=$accountId attempt=$nextAttempt", retryError)
            }
        }

        val attemptsCompleted = TowerDeliveryRetryPolicy.attemptsCompleted(attempt)
        val finalMessage = "Не удалось запустить задачу башни после $attemptsCompleted попыток. Причина: $reason. Откройте 2R2R и проверьте фоновую работу."
        Log.e(TAG, "Tower delivery stopped accountId=$accountId attempts=$attemptsCompleted reason=$reason")
        showDeliveryNotification(context, accountId, finalMessage, notificationId(accountId))
    }

    private fun showDeliveryNotification(
        context: Context,
        accountId: String,
        message: String,
        notificationId: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Tower delivery notification not shown: POST_NOTIFICATIONS denied accountId=$accountId reason=$message")
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    DELIVERY_CHANNEL_ID,
                    "Проблемы запуска башни",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Причины сбоев и повторные попытки запуска автоматизации башни"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, DELIVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("MangaBuff Auto — башня")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(notificationId, notification)
    }

    private fun notificationId(accountId: String): Int =
        NOTIFICATION_ID_BASE + (accountId.hashCode() and 0x3FFF)

    companion object {
        private const val TAG = "TowerCycleAlarm"
        private const val DELIVERY_CHANNEL_ID = "tower_alarm_delivery_alerts"
        private const val EXTRA_DELIVERY_ATTEMPT = "EXTRA_TOWER_DELIVERY_ATTEMPT"
        private const val NOTIFICATION_ID_BASE = 20_000
        const val ACTION_TOWER_ALARM = "com.example.myapplication.action.TOWER_CYCLE_ALARM"

        fun pendingIntent(
            context: Context,
            accountId: String,
            endsAtMillis: Long? = null,
            deliveryAttempt: Int = 0
        ): PendingIntent {
            val alarmIntent = Intent(context, TowerCycleAlarmReceiver::class.java).apply {
                action = ACTION_TOWER_ALARM
                data = Uri.parse("mangabuff://tower-cycle/${Uri.encode(accountId)}")
                putExtra(MangaBuffForegroundService.EXTRA_ACCOUNT_ID, accountId)
                putExtra(EXTRA_DELIVERY_ATTEMPT, deliveryAttempt.coerceAtLeast(0))
                if (endsAtMillis != null) {
                    putExtra(MangaBuffForegroundService.EXTRA_TOWER_ENDS_AT_MS, endsAtMillis)
                }
            }
            return PendingIntent.getBroadcast(
                context,
                accountId.hashCode(),
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
