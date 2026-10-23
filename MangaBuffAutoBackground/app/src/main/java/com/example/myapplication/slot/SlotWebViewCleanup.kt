package com.example.myapplication.slot

/** Runs both cleanup operations and reports failure if either operation throws. */
internal object SlotWebViewCleanup {
    fun cleanup(
        stopLoading: () -> Unit,
        destroy: () -> Unit,
        onFailure: () -> Unit
    ): Boolean {
        var failed = false
        try {
            stopLoading()
        } catch (_: Exception) {
            failed = true
        }
        try {
            destroy()
        } catch (_: Exception) {
            failed = true
        }
        if (failed) onFailure()
        return !failed
    }
}
