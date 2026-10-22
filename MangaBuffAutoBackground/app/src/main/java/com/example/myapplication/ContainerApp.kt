package com.example.myapplication

import android.app.Application
import android.os.Build
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewFeature
import com.example.myapplication.slot.SlotLauncher

/**
 * Прокси и каталог данных WebView задаются на весь процесс, поэтому каждый слот-вкладка живёт в
 * собственном процессе (":slotN"). Суффикс каталога данных обязан быть выставлен один раз,
 * до первого обращения к android.webkit в процессе, то есть здесь.
 */
class ContainerApp : Application() {
    var isolatedWebViewProcessReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        val processName = processNameCompat()
        // Slot, worker and account-viewer processes each get their own WebView data directory.
        val suffix = SlotLauncher.slotIndexOf(processName)?.let { "slot$it" }
            ?: processName?.substringAfter(":w", "")?.toIntOrNull()?.let { "w$it" }
            ?: processName?.takeIf { it.endsWith(":webviewer") }?.let { "webviewer" }
            ?: return
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
            isolatedWebViewProcessReady = true
        }
    }

    private fun processNameCompat(): String? =
        if (Build.VERSION.SDK_INT >= 28) getProcessName() else null
}
