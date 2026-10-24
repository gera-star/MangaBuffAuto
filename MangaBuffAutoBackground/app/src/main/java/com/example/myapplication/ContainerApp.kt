package com.example.myapplication

import android.app.Application
import android.os.Build
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewFeature

/**
 * Proxy and WebView data-directory configuration are process-wide. Each isolated slot, worker,
 * and account-viewer process must set its unique suffix here, before any WebView is constructed.
 */
class ContainerApp : Application() {
    var isolatedWebViewProcessReady: Boolean = false
        private set

    /** Exact data-directory suffix that was successfully applied in this process. */
    var isolatedWebViewSuffix: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val suffix = WebViewProcessIsolationPolicy.suffixFor(processNameCompat()) ?: return
        if (!WebViewFeature.isStartupFeatureSupported(
                this,
                WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX
            )
        ) return

        runCatching {
            ProcessGlobalConfig.apply(
                ProcessGlobalConfig().setDataDirectorySuffix(this, suffix)
            )
        }.onSuccess {
            isolatedWebViewSuffix = suffix
            isolatedWebViewProcessReady = true
        }
    }

    private fun processNameCompat(): String? =
        if (Build.VERSION.SDK_INT >= 28) getProcessName() else null
}
