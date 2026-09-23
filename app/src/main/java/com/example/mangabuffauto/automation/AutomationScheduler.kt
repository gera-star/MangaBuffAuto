package com.example.mangabuffauto.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.mangabuffauto.AndroidBotLog
import com.example.mangabuffauto.task.AutomationTask
import com.example.mangabuffauto.task.ChatDiamondTask
import com.example.mangabuffauto.task.MineTask
import com.example.mangabuffauto.task.ReadTask
import com.example.mangabuffauto.task.TaskResult
import com.example.mangabuffauto.task.WatchAdsTask

class AutomationScheduler(
    context: Context,
    private val runtime: AutomationRuntimeFacade
) {
    interface AutomationRuntimeFacade {
        fun engine(): AutomationEngine?
        fun startTask(task: AutomationTask)
        fun activeProfile(): String
    }
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var lastChatAt = 0L
    private var lastReadAt = 0L
    private var lastMineAt = 0L
    private var lastAdsAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { tickInternal() } catch (e: Exception) {
                AndroidBotLog.log("AUTO SCHEDULER ERROR: " + e.message)
            } finally {
                if (running) handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }
    private val finishListener: (AutomationTask, TaskResult) -> Unit = { task, result ->
        AndroidBotLog.log("AUTO: " + task.spec.id + " finished -> " + result)
    }
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start() else stop()
    }
    fun start() {
        if (running) return
        running = true
        loadTimestamps()
        runtime.engine()?.addTaskFinishedListener(finishListener)
        handler.removeCallbacks(tick)
        handler.post(tick)
        AndroidBotLog.log("AUTO: scheduler STARTED profile=" + runtime.activeProfile())
    }
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        runtime.engine()?.removeTaskFinishedListener(finishListener)
        AndroidBotLog.log("AUTO: scheduler STOPPED")
    }
    fun onProfileChanged() { loadTimestamps() }
    fun destroy() { stop(); handler.removeCallbacksAndMessages(null) }
    private fun tickInternal() {
        if (!isEnabled()) { stop(); return }
        val engine = runtime.engine() ?: return
        if (engine.state.value == AutomationState.BLOCKED || engine.state.value == AutomationState.CAPTCHA ||
            engine.state.value == AutomationState.ERROR || engine.hasTaskInFlight()) return
        val now = System.currentTimeMillis()
        if (now - lastMineAt >= DAY_MS) { lastMineAt=now; saveTimestamp(KEY_MINE_AT,now); launch(MineTask); return }
        if (now - lastAdsAt >= DAY_MS) { lastAdsAt=now; saveTimestamp(KEY_ADS_AT,now); launch(WatchAdsTask); return }
        if (now - lastChatAt >= CHAT_INTERVAL_MS) { lastChatAt=now; saveTimestamp(KEY_CHAT_AT,now); launch(ChatDiamondTask); return }
        if (now - lastReadAt >= READ_INTERVAL_MS) { lastReadAt=now; saveTimestamp(KEY_READ_AT,now); launch(ReadTask) }
    }
    private fun launch(task: AutomationTask) {
        AndroidBotLog.log("AUTO: START " + task.spec.id + " profile=" + runtime.activeProfile())
        runtime.startTask(task)
    }
    private fun loadTimestamps() {
        val prefix=profilePrefix()
        lastChatAt=prefs.getLong(prefix+KEY_CHAT_AT,0L); lastReadAt=prefs.getLong(prefix+KEY_READ_AT,0L)
        lastMineAt=prefs.getLong(prefix+KEY_MINE_AT,0L); lastAdsAt=prefs.getLong(prefix+KEY_ADS_AT,0L)
    }
    private fun saveTimestamp(key:String,value:Long){prefs.edit().putLong(profilePrefix()+key,value).apply()}
    private fun profilePrefix()="profile_"+runtime.activeProfile()+"_"
    companion object {
        private const val PREFS="mangabuff_automation_scheduler"
        private const val KEY_ENABLED="enabled"
        private const val KEY_CHAT_AT="chat_at"
        private const val KEY_READ_AT="read_at"
        private const val KEY_MINE_AT="mine_at"
        private const val KEY_ADS_AT="ads_at"
        private const val CHECK_INTERVAL_MS=15_000L
        private const val CHAT_INTERVAL_MS=930_000L
        private const val READ_INTERVAL_MS=3_600_000L
        private const val DAY_MS=86_400_000L
    }
}
