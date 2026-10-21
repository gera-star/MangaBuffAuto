package com.example.myapplication.service

import com.example.myapplication.data.NetworkResetMode

/** Pure gating rules for an optional reset between completed sequential accounts. */
internal object NetworkResetPolicy {
    fun shouldResetBetweenAccounts(
        enabled: Boolean,
        mode: NetworkResetMode,
        runCompleted: Boolean,
        hasNextAccount: Boolean,
        stoppingExplicitly: Boolean,
        accountOffline: Boolean
    ): Boolean =
        enabled &&
            mode != NetworkResetMode.NONE &&
            runCompleted &&
            hasNextAccount &&
            !stoppingExplicitly &&
            !accountOffline
}
