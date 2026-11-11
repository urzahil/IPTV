package io.github.urzahil.iptv.util

import android.util.Log
import io.github.urzahil.iptv.BuildConfig
import io.github.urzahil.iptv.core.util.Redactor

/** Debug-only logging; every message passes through the [Redactor]. Never log URLs or credentials. */
object AppLog {
    @Volatile
    var redactor: Redactor = Redactor()

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, redactor.redact(msg))
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, redactor.redact(msg) + (t?.let { " " + redactor.redact(it) } ?: ""))
    }
}
