package com.example.myapplication.slot

/**
 * Slot Activities must fail closed unless they run in their declared :slotN process and
 * ContainerApp confirms the exact WebView data-directory suffix applied before WebView startup.
 */
internal object SlotProcessAdmissionPolicy {
    fun isReady(
        processName: String?,
        isolatedWebViewProcessReady: Boolean,
        isolatedWebViewSuffix: String?
    ): Boolean {
        val slot = SlotLauncher.slotIndexOf(processName) ?: return false
        return isolatedWebViewProcessReady && isolatedWebViewSuffix == "slot$slot"
    }
}
