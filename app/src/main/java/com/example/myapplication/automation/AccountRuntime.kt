package com.example.myapplication.automation

import android.webkit.WebView

data class AccountRuntime(
    val accountId: String,
    val profileName: String,
    val webView: WebView,
    var state: AccountState = AccountState.IDLE
)

enum class AccountState {
    IDLE, RUNNING, STOPPED, ERROR
}
