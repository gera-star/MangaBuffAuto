package com.example.myapplication.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Delivers persisted Tower expedition deadlines after the device has been idle.
 * The receiver does no WebView work; it only hands the deadline to the foreground service.
 */
class TowerCycleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TOWER_ALARM) return

        val accountId = intent.getStringExtra(MangaBuffForegroundService.EXTRA_ACCOUNT_ID).orEmpty()
        val endsAt = intent.getLongExtra(MangaBuffForegroundService.EXTRA_TOWER_ENDS_AT_MS, -1L)
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
            Log.i(TAG, "Delivered Tower alarm accountId=$accountId endsAt=$endsAt")
        } catch (e: Exception) {
            // Android may restrict a background FGS start if exact-alarm access is not granted.
            // The running service's coroutine remains a fallback; this log makes the limitation visible.
            Log.e(TAG, "Could not deliver Tower alarm accountId=$accountId endsAt=$endsAt", e)
        }
    }

    companion object {
        private const val TAG = "TowerCycleAlarm"
        const val ACTION_TOWER_ALARM = "com.example.myapplication.action.TOWER_CYCLE_ALARM"

        fun pendingIntent(
            context: Context,
            accountId: String,
            endsAtMillis: Long? = null
        ): PendingIntent {
            val alarmIntent = Intent(context, TowerCycleAlarmReceiver::class.java).apply {
                action = ACTION_TOWER_ALARM
                data = Uri.parse("mangabuff://tower-cycle/${Uri.encode(accountId)}")
                putExtra(MangaBuffForegroundService.EXTRA_ACCOUNT_ID, accountId)
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
