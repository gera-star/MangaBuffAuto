package com.example.mangabuffauto

import android.util.Log

object AndroidBotLog {
    private var listener: ((String) -> Unit)? = null

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
    }

    fun log(message: String) {
        Log.i("MangaBuffAuto", message)
        listener?.invoke(message)
    }
}
