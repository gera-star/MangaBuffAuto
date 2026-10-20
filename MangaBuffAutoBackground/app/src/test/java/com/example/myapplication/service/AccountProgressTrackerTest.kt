package com.example.myapplication.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProgressTrackerTest {
    @Test
    fun onlyChangedProgressRefreshesDeadline() {
        var now = 1_000L
        val tracker = AccountProgressTracker { now }

        tracker.mark(ProgressStage.BATTLE, "BATTLE: ROUND_START")
        now += ProgressStage.BATTLE.timeoutMs - 1L
        assertFalse(tracker.isTimedOut(now))

        tracker.mark(ProgressStage.BATTLE, "BATTLE: ROUND_START")
        assertEquals(1_000L, tracker.snapshot().lastProgressAtMs)

        now += 1L
        assertTrue(tracker.isTimedOut(now))

        tracker.mark(ProgressStage.BATTLE, "BATTLE: RESULT=WIN")
        assertFalse(tracker.isTimedOut(now))
        assertEquals(now, tracker.snapshot().lastProgressAtMs)
    }

    @Test
    fun stageChangeStartsNewDeadlineAndReaderGetsLongerWindow() {
        var now = 10_000L
        val tracker = AccountProgressTracker { now }
        tracker.mark(ProgressStage.BATTLE, "battle")
        now += ProgressStage.BATTLE.timeoutMs
        assertTrue(tracker.isTimedOut(now))

        tracker.mark(ProgressStage.READER, "chapter-1")
        assertFalse(tracker.isTimedOut(now))
        assertTrue(ProgressStage.READER.timeoutMs > ProgressStage.BATTLE.timeoutMs)
    }

    @Test
    fun eventClassificationUsesTaskStage() {
        assertEquals(
            ProgressStage.MINE,
            ProgressStage.fromEvent("MINE: PROGRESS 3/10", "AUTOMATION", "Все задачи")
        )
        assertEquals(
            ProgressStage.READER,
            ProgressStage.fromEvent("BACKGROUND_SCROLL_WATCHDOG", "READER", "Читалка")
        )
        assertEquals(
            ProgressStage.READER,
            ProgressStage.fromEvent("SCROLL_PROGRESS_SAMPLE y=120", "READER", "Все задачи")
        )
    }
}
