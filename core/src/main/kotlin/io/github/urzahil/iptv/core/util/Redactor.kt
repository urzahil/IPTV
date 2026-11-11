package io.github.urzahil.iptv.core.util

import java.util.concurrent.ConcurrentHashMap

/** Removes credentials from log lines and error text. */
class Redactor {
    private val secrets = ConcurrentHashMap.newKeySet<String>()

    /** Registers a secret (username, password, token). Very short values are ignored. */
    fun register(secret: String?) {
        if (secret == null || secret.length < MIN_SECRET_LENGTH) return
        secrets.add(secret)
        val enc = Urls.encodeQuery(secret)
        if (enc != secret) secrets.add(enc)
    }

    fun clear() = secrets.clear()

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out: String = text
        for (s in secrets.sortedByDescending { it.length }) out = out.replace(s, MASK)
        out = QUERY.replace(out) { "${it.groupValues[1]}=$MASK" }
        out = PATH.replace(out) { "/${it.groupValues[1]}/$MASK/$MASK/" }
        out = USERINFO.replace(out, "//$MASK@")
        return out
    }

    fun redact(t: Throwable): String =
        redact("${t.javaClass.simpleName}: ${t.message.orEmpty()}")

    companion object {
        const val MASK = "***"
        private const val MIN_SECRET_LENGTH = 3
        private val QUERY = Regex("(?i)(?<![A-Za-z])(username|password|pass|pwd|token|auth|key|sig)=[^&\\s]+")
        private val PATH = Regex("/(live|timeshift|movie|series)/[^/\\s]+/[^/\\s]+/")
        private val USERINFO = Regex("//[^/\\s@]+@")
    }
}
