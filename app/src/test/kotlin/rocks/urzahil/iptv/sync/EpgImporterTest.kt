package rocks.urzahil.iptv.sync

import org.robolectric.RobolectricTestRunner
import com.google.common.truth.Truth.assertThat
import rocks.urzahil.iptv.core.epg.ProgrammeId
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.repo.EpgRepository
import rocks.urzahil.iptv.data.repo.EpgRequest
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EpgImporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private val now = 1_794_398_400_000L // 2026-11-11 12:00Z
    private val h = 3_600_000L
    private val day = 24 * h
    private val settings = AppSettings(epgPastDays = 7, epgFutureDays = 3)

    @Before fun setUp() { db = newDb() }
    @After fun tearDown() { db.close() }

    private fun ts(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss 'Z'", java.util.Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)).replace(" Z", " +0000")

    private fun prog(ch: String, start: Long, end: Long, title: String) =
        """<programme start="${ts(start)}" stop="${ts(end)}" channel="$ch"><title>$title</title><desc>d $title</desc></programme>"""

    private fun importer(): EpgImporter = EpgImporter(
        db, OkHttpClient(), rocks.urzahil.iptv.data.net.Downloader(Redactor()),
        SourceSecrets(FakeCipher(), Redactor()), Redactor(), tmp.root,
    )

    private fun setup(): Pair<Long, Long> {
        val src = db.addSource()
        M3uImporter(db).import(
            src,
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cat.one" catchup="shift" catchup-days="3",Cat One
            http://s/1
            #EXTINF:-1 tvg-id="plain.one",Plain One
            http://s/2
            #EXTINF:-1 tvg-name="Name Matched",Name Matched HD
            http://s/3
            """.trimIndent().byteInputStream(),
        )
        val epgId = EpgSourceManager(db, SourceSecrets(FakeCipher(), Redactor())).addUserUrl(src.id, "http://epg.example/x.xml")
        return src.id to epgId
    }

    @Test fun importsOnlyWantedChannelsAndHonoursPerChannelRetention() {
        val (srcId, epgId) = setup()
        val xml = """<?xml version="1.0"?><tv>
            <channel id="cat.one"><display-name>Cat One</display-name></channel>
            <channel id="plain.one"><display-name>Plain One</display-name></channel>
            <channel id="other"><display-name>Not Wanted</display-name></channel>
            <channel id="x1"><display-name>Name Matched</display-name></channel>
            ${prog("cat.one", now - 5 * day, now - 5 * day + h, "cat-too-old")}
            ${prog("cat.one", now - 2 * day, now - 2 * day + h, "cat-in-archive")}
            ${prog("cat.one", now - h, now + h, "cat-now")}
            ${prog("cat.one", now + 2 * day, now + 2 * day + h, "cat-future-ok")}
            ${prog("cat.one", now + 5 * day, now + 5 * day + h, "cat-too-far")}
            ${prog("plain.one", now - 2 * day, now - 2 * day + h, "plain-old")}
            ${prog("plain.one", now - h, now + h, "plain-now")}
            ${prog("plain.one", now + h, now + 2 * h, "plain-next")}
            ${prog("other", now - h, now + h, "other-now")}
            ${prog("x1", now - h, now + h, "name-now")}
            </tv>""".trimIndent()
        val f = File(tmp.root, "epg.xml").also { it.writeText(xml) }
        val r = importer().importFile(epgId, srcId, f, settings, now)
        assertThat(r.programmes).isEqualTo(6)

        EpgMapper(db).map(srcId)
        val rows = db.channelDao().matchRows(srcId).associateBy { it.name }
        assertThat(rows.values.all { it.epgChannelId != null }).isTrue()

        val repo = EpgRepository(db)
        fun titles(name: String): List<String> = runBlocking {
            val c = rows.getValue(name)
            repo.programmes(c.epgChannelId!!, 0, now - 10 * day, now + 10 * day).map { it.title }
        }
        assertThat(titles("Cat One")).containsExactly("cat-in-archive", "cat-now", "cat-future-ok").inOrder()
        assertThat(titles("Plain One")).containsExactly("plain-now", "plain-next").inOrder()
        assertThat(titles("Name Matched HD")).containsExactly("name-now")
        // Details are stored apart from the hot table.
        val any = runBlocking { repo.programmes(rows.getValue("Cat One").epgChannelId!!, 0, now, now + h) }.first()
        assertThat(runBlocking { repo.details(any.id) }!!.description).isEqualTo("d cat-now")
        assertThat(db.epgDao().idMap(epgId).map { it.xmltvIdLower }).doesNotContain("other")
    }

    @Test fun swapKeepsOlderHistoryAndReplacesOverlap() {
        val (srcId, epgId) = setup()
        val first = """<tv><channel id="cat.one"><display-name>Cat One</display-name></channel>
            ${prog("cat.one", now - 2 * day, now - 2 * day + h, "old-a")}
            ${prog("cat.one", now, now + h, "v1")}</tv>"""
        importer().importFile(epgId, srcId, File(tmp.root, "a.xml").also { it.writeText(first) }, settings, now)
        val second = """<tv><channel id="cat.one"><display-name>Cat One</display-name></channel>
            ${prog("cat.one", now, now + h, "v2")}
            ${prog("cat.one", now + h, now + 2 * h, "v2-next")}</tv>"""
        importer().importFile(epgId, srcId, File(tmp.root, "b.xml").also { it.writeText(second) }, settings, now)
        EpgMapper(db).map(srcId)
        val ch = db.channelDao().matchRows(srcId).first { it.name == "Cat One" }.epgChannelId!!
        val titles = runBlocking { EpgRepository(db).programmes(ch, 0, now - 10 * day, now + 10 * day) }.map { it.title }
        assertThat(titles).containsExactly("old-a", "v2", "v2-next").inOrder()
        assertThat(db.programmeDao().count()).isEqualTo(3)
        // Staging is empty again.
        assertThat(db.programmeDao().stagedMinStarts()).isEmpty()
    }

    @Test fun prunerAppliesRetentionAfterSettingsShrink() {
        val (srcId, epgId) = setup()
        val xml = """<tv><channel id="cat.one"><display-name>Cat One</display-name></channel>
            <channel id="plain.one"><display-name>Plain One</display-name></channel>
            ${prog("cat.one", now - 3 * day, now - 3 * day + h, "c-3d")}
            ${prog("cat.one", now - day, now - day + h, "c-1d")}
            ${prog("cat.one", now + 2 * day, now + 2 * day + h, "c+2d")}
            ${prog("plain.one", now - h, now + h, "p-now")}
            ${prog("plain.one", now + 2 * day, now + 2 * day + h, "p+2d")}</tv>"""
        importer().importFile(epgId, srcId, File(tmp.root, "a.xml").also { it.writeText(xml) }, settings, now)
        EpgMapper(db).map(srcId)
        Pruner(db).prune(AppSettings(epgPastDays = 2, epgFutureDays = 1), now)

        val repo = EpgRepository(db)
        val rows = db.channelDao().matchRows(srcId).associateBy { it.name }
        fun titles(n: String) = runBlocking { repo.programmes(rows.getValue(n).epgChannelId!!, 0, now - 10 * day, now + 10 * day) }.map { it.title }
        assertThat(titles("Cat One")).containsExactly("c-1d")
        assertThat(titles("Plain One")).containsExactly("p-now")
    }

    @Test fun shiftIsAppliedAtReadTime() {
        val (srcId, epgId) = setup()
        val xml = """<tv><channel id="plain.one"><display-name>Plain One</display-name></channel>
            ${prog("plain.one", now - h, now + h, "p")}</tv>"""
        importer().importFile(epgId, srcId, File(tmp.root, "a.xml").also { it.writeText(xml) }, settings, now)
        EpgMapper(db).map(srcId)
        val ch = db.channelDao().matchRows(srcId).first { it.name == "Plain One" }.epgChannelId!!
        val nn = runBlocking { EpgRepository(db).nowNext(listOf(EpgRequest(1, ch, shiftMin = 60)), now + 90 * 60_000L) }
        assertThat(nn.getValue(1).now!!.title).isEqualTo("p")
        assertThat(nn.getValue(1).now!!.startMs).isEqualTo(now)
        assertThat(ProgrammeId.epgChannelOf(nn.getValue(1).now!!.id)).isEqualTo(ch)
    }
}
