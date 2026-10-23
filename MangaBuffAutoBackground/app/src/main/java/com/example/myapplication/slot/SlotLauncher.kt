package com.example.myapplication.slot

import android.content.Context
import android.content.Intent

/** Запуск слота: слот N = процесс ":slotN" = одна вкладка с одним фиксированным маршрутом. */
object SlotLauncher {
    const val SLOT_COUNT = 3

    const val EXTRA_TAB_ID = "slot.tabId"
    const val EXTRA_URL = "slot.url"
    const val EXTRA_PROXY_TYPE = "slot.proxyType"
    const val EXTRA_HOST = "slot.host"
    const val EXTRA_PORT = "slot.port"
    const val EXTRA_USER = "slot.user"
    const val EXTRA_PASS = "slot.pass"

    private val activities = listOf(
        "com.example.myapplication.slot.Slot0Activity",
        "com.example.myapplication.slot.Slot1Activity",
        "com.example.myapplication.slot.Slot2Activity"
    )

    /** Номер слота по имени процесса вида "<package>:slot2", иначе null. */
    fun slotIndexOf(processName: String?): Int? {
        val tail = processName?.substringAfter(":slot", "") ?: return null
        val n = tail.toIntOrNull() ?: return null
        return n.takeIf { it in 0 until SLOT_COUNT }
    }

    /** Slots are fixed process containers, not an unbounded browser tab pool. */
    fun isValidSlot(slot: Int): Boolean = slot in 0 until SLOT_COUNT

    /** tabId — стабильный идентификатор вкладки; из него строится имя профиля WebView. */
    fun isValidTabId(tabId: String): Boolean = tabId.matches(Regex("[A-Za-z0-9_-]{1,64}"))

    fun profileName(tabId: String): String = "tab_$tabId"

    /** NEW_TASK is needed for an application-context launch; MULTIPLE_TASK bypasses singleTask reuse. */
    internal fun activityLaunchFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK

    fun launch(context: Context, slot: Int, tabId: String, url: String, route: SlotRoute): Result<Unit> {
        if (!isValidSlot(slot)) return Result.failure(IllegalArgumentException("Нет такого слота: $slot"))
        if (!isValidTabId(tabId)) return Result.failure(IllegalArgumentException("Недопустимый id вкладки"))
        route.validate()?.let { return Result.failure(IllegalArgumentException(it)) }
        val normalized = normalizeSlotUrlForRoute(url, route)
            ?: return Result.failure(
                IllegalArgumentException(
                    slotUrlValidationMessage(url)
                )
            )

        val intent = Intent().setClassName(context.packageName, activities[slot])
            .addFlags(activityLaunchFlags())
            .putExtra(EXTRA_TAB_ID, tabId)
            .putExtra(EXTRA_URL, normalized)
            .putExtra(EXTRA_PROXY_TYPE, route.type.name)
            .putExtra(EXTRA_HOST, route.host.trim())
            .putExtra(EXTRA_PORT, route.port)
            .putExtra(EXTRA_USER, route.username)
            .putExtra(EXTRA_PASS, route.password)
        context.startActivity(intent)
        return Result.success(Unit)
    }
}
