package com.example.myapplication.service

/**
 * The app intentionally runs one account at a time. Profiles and clients are isolated as well,
 * but that is not a reason to allow concurrent account tasks.
 */
internal object AccountExecutionModePolicy {
    fun shouldUseParallel(): Boolean = false
}
