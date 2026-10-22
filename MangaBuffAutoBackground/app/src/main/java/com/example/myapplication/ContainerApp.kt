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
    override fun onCreate() {
        super.onCreate()
        val slot = SlotLauncher.slotIndexOf(processNameCompat()) ?: return
        if (WebViewFeature.isStartupFeatureSupported(
                this,
                WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX
            )
        ) {
            ProcessGlobalConfig.apply(
                ProcessGlobalConfig().setDataDirectorySuffix(this, "slot$slot")
            )
        }
        // Если функция не поддерживается, SlotActivity откажется грузить страницы (fail-closed):
        // общий каталог данных у разных процессов приведёт к конфликту и смешению данных.
    }

    private fun processNameCompat(): String? =
        if (Build.VERSION.SDK_INT >= 28) getProcessName() else null
}
