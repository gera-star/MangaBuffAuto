package com.example.myapplication.automation

/** Computes the amount left from a configured daily target after restored persisted progress. */
internal object DailyTarget {
    fun remaining(configuredTarget: Int, completedToday: Int): Int =
        (configuredTarget.coerceAtLeast(0) - completedToday.coerceAtLeast(0)).coerceAtLeast(0)
}
