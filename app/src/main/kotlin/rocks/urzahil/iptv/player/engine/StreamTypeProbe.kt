package rocks.urzahil.iptv.player.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import rocks.urzahil.iptv.core.util.Redactor
import java.util.concurrent.TimeUnit

enum class StreamKind { HLS, DASH, TRANSPORT_STREAM, PROGRESSIVE }

/** Decides what an extensionless URL really is. The network part is [probe]; the rest is pure. */
object StreamTypeProbe {
    private val KNOWN_EXTENSIONS = setOf("m3u8", "m3u", "mpd", "ts", "mp4", "flv", "mkv", "aac", "mp3", "webm")

    /**
     * Probing opens a connection to the provider, which counts against `max_connections` on some panels, so it
     * is only done for http(s) URLs whose path has no recognisable extension.
     */
    fun shouldProbe(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        val ext = if ('.' in last) last.substringAfterLast('.').lowercase() else ""
        return ext !in KNOWN_EXTENSIONS
    }

    fun classify(contentType: String?, head: ByteArray, length: Int = head.size): StreamKind {
        val ct = contentType?.lowercase().orEmpty()
        val text = String(head, 0, minOf(length, 512), Charsets.ISO_8859_1).trimStart(' ', '\r', '\n')
        return when {
            "mpegurl" in ct || text.startsWith("#EXTM3U") -> StreamKind.HLS
            "dash+xml" in ct || text.contains("<MPD") -> StreamKind.DASH
            length > 188 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte() -> StreamKind.TRANSPORT_STREAM
            else -> StreamKind.PROGRESSIVE
        }
    }

    fun mimeOf(kind: StreamKind): String? = when (kind) {
        StreamKind.HLS -> "hls"
        StreamKind.DASH -> "dash"
        StreamKind.TRANSPORT_STREAM -> "video/mp2t"
        StreamKind.PROGRESSIVE -> null
    }

    /** Sends one GET with [headers], reads at most 4 KB and closes. Returns null on any failure. Never logs the URL. */
    suspend fun probe(client: OkHttpClient, url: String, headers: Map<String, String>, redactor: Redactor): StreamKind? =
        withContext(Dispatchers.IO) {
            try {
                val c = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
                val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                c.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val buf = ByteArray(4096)
                    val n = resp.body.byteStream().read(buf).coerceAtLeast(0)
                    classify(resp.header("Content-Type"), buf, n)
                }
            } catch (e: Exception) {
                null
            }
        }
}
