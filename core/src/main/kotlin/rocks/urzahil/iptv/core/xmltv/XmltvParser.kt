package rocks.urzahil.iptv.core.xmltv

import rocks.urzahil.iptv.core.epg.NameNormalizer
import rocks.urzahil.iptv.core.io.InputLimitException
import rocks.urzahil.iptv.core.model.ParsedEpgChannel
import rocks.urzahil.iptv.core.model.ParsedProgramme
import java.io.InputStream
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/**
 * @property nowMs reference time for the retention window.
 * @property wantedIds lowercase tvg-ids the playlist wants (null = no filtering).
 * @property wantedNames normalised channel names the playlist wants (null = no filtering).
 */
data class XmltvOptions(
    val nowMs: Long = System.currentTimeMillis(),
    val pastDays: Int = 7,
    val futureDays: Int = 3,
    val wantedIds: Set<String>? = null,
    val wantedNames: Set<String>? = null,
    val language: String = Locale.getDefault().language,
)

data class XmltvResult(
    val channels: Int,
    val programmes: Int,
    /** Programmes dropped by the window/wanted filters or for invalid times. */
    val skipped: Int,
    val warnings: List<String>,
)

class XmltvParser(
    private val options: XmltvOptions = XmltvOptions(),
    private val parserFactory: () -> XmlPullParser = { XmlPullParserFactory.newInstance().newPullParser() },
) {
    private class Pending(val chan: String, val start: Long, val p: ParsedProgramme)

    fun parse(
        input: InputStream,
        onChannel: (ParsedEpgChannel) -> Unit,
        onProgramme: (ParsedProgramme) -> Unit,
    ): XmltvResult {
        val parser = parserFactory()
        try {
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (_: Exception) {
        }
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        } catch (_: Exception) {
        }
        val tail = TailStream(input)
        parser.setInput(tail, null)

        val ctx = Ctx(onChannel, onProgramme)
        try {
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser, ctx)
                        "programme" -> readProgramme(parser, ctx)
                    }
                }
                ev = parser.next()
            }
            // Relaxed mode silently closes open elements at EOF, so check the raw tail for the root end tag.
            if (!tail.endsWithRootClose()) ctx.warnings.add("Unexpected end of file, keeping partial result")
        } catch (e: XmlPullParserException) {
            ctx.warnings.add("XML error, keeping partial result: ${e.message}")
        } catch (e: InputLimitException) {
            throw e
        } catch (e: java.io.IOException) {
            ctx.warnings.add("Read error, keeping partial result: ${e.message}")
        }
        // flush programmes that never received a stop time
        for (pend in ctx.pending.values.toList()) {
            ctx.emit(pend.p.copy(endMs = pend.start + HOUR_MS))
        }
        ctx.pending.clear()
        return XmltvResult(ctx.channels, ctx.programmes, ctx.skipped, ctx.warnings)
    }

    private inner class Ctx(val onChannel: (ParsedEpgChannel) -> Unit, val onProgramme: (ParsedProgramme) -> Unit) {
        val warnings = ArrayList<String>()
        var channels = 0
        var programmes = 0
        var skipped = 0
        /** lowercase channel id -> wanted */
        val known = HashMap<String, Boolean>()
        val pending = HashMap<String, Pending>()
        private val minMs = options.nowMs - options.pastDays * DAY_MS
        private val maxMs = options.nowMs + options.futureDays * DAY_MS

        fun isWantedId(idLower: String): Boolean {
            val k = known[idLower]
            if (k != null) return k
            // channel never declared: keep only if the playlist asks for that id
            val ids = options.wantedIds ?: return options.wantedNames == null
            return idLower in ids
        }

        fun emit(p: ParsedProgramme) {
            var prog = p
            if (prog.endMs <= prog.startMs) { skipped++; return }
            if (prog.endMs - prog.startMs > MAX_PROGRAMME_MS) prog = prog.copy(endMs = prog.startMs + MAX_PROGRAMME_MS)
            if (prog.endMs < minMs || prog.startMs > maxMs) { skipped++; return }
            programmes++
            onProgramme(prog)
        }
    }

    private fun readChannel(parser: XmlPullParser, ctx: Ctx) {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>()
        var icon: String? = null
        val depth = parser.depth
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw eof()
            if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> readText(parser).takeIf { it.isNotEmpty() }?.let { names.add(it) }
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src")
                        if (icon == null && !src.isNullOrBlank()) icon = src.trim()
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
        }
        if (id.isEmpty()) return
        val lower = id.lowercase()
        val wantedFilter = options.wantedIds != null || options.wantedNames != null
        val wanted = !wantedFilter ||
            (options.wantedIds?.contains(lower) == true) ||
            (options.wantedNames != null && names.any { NameNormalizer.normalize(it) in options.wantedNames })
        ctx.known[lower] = wanted
        if (wanted) {
            ctx.channels++
            ctx.onChannel(ParsedEpgChannel(id, names, icon))
        }
    }

    private fun readProgramme(parser: XmlPullParser, ctx: Ctx) {
        val chan = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
        val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
        val catchupId = parser.getAttributeValue(null, "catchup-id")?.trim()?.takeIf { it.isNotEmpty() }
        val titles = ArrayList<Pair<String?, String>>()
        val subs = ArrayList<Pair<String?, String>>()
        val descs = ArrayList<Pair<String?, String>>()
        val cats = ArrayList<String>()
        var icon: String? = null
        var onscreen: String? = null
        var ns: String? = null
        val depth = parser.depth
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw eof()
            if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> { val l = parser.getAttributeValue(null, "lang"); titles.add(l to readText(parser)) }
                "sub-title" -> { val l = parser.getAttributeValue(null, "lang"); subs.add(l to readText(parser)) }
                "desc" -> { val l = parser.getAttributeValue(null, "lang"); descs.add(l to readText(parser)) }
                "category" -> readText(parser).takeIf { it.isNotEmpty() }?.let { cats.add(it) }
                "episode-num" -> {
                    val sys = parser.getAttributeValue(null, "system")?.lowercase()
                    val t = readText(parser)
                    when (sys) {
                        "onscreen" -> if (onscreen == null && t.isNotEmpty()) onscreen = t
                        "xmltv_ns" -> if (ns == null) ns = formatXmltvNs(t)
                    }
                }
                "icon" -> {
                    val src = parser.getAttributeValue(null, "src")
                    if (icon == null && !src.isNullOrBlank()) icon = src.trim()
                    skip(parser)
                }
                else -> skip(parser)
            }
        }
        if (start == null || chan.isEmpty()) { ctx.skipped++; return }
        val chanKey = chan.lowercase()
        if (!ctx.isWantedId(chanKey)) { ctx.skipped++; return }

        val prog = ParsedProgramme(
            xmltvChannelId = chan,
            startMs = start,
            endMs = stop ?: start,
            title = pick(titles).orEmpty(),
            subTitle = pick(subs)?.takeIf { it.isNotEmpty() },
            desc = pick(descs)?.takeIf { it.isNotEmpty() },
            categories = cats.takeIf { it.isNotEmpty() }?.joinToString(", "),
            episode = onscreen ?: ns,
            icon = icon,
            catchupId = catchupId,
        )

        // Close the pending programme (no stop) of this channel with this start.
        ctx.pending.remove(chanKey)?.let { prev ->
            ctx.emit(prev.p.copy(endMs = start))
        }
        if (stop == null) {
            ctx.pending[chanKey] = Pending(chan, start, prog)
        } else {
            ctx.emit(prog)
        }
    }

    /** Remembers the last bytes read so truncation can be detected. */
    private class TailStream(input: InputStream) : java.io.FilterInputStream(input) {
        private val tail = ByteArray(64)
        private var filled = 0

        private fun remember(b: ByteArray, off: Int, len: Int) {
            if (len >= tail.size) {
                System.arraycopy(b, off + len - tail.size, tail, 0, tail.size); filled = tail.size
            } else {
                val keep = minOf(filled, tail.size - len)
                System.arraycopy(tail, filled - keep, tail, 0, keep)
                System.arraycopy(b, off, tail, keep, len)
                filled = keep + len
            }
        }

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) remember(byteArrayOf(r.toByte()), 0, 1)
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) remember(b, off, n)
            return n
        }

        fun endsWithRootClose(): Boolean =
            String(tail, 0, filled, Charsets.ISO_8859_1).trimEnd().endsWith("</tv>")
    }

    private fun eof() = XmlPullParserException("Unexpected end of document")

    private fun pick(items: List<Pair<String?, String>>): String? {
        if (items.isEmpty()) return null
        val lang = options.language.lowercase()
        val m = items.firstOrNull { it.first?.lowercase()?.substringBefore('-') == lang && lang.isNotEmpty() }
        return (m ?: items.first()).second
    }

    private fun formatXmltvNs(t: String): String? {
        val parts = t.split('.')
        fun num(i: Int): Int? = parts.getOrNull(i)?.substringBefore('/')?.trim()?.toIntOrNull()
        val s = num(0)
        val e = num(1)
        return when {
            s != null && e != null -> "S${s + 1}E${e + 1}"
            e != null -> "E${e + 1}"
            s != null -> "S${s + 1}"
            else -> null
        }
    }

    /** Reads the text of the current element (nested elements ignored), whitespace collapsed. Leaves parser on END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw eof()
            if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT || ev == XmlPullParser.ENTITY_REF) {
                if (parser.depth == depth) sb.append(parser.text)
            }
        }
        return WHITESPACE.replace(sb.toString(), " ").trim()
    }

    /** Skips the current element including children; leaves the parser on its END_TAG. */
    private fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw eof()
            if (ev == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        const val MAX_PROGRAMME_MS = 12 * HOUR_MS
        private val WHITESPACE = Regex("\\s+")
    }
}
