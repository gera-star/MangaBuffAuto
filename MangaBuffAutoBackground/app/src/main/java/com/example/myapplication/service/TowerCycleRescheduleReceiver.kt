package com.example.myapplication.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.myapplication.data.AccountRepository

/**
 * AlarmManager alarms are removed by Android on reboot. Re-arm persisted Tower deadlines
 * without starting a foreground service; the normal Tower alarm receiver starts it when due.
 */
class TowerCycleRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED -> reschedule(context)
            else -> return
        }
    }

    private fun reschedule(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY_TOWER_CYCLE_ENDS, emptySet()).orEmpty()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val accountsById = AccountRepository(context).getAccounts().associateBy { it.id }
        val retainedEntries = entries.toMutableSet()
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        entries.forEach { entry ->
            val separator = entry.indexOf('|')
            if (separator <= 0 || separator >= entry.length - 1) return@forEach
            val accountId = entry.substring(0, separator)
            val endsAt = entry.substring(separator + 1).toLongOrNull() ?: return@forEach
            if (accountId.isBlank() || endsAt <= 0L) return@forEach

            val account = accountsById[accountId]
            if (account == null || account.isOffline) {
                runCatching {
                    alarmManager.cancel(TowerCycleAlarmReceiver.pendingIntent(context, accountId))
                }
                retainedEntries.remove(entry)
                Log.i(TAG, "Skipped Tower alarm for missing/OFFLINE accountId=$accountId")
                return@forEach
            }

            val pending = TowerCycleAlarmReceiver.pendingIntent(context, accountId, endsAt)
            try {
                if (canUseExact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pending)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pending)
                }
                Log.i(TAG, "Restored Tower alarm accountId=$accountId endsAt=$endsAt exact=$canUseExact")
            } catch (e: SecurityException) {
                runCatching {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pending)
                }.onFailure {
                    Log.e(TAG, "Could not restore Tower alarm for accountId=$accountId", it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not restore Tower alarm for accountId=$accountId", e)
            }
        }

        if (retainedEntries != entries) {
            prefs.edit().putStringSet(KEY_TOWER_CYCLE_ENDS, retainedEntries).apply()
        }
    }

    companion object {
        private const val TAG = "TowerCycleReschedule"
        private const val PREFS_RUNTIME = "mangabuff_runtime"
        private const val KEY_TOWER_CYCLE_ENDS = "tower_cycle_ends"
        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
