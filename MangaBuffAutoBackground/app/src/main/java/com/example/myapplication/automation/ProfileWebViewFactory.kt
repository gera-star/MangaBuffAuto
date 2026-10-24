package com.example.myapplication.automation

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Shared profile binding and security-settings entry point for all app-owned WebViews.
 * Callers must retain/register the WebView before invoking this method because binding can fail.
 */
@SuppressLint("RestrictedApi")
internal object ProfileWebViewFactory {
    fun bindProfileAndApply(
        context: Context,
        webView: WebView,
        profileName: String,
        config: WebViewContainerPrivacyConfig = WebViewContainerPrivacyConfig()
    ): CookieManager {
        require(profileName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "PROFILE_NAME_INVALID"
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            "MULTI_PROFILE_UNSUPPORTED"
        }
        WebViewCompat.setProfile(webView, profileName)
        val cookies = ProfileStore.getInstance().getProfile(profileName)?.cookieManager
            ?: throw IllegalStateException("PROFILE_COOKIE_MANAGER_MISSING")
        WebViewContainerPrivacy.apply(context, webView, cookies, config)
        return cookies
    }
}
