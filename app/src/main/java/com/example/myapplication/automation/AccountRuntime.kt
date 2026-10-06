package com.example.myapplication.automation

import android.webkit.WebView
import java.util.UUID

data class AccountRuntime(
    val accountId: String,
    val profileName: String,
    val webView: WebView,
    val runId: String = UUID.randomUUID().toString(),
    var state: AccountState = AccountState.IDLE
)

enum class AccountState {
    IDLE, RUNNING, STOPPED, ERROR
}
