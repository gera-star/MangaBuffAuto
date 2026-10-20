package com.example.myapplication.service

import android.os.SystemClock

/**
 * Tracks meaningful progress for one account task. Repeated identical diagnostics do not
 * refresh the deadline; a new stage or a changed progress marker does.
 */
internal enum class ProgressStage(val timeoutMs: Long) {
    WAITING(0L),
    BATTLE(BATTLE_TIMEOUT_MS),
    TOWER(TOWER_TIMEOUT_MS),
    ADVERTISEMENT(ADVERTISEMENT_TIMEOUT_MS),
    MINE(MINE_TIMEOUT_MS),
    READER(READER_TIMEOUT_MS),
    COMMENT(COMMENT_TIMEOUT_MS),
    PROMO(PROMO_TIMEOUT_MS),
    BALANCE(BALANCE_TIMEOUT_MS),
    OTHER(OTHER_TIMEOUT_MS);

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
            return when {
                value.contains("BATTLE") || value.contains("БОЙ") || value.contains("БОЕВ") -> BATTLE
                value.contains("TOWER") || value.contains("БАШН") -> TOWER
                value.contains("ADS:") || value.contains("AD_") || value.contains("РЕКЛАМ") -> ADVERTISEMENT
                value.contains("MINE:") || value.contains("ШAХТ") || value.contains("ШАХТ") -> MINE
                value.contains("READER:") || value.contains("CHAPTER") || value.contains("ЧИТАЛ") -> READER
                value.contains("COMMENT") || value.contains("КОММЕНТ") -> COMMENT
                value.contains("PROMO") || value.contains("ПРОМО") -> PROMO
                value.contains("BALANCE") || value.contains("STAT:") || value.contains("БАЛАНС") -> BALANCE
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
