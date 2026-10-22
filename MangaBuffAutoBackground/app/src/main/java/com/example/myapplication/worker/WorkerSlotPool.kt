package com.example.myapplication.worker

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Пул рабочих процессов WebView. Прокси WebView задаётся на весь процесс, поэтому в одном
 * процессе одновременно могут работать только аккаунты с ОДИНАКОВЫМ маршрутом. Аккаунт с другим
 * маршрутом получает другой свободный процесс; если свободных нет — ждёт.
 *
 * Модель не зависит от Android и проверяется обычными JVM-тестами.
 */
internal class WorkerSlotPool(private val size: Int) {
    private val lock = Any()
    private val activeRoute = arrayOfNulls<String>(size)
    private val leases = IntArray(size)
    private val lastRoute = arrayOfNulls<String>(size)
    private var changed = CompletableDeferred<Unit>()

    init {
        require(size > 0)
    }

    suspend fun acquire(route: String): Lease {
        while (true) {
            val signal: CompletableDeferred<Unit>
            synchronized(lock) {
                // 1) процесс, который уже работает с тем же маршрутом
                var index = (0 until size).firstOrNull { leases[it] > 0 && activeRoute[it] == route }
                // 2) свободный процесс, где этот маршрут применялся в прошлый раз (без смены прокси)
                if (index == null) index = (0 until size).firstOrNull { leases[it] == 0 && lastRoute[it] == route }
                // 3) любой свободный процесс
                if (index == null) index = (0 until size).firstOrNull { leases[it] == 0 }
                if (index != null) {
                    activeRoute[index] = route
                    lastRoute[index] = route
                    leases[index]++
                    return Lease(index, route) { release(index) }
                }
                signal = changed
            }
            signal.await()
        }
    }

    private fun release(index: Int) {
        val completed = synchronized(lock) {
            check(leases[index] > 0) { "Worker lease released twice" }
            leases[index]--
            if (leases[index] == 0) {
                activeRoute[index] = null
                val c = changed
                changed = CompletableDeferred()
                c
            } else {
                null
            }
        }
        completed?.complete(Unit)
    }

    class Lease internal constructor(
        val index: Int,
        val route: String,
        private val releaseAction: () -> Unit
    ) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) releaseAction()
        }
    }
}
