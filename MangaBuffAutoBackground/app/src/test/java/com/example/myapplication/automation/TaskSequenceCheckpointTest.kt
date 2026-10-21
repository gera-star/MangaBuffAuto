package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSequenceCheckpointTest {
    @Test
    fun restoreSkipsOnlyStagesCompletedBeforeRestart() {
        val lastCompletedStage = 2
        assertFalse(TaskSequenceCheckpoint.shouldRunStage(0, lastCompletedStage, resume = true))
        assertFalse(TaskSequenceCheckpoint.shouldRunStage(1, lastCompletedStage, resume = true))
        assertFalse(TaskSequenceCheckpoint.shouldRunStage(2, lastCompletedStage, resume = true))
        assertTrue(TaskSequenceCheckpoint.shouldRunStage(3, lastCompletedStage, resume = true))
        assertTrue(TaskSequenceCheckpoint.shouldRunStage(4, lastCompletedStage, resume = true))
    }

    @Test
    fun manualStartIgnoresAnOldCheckpoint() {
        assertTrue(TaskSequenceCheckpoint.shouldRunStage(0, 5, resume = false))
        assertTrue(TaskSequenceCheckpoint.shouldRunStage(5, 5, resume = false))
    }

    @Test
    fun checkpointNeverMovesBackwards() {
        assertEquals(4, TaskSequenceCheckpoint.markCompleted(4, 2))
        assertEquals(5, TaskSequenceCheckpoint.markCompleted(4, 5))
    }

    @Test
    fun checkpointCannotSkipAnUncompletedStage() {
        assertEquals(-1, TaskSequenceCheckpoint.markCompleted(-1, 1))
        assertEquals(0, TaskSequenceCheckpoint.markCompleted(-1, 0))
        assertEquals(1, TaskSequenceCheckpoint.markCompleted(0, 1))
        assertEquals(1, TaskSequenceCheckpoint.markCompleted(1, 3))
        assertEquals(3, TaskSequenceCheckpoint.markCompleted(2, 3))
    }
}
