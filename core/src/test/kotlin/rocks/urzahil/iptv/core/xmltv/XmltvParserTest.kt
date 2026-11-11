package rocks.urzahil.iptv.core.xmltv

import com.google.common.truth.Truth.assertThat
import rocks.urzahil.iptv.core.io.Compression
import rocks.urzahil.iptv.core.model.ParsedEpgChannel
import rocks.urzahil.iptv.core.model.ParsedProgramme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.kxml2.io.KXmlParser

class XmltvParserTest {
    private val now = 1794398400_000L // 2026-11-11 12:00Z
    private val H = 3_600_000L

    private class R(val channels: List<ParsedEpgChannel>, val progs: List<ParsedProgramme>, val result: XmltvResult)

    private fun run(xml: String, o: XmltvOptions = XmltvOptions(nowMs = now, language = "en")): R = run(xml.toByteArray().inputStream(), o)

    private fun run(input: InputStream, o: XmltvOptions): R {
        val ch = ArrayList<ParsedEpgChannel>()
        val pr = ArrayList<ParsedProgramme>()
        val res = XmltvParser(o) { KXmlParser() }.parse(input, { ch.add(it) }, { pr.add(it) })
        return R(ch, pr, res)
    }

    private fun tv(body: String) = """<?xml version="1.0" encoding="UTF-8"?><tv generator-info-name="t">$body</tv>"""

    private fun prog(ch: String, start: String, stop: String?, extra: String = "<title>T</title>") =
        """<programme start="$start +0000"${stop?.let { """ stop="$it +0000"""" } ?: ""} channel="$ch">$extra</programme>"""

    @Test fun channels_with_multiple_display_names() {
        val r = run(tv("""<channel id=" bbc1.uk "><display-name>BBC One</display-name><display-name lang="en">BBC 1</display-name><icon src="http://i/a.png"/><icon src="http://i/b.png"/><url>http://x</url></channel>"""))
        assertThat(r.channels).hasSize(1)
        assertThat(r.channels[0].id).isEqualTo("bbc1.uk")
        assertThat(r.channels[0].displayNames).containsExactly("BBC One", "BBC 1").inOrder()
        assertThat(r.channels[0].icon).isEqualTo("http://i/a.png")
    }

    @Test fun language_preference() {
        val r = run(tv(prog("a", "20261111110000", "20261111120000",
            """<title lang="de">Titel</title><title lang="en">Title</title><desc lang="de">Beschr</desc><desc lang="en">Descr</desc><sub-title>Sub</sub-title>""")))
        assertThat(r.progs[0].title).isEqualTo("Title")
        assertThat(r.progs[0].desc).isEqualTo("Descr")
        val r2 = run(tv(prog("a", "20261111110000", "20261111120000",
            """<title lang="de">Titel</title><title lang="fr">Titre</title>""")))
        assertThat(r2.progs[0].title).isEqualTo("Titel")
    }

    @Test fun missing_stop_filled_from_next_start_and_last_gets_one_hour() {
        val r = run(tv(
            prog("a", "20261111100000", null) + prog("a", "20261111103000", null) + prog("a", "20261111110000", null) +
                prog("b", "20261111100000", "20261111101500"),
        ))
        val a = r.progs.filter { it.xmltvChannelId == "a" }.sortedBy { it.startMs }
        assertThat(a).hasSize(3)
        assertThat(a[0].endMs - a[0].startMs).isEqualTo(30 * 60_000L)
        assertThat(a[1].endMs).isEqualTo(a[2].startMs)
        assertThat(a[2].endMs - a[2].startMs).isEqualTo(H)
    }

    @Test fun stop_not_after_start_is_dropped() {
        val r = run(tv(prog("a", "20261111110000", "20261111110000") + prog("a", "20261111120000", "20261111110000") +
            prog("a", "20261111110000", "20261111120000")))
        assertThat(r.progs).hasSize(1)
        assertThat(r.result.skipped).isEqualTo(2)
    }

    @Test fun long_programmes_clipped_to_12h() {
        val r = run(tv(prog("a", "20261111000000", "20261112200000")))
        assertThat(r.progs[0].endMs - r.progs[0].startMs).isEqualTo(12 * H)
    }

    @Test fun unknown_nested_elements_skipped() {
        val r = run(tv(prog("a", "20261111110000", "20261111120000",
            """<credits><actor role="x">Someone<b>deep</b></actor></credits><title>Real</title><video><aspect>16:9</aspect></video><category>News</category><category>Live</category><icon src="http://i/p.png"/><rating system="x"><value>PG</value></rating>""")))
        val p = r.progs[0]
        assertThat(p.title).isEqualTo("Real")
        assertThat(p.categories).isEqualTo("News, Live")
        assertThat(p.icon).isEqualTo("http://i/p.png")
    }

