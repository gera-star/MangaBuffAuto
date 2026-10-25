package com.example.myapplication.web

/**
 * A failed WebView cleanup leaves Chromium state uncertain. Quarantine the process and never
 * treat the container as reusable unless the entire cleanup operation completes successfully.
 */
internal object AccountWebViewerCleanupPolicy {
    fun destroyOrQuarantine(
        owner: AccountWebViewerProcessRouteOwner,
        cleanup: () -> Unit
    ): Boolean {
        return try {
            cleanup()
            true
        } catch (_: Throwable) {
            owner.quarantine()
            false
        }
    }
}
