package io.github.urzahil.iptv.core.xtream

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

object Base64Text {
    /** Decodes base64 text; falls back to the raw input if it is not base64 or does not decode to clean UTF-8. */
    fun decode(raw: String?): String {
        if (raw == null) return ""
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val bytes = try {
            Base64.getDecoder().decode(t)
        } catch (e: IllegalArgumentException) {
            try {
                Base64.getMimeDecoder().decode(t.filter { !it.isWhitespace() })
            } catch (e2: IllegalArgumentException) {
                return raw
            }
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return raw
        }
        if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return raw
        return text
    }
}
