package io.github.urzahil.iptv.data.security

import io.github.urzahil.iptv.core.util.Redactor
import io.github.urzahil.iptv.data.db.entities.EpgSourceEntity
import io.github.urzahil.iptv.data.db.entities.SourceEntity

/** Decrypted Xtream account. Keep instances short-lived; never log or persist them. */
data class XtreamCredentials(val base: String, val username: String, val password: String) {
    override fun toString(): String = "XtreamCredentials(***)"
}

/** The only place stored credentials and URLs are decrypted. Decrypted secrets are registered with the [Redactor]. */
class SourceSecrets(private val cipher: SecretCipher, private val redactor: Redactor) {
    fun encrypt(plain: String): String = cipher.encrypt(plain)

    fun decrypt(token: String?): String? = token?.let { cipher.decrypt(it) }

    /** Null for non-Xtream sources or when the stored credentials cannot be decrypted. */
    fun xtreamCredentials(source: SourceEntity): XtreamCredentials? {
        val base = source.serverUrl ?: return null
        val user = decrypt(source.usernameEnc) ?: return null
        val pass = decrypt(source.passwordEnc) ?: return null
        redactor.register(user)
        redactor.register(pass)
        return XtreamCredentials(base, user, pass)
    }

    fun m3uUrl(source: SourceEntity): String? {
        val url = decrypt(source.m3uUrlEnc) ?: return null
        registerUrlSecrets(url)
        return url
    }

    fun epgUrl(epg: EpgSourceEntity): String? {
        val url = decrypt(epg.urlEnc) ?: return null
        registerUrlSecrets(url)
        return url
    }

    /** Registers query values of a URL (tokens, passwords) so they are masked in any error text. */
    fun registerUrlSecrets(url: String) {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return
        for (pair in q.split('&')) {
            val v = pair.substringAfter('=', "")
            if (v.length >= 3) redactor.register(io.github.urzahil.iptv.core.util.Urls.decode(v))
        }
    }
}
