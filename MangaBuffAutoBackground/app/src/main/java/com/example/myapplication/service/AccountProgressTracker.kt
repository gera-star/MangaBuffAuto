package com.example.myapplication.service

import android.os.SystemClock

/**
 * Tracks meaningful progress for one account task. Repeated identical diagnostics do not
 * refresh the deadline; a new stage or a changed progress marker does.
 */
internal enum class ProgressStage(val timeoutMs: Long) {
    WAITING(0L),
    BATTLE(120_000L),
    TOWER(180_000L),
    ADVERTISEMENT(180_000L),
    MINE(180_000L),
    READER(10 * 60_000L),
    COMMENT(180_000L),
    PROMO(120_000L),
    BALANCE(120_000L),
    OTHER(5 * 60_000L);

    companion object {
        const val BATTLE_TIMEOUT_MS = 120_000L
        const val TOWER_TIMEOUT_MS = 180_000L
        const val ADVERTISEMENT_TIMEOUT_MS = 180_000L
        const val MINE_TIMEOUT_MS = 180_000L
        const val READER_TIMEOUT_MS = 10 * 60_000L
        const val COMMENT_TIMEOUT_MS = 180_000L
        const val PROMO_TIMEOUT_MS = 120_000L
        const val BALANCE_TIMEOUT_MS = 120_000L
        const val OTHER_TIMEOUT_MS = 5 * 60_000L

        fun fromText(value: String): ProgressStage {
            val text = value.lowercase()
            return when {
                text.contains("battle") || text.contains("бой") || text.contains("бои") || text.contains("боев") || text.contains("боёв") -> BATTLE
                text.contains("tower") || text.contains("баш") -> TOWER
                text.contains("ads") || text.contains("реклам") -> ADVERTISEMENT
                text.contains("mine") || text.contains("шахт") -> MINE
                text.contains("reader") || text.contains("читал") || text.contains("chapter") -> READER
                text.contains("comment") || text.contains("коммент") -> COMMENT
                text.contains("promo") || text.contains("промо") -> PROMO
                text.contains("balance") || text.contains("баланс") || text.contains("stat") -> BALANCE
                else -> OTHER
            }
        }

        fun fromEvent(message: String, component: String, currentTask: String): ProgressStage {
            val value = "${component.uppercase()} ${message.uppercase()}"
            val componentName = component.uppercase()
            return when {
                componentName.contains("BATTLE") || value.contains("BATTLE") ||
                    value.contains("БОЙ") || value.contains("БОЕВ") -> BATTLE
                componentName.contains("TOWER") || value.contains("TOWER") || value.contains("БАШН") -> TOWER
                componentName.contains("ADS") || value.contains("ADS:") ||
                    value.contains("AD_") || value.contains("РЕКЛАМ") -> ADVERTISEMENT
                componentName.contains("MINE") || value.contains("MINE:") ||
                    value.contains("ШАХТ") -> MINE
                componentName.contains("READER") || value.contains("CHAPTER") ||
                    value.contains("ЧИТАЛ") || value.contains("SCROLL_PROGRESS") -> READER
                componentName.contains("COMMENT") || value.contains("COMMENT") || value.contains("КОММЕНТ") -> COMMENT
                componentName.contains("PROMO") || value.contains("PROMO") || value.contains("ПРОМО") -> PROMO
                componentName.contains("BALANCE") || value.contains("BALANCE") ||
                    value.contains("STAT:") || value.contains("БАЛАНС") -> BALANCE
                else -> fromText(currentTask)
            }
        }
    }
}

internal data class ProgressSnapshot(
    val stage: ProgressStage,
    val marker: String,
    val lastProgressAtMs: Long
)

internal class AccountProgressTracker(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    @Volatile
    private var current = ProgressSnapshot(ProgressStage.WAITING, "", nowMs())

    @Synchronized
    fun mark(stage: ProgressStage, marker: String) {
        if (stage == ProgressStage.WAITING) return
        if (current.stage != stage || current.marker != marker) {
            current = ProgressSnapshot(stage, marker, nowMs())
        }
    }

    fun snapshot(): ProgressSnapshot = current

    fun stalledForMs(now: Long = nowMs()): Long =
        (now - current.lastProgressAtMs).coerceAtLeast(0L)

    fun isTimedOut(now: Long = nowMs()): Boolean {
        val snapshot = current
        return snapshot.stage != ProgressStage.WAITING &&
            snapshot.stage.timeoutMs > 0L &&
            (now - snapshot.lastProgressAtMs) >= snapshot.stage.timeoutMs
    }
}
