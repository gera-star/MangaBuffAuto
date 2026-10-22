package com.example.myapplication.automation

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Privacy defaults applied to one WebView bound to an AndroidX WebView Profile.
 *
 * Each profile has independent cookies, DOM storage and other browsing data. WebSettings
 * belong to the individual WebView. Keep the default User-Agent normalized across profiles:
 * random/per-profile UA strings usually increase fingerprintability rather than reduce it.
 */
internal data class WebViewContainerPrivacyConfig(
    val userAgentOverride: String? = null,
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val acceptThirdPartyCookies: Boolean = false,
    val cacheMode: Int = WebSettings.LOAD_DEFAULT
)

internal object WebViewContainerPrivacy {
    fun apply(
        context: Context,
        webView: WebView,
        profileCookieManager: CookieManager,
        config: WebViewContainerPrivacyConfig = WebViewContainerPrivacyConfig()
    ) {
        val settings = webView.settings

        // MangaBuff's pages require JavaScript and DOM storage for login and app state.
        // These are enabled per WebView rather than through a shared/global settings object.
        settings.javaScriptEnabled = config.javaScriptEnabled
        settings.domStorageEnabled = config.domStorageEnabled

        // Use a common platform WebView UA by default. A profile may opt into a fixed,
        // tested override, but do not append account IDs or random tokens to it.
        settings.userAgentString = config.userAgentOverride
            ?.takeIf { it.isNotBlank() }
            ?: WebSettings.getDefaultUserAgent(context)

        settings.cacheMode = config.cacheMode
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // The automation pages do not need local-file or content-provider access.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false

        // The app does not need site geolocation or autoplay for its account workflow.
        settings.setGeolocationEnabled(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.safeBrowsingEnabled = true

        // Use the CookieManager belonging to this WebView Profile, not CookieManager's
        // default-profile singleton. Third-party cookies stay disabled unless explicitly
        // required by a known site workflow.
        profileCookieManager.setAcceptCookie(true)
        profileCookieManager.setAcceptThirdPartyCookies(
            webView,
            config.acceptThirdPartyCookies
        )
    }
}