    @Test fun bare_ampersand_tolerated() {
        val r = run(tv("""<channel id="a"><display-name>Fox & Friends</display-name></channel>""" +
            prog("a", "20261111110000", "20261111120000", "<title>Rock & Roll &unknown; X</title>")))
        assertThat(r.channels[0].displayNames[0]).isEqualTo("Fox & Friends")
        assertThat(r.progs[0].title).startsWith("Rock & Roll")
        assertThat(r.result.warnings).isEmpty()
    }

    @Test fun truncated_file_keeps_partial_result() {
        val full = tv(prog("a", "20261111110000", "20261111120000") + prog("a", "20261111120000", "20261111130000"))
        val cut = full.substring(0, full.indexOf("20261111130000") + 5)
        val r = run(cut)
        assertThat(r.progs).hasSize(1)
        assertThat(r.result.warnings).isNotEmpty()
    }

    @Test fun gzip_and_zip_via_compression() {
        val xml = tv(prog("a", "20261111110000", "20261111120000")).toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(xml) } }.toByteArray()
        val zip = ByteArrayOutputStream().also {
            ZipOutputStream(it).use { z -> z.putNextEntry(ZipEntry("epg.xml")); z.write(xml); z.closeEntry() }
        }.toByteArray()
        for (b in listOf(xml, gz, zip)) {
            val r = run(Compression.open(ByteArrayInputStream(b)), XmltvOptions(nowMs = now))
            assertThat(r.progs).hasSize(1)
        }
    }

    @Test fun window_filter() {
        val o = XmltvOptions(nowMs = now, pastDays = 1, futureDays = 1, language = "en")
        val r = run(tv(
            prog("a", "20261109100000", "20261109110000") + // too old
                prog("a", "20261110130000", "20261110140000") + // ok (within past day)
                prog("a", "20261110115900", "20261110120000") + // ends exactly at window start: kept
                prog("a", "20261111100000", "20261111110000") +
                prog("a", "20261112120000", "20261112130000") + // starts exactly at window end: kept
                prog("a", "20261112120100", "20261112130000"), // too far
        ), o)
        assertThat(r.progs.map { it.startMs }).hasSize(4)
        assertThat(r.result.skipped).isEqualTo(2)
    }

    @Test fun wanted_channel_filter() {
        val body = tv(
            """<channel id="Keep.Id"><display-name>Whatever</display-name></channel>""" +
                """<channel id="byname"><display-name>Some Name HD</display-name></channel>""" +
                """<channel id="drop"><display-name>Dropped</display-name></channel>""" +
                prog("keep.id", "20261111110000", "20261111120000") +
                prog("byname", "20261111110000", "20261111120000") +
                prog("drop", "20261111110000", "20261111120000") +
                prog("undeclared.wanted", "20261111110000", "20261111120000") +
                prog("undeclared.other", "20261111110000", "20261111120000"),
        )
        val o = XmltvOptions(nowMs = now, wantedIds = setOf("keep.id", "undeclared.wanted"), wantedNames = setOf("somename"))
        val r = run(body, o)
        assertThat(r.channels.map { it.id }).containsExactly("Keep.Id", "byname").inOrder()
        assertThat(r.progs.map { it.xmltvChannelId }).containsExactly("keep.id", "byname", "undeclared.wanted")
    }

    @Test fun episode_formatting() {
        val r = run(tv(
            prog("a", "20261111110000", "20261111120000", """<title>A</title><episode-num system="xmltv_ns">1.4.0/1</episode-num>""") +
                prog("a", "20261111120000", "20261111130000", """<title>B</title><episode-num system="xmltv_ns">1.4.</episode-num><episode-num system="onscreen">S02E05</episode-num>""") +
                prog("a", "20261111130000", "20261111140000", """<title>C</title><episode-num system="xmltv_ns"> . 7 . </episode-num>""") +
                prog("a", "20261111140000", "20261111150000", """<title>D</title><episode-num system="dd_progid">EP123</episode-num>"""),
        ))
        assertThat(r.progs.map { it.episode }).containsExactly("S2E5", "S02E05", "E8", null).inOrder()
    }

    @Test fun catchup_id_captured() {
        val r = run(tv("""<programme start="20261111110000 +0000" stop="20261111120000 +0000" channel="a" catchup-id="ev42"><title>T</title></programme>"""))
        assertThat(r.progs[0].catchupId).isEqualTo("ev42")
    }

    @Test fun invalid_programmes_skipped() {
        val r = run(tv("""<programme start="bad" stop="20261111120000" channel="a"><title>T</title></programme>""" +
            """<programme start="20261111110000" stop="20261111120000"><title>NoChan</title></programme>"""))
        assertThat(r.progs).isEmpty()
    }

    @Test fun default_factory_works_on_jvm() {
        val ch = ArrayList<ParsedEpgChannel>()
        XmltvParser(XmltvOptions(nowMs = now)).parse(tv("""<channel id="a"><display-name>A</display-name></channel>""").byteInputStream(), { ch.add(it) }, {})
        assertThat(ch).hasSize(1)
    }
}
