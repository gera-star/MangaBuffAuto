package com.example.myapplication.service

import com.example.myapplication.data.LogEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressLogClassifierTest {
    @Test
    fun pageLifecycleEventsDoNotRefreshProgressDeadline() {
        assertFalse(
            ProgressLogClassifier.isMeaningful(
                LogEntry(username = "account-A", component = "WEBVIEW", message = "PAGE_STARTED url=https://mangabuff.ru/login")
            )
        )
        assertFalse(
            ProgressLogClassifier.isMeaningful(
                LogEntry(username = "account-A", component = "WEBVIEW", message = "PAGE_FINISHED url=https://mangabuff.ru/login")
            )
        )
    }

    @Test
    fun taskMilestonesAndReaderProgressAreMeaningful() {
        assertTrue(
            ProgressLogClassifier.isMeaningful(
                LogEntry(username = "account-A", component = "BATTLE", message = "BATTLE: RESULT=WIN")
            )
        )
        assertTrue(
            ProgressLogClassifier.isMeaningful(
                LogEntry(username = "account-A", component = "READER", message = "READER: SCROLL_PROGRESS y=120")
            )
        )
    }

    @Test
    fun diagnosticsAndErrorsNeverCountAsProgress() {
        assertFalse(
            ProgressLogClassifier.isMeaningful(
                LogEntry(username = "account-A", component = "ENGINE", message = "HEARTBEAT url=/battle")
            )
        )
        assertFalse(
            ProgressLogClassifier.isMeaningful(
                LogEntry(username = "account-A", component = "BATTLE", message = "BATTLE: RESULT=WIN", isError = true)
            )
        )
    }
}
