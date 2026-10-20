package com.example.myapplication.automation

/** Pure rules for resuming a sequential ALL run at the first uncompleted stage. */
internal object TaskSequenceCheckpoint {
    const val NO_COMPLETED_STAGE = -1

    fun shouldRunStage(stageIndex: Int, lastCompletedStage: Int, resume: Boolean): Boolean =
        !resume || stageIndex > lastCompletedStage

    fun markCompleted(currentCompletedStage: Int, newCompletedStage: Int): Int =
        maxOf(currentCompletedStage, newCompletedStage)
}
