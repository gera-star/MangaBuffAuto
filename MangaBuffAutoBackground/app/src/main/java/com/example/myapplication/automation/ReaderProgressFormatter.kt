package com.example.myapplication.automation

/** User-visible reading progress is relative to the configured session target, not manga length. */
internal object ReaderProgressFormatter {
    fun format(completed: Int, target: Int): String {
        val completedSafe = completed.coerceAtLeast(0)
        val targetLabel = target.takeIf { it > 0 }?.toString() ?: "?"
        return "$completedSafe / $targetLabel"
    }
}
