package com.example.myapplication.worker

/** Сообщения между основным процессом (сервис) и рабочими процессами WebView (:w0..:w3). */
internal object WorkerProtocol {
    const val WORKER_COUNT = 4

    // клиент -> рабочий процесс
    const val CMD_RUN = 1
    const val CMD_STOP_ACCOUNT = 2
    const val CMD_STOP_ALL = 3
    const val CMD_SKIP_MANGA = 4
    const val CMD_MARK_READ = 5
    const val CMD_DECK_TOGGLE = 6
    const val CMD_RELOAD = 7

    // рабочий процесс -> клиент
    const val EV_LOG = 101
    const val EV_STATUS = 102
    const val EV_MANGA = 103
    const val EV_STATS = 104
    const val EV_DAILY = 105
    const val EV_TOWER = 106
    const val EV_MINE = 107
    const val EV_RECOVERY = 108
    const val EV_RESULT = 109

    const val K_REQ = "req"
    const val K_ACCOUNT_ID = "accountId"
    const val K_ACCOUNT_JSON = "accountJson"
    const val K_SETTINGS_JSON = "settingsJson"
    const val K_PROXY_JSON = "proxyJson"
    const val K_TASK = "task"
    const val K_PROMO = "promo"
    const val K_RESUME = "resume"
    const val K_JSON = "json"
    const val K_STR1 = "s1"
    const val K_STR2 = "s2"
    const val K_STR3 = "s3"
    const val K_STR4 = "s4"
    const val K_STR5 = "s5"
    const val K_BOOL = "b"
    const val K_FLOAT = "f"
    const val K_LONG = "l"
    const val K_INT = "i"
    const val K_OK = "ok"
    const val K_ERR_CLASS = "errClass"
    const val K_ERR_MSG = "errMsg"

    /** Binder-транзакция ограничена ~1 МБ: длинные строки логов обрезаем. */
    const val MAX_TEXT = 20_000
}
