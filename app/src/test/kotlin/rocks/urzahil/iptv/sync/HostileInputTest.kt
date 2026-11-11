package rocks.urzahil.iptv.sync

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.iptv.core.io.InputLimitException
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.core.xtream.XtreamException
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.net.Downloader
import rocks.urzahil.iptv.data.repo.EpgRepository
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.security.XtreamCredentials
import rocks.urzahil.iptv.data.settings.AppSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HostileInputTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private val now = 1_794_398_400_000L // 2026-11-11 12:00Z
    private val settings = AppSettings(epgPastDays = 7, epgFutureDays = 3)

    @Before fun setUp() { db = newDb() }
    @After fun tearDown() { db.close() }

    private fun ts(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " +0000"
    private fun prog(ch: String, start: Long, end: Long, title: String) =
        """<programme start="${ts(start)}" stop="${ts(end)}" channel="$ch"><title>$title</title></programme>"""

    private fun importer(): EpgImporter = EpgImporter(
        db, OkHttpClient(), Downloader(Redactor()), SourceSecrets(FakeCipher(), Redactor()), Redactor(), tmp.root,
    )

    // ---- M3U scheme allowlist

    @Test fun m3uEntriesWithLocalSchemesAreDropped() {
        val src = db.addSource()
        val text = """
            #EXTM3U
            #EXTINF:-1,Http
            http://s/1
            #EXTINF:-1,Https Upper
            HTTPS://s/2
            #EXTINF:-1,Udp
            udp://@239.0.0.1:1234
            #EXTINF:-1,Rtmp
            rtmp://s/live/3
            #EXTINF:-1,File
            file:///data/data/rocks.urzahil.iptv/databases/app.db
            #EXTINF:-1,Content
            content://media/external/video/1
            #EXTINF:-1,Resource
            android.resource://rocks.urzahil.iptv/1
            #EXTINF:-1,Data
            data:text/plain;base64,AAAA
            #EXTINF:-1,Relative
            stream/4.ts
        """.trimIndent()
        val r = M3uImporter(db).import(src, text.byteInputStream())
        assertThat(r.channels).isEqualTo(4)
        assertThat(r.dropped).isEqualTo(5)
        assertThat(db.channelDao().matchRows(src.id).map { it.name }).containsExactly("Http", "Https Upper", "Udp", "Rtmp")
    }

    @Test fun m3uWithoutLineBreaksIsRejectedAsIoError() {
        val src = db.addSource()
        // Over the 1 MB line limit, no newline anywhere.
        val e = assertThrows(IOException::class.java) { M3uImporter(db).import(src, "#EXTM3U ".plus("x".repeat(1_100_000)).byteInputStream()) }
        assertThat(e).isInstanceOf(InputLimitException::class.java)
        assertThat(db.channelDao().count(src.id)).isEqualTo(0)
    }

    // ---- Xtream responses

    @Test fun xtreamResponseOverTheLimitIsRejectedWithAGenericMessage() {
        val server = MockWebServer().also { it.start() }
        try {
            // The account response may be at most 16 MB.
            server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(17 * 1024 * 1024) { 'a'.code.toByte() })).build())
            val src = db.addSource(SourceType.XTREAM)
            val creds = XtreamCredentials(server.url("/").toString().trimEnd('/'), "user", "pass")
            val e = assertThrows(XtreamException::class.java) { XtreamImporter(db, OkHttpClient()).import(src, creds, "UA") }
            assertThat(e).hasMessageThat().isEqualTo("Unexpected server response")
        } finally {
            server.close()
        }
    }

    // ---- EPG

    private fun epgSetup(): Triple<Long, Long, Long> {
        val src = db.addSource()
        M3uImporter(db).import(
            src,
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"cat.one\",Cat One\nhttp://s/1\n#EXTINF:-1 tvg-id=\"plain.one\",Plain One\nhttp://s/2\n".byteInputStream(),
        )
        val mgr = EpgSourceManager(db, SourceSecrets(FakeCipher(), Redactor()))
        return Triple(src.id, mgr.addUserUrl(src.id, "http://epg.example/a.xml"), mgr.addUserUrl(src.id, "http://epg.example/b.xml"))
    }

    @Test fun concurrentEpgImportsDoNotInterfere() {
        val (srcId, epgA, epgB) = epgSetup()
        val m = 60_000L
        val countA = 2600 // more than one staging batch (2000), so A is mid-import when B starts
        val countB = 40
        val xmlA = "<tv><channel id=\"cat.one\"><display-name>Cat One</display-name></channel>" +
            (0 until countA).joinToString("") { prog("cat.one", now + it * m, now + (it + 1) * m, "a$it") } + "</tv>"
        val xmlB = "<tv><channel id=\"plain.one\"><display-name>Plain One</display-name></channel>" +
            (0 until countB).joinToString("") { prog("plain.one", now + it * m, now + (it + 1) * m, "b$it") } + "</tv>"
        val fileA = File(tmp.root, "a.xml").also { it.writeText(xmlA) }
        val fileB = File(tmp.root, "b.xml").also { it.writeText(xmlB) }

        val inMiddle = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val bDone = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val resultA = AtomicReference<EpgImportResult.Imported>()
        val resultB = AtomicReference<EpgImportResult.Imported>()

        val ta = Thread {
            try {
                resultA.set(importer().importFile(epgA, srcId, fileA, settings, now) {
                    // Called after the first batch was staged: hold A here, staging half full.
                    if (first.getAndSet(false)) { inMiddle.countDown(); release.await(30, TimeUnit.SECONDS) }
                })
            } catch (t: Throwable) { failure.set(t) }
        }
        val tb = Thread {
            try {
                resultB.set(importer().importFile(epgB, srcId, fileB, settings, now))
            } catch (t: Throwable) { failure.set(t) } finally { bDone.set(true) }
        }
        ta.start()
        assertThat(inMiddle.await(30, TimeUnit.SECONDS)).isTrue()
        tb.start()
        Thread.sleep(500)
        // B must wait for A: it may neither clear A's staged rows nor swap them.
        assertThat(bDone.get()).isFalse()
        release.countDown()
        ta.join(60_000); tb.join(60_000)
        assertThat(failure.get()).isNull()

        assertThat(resultA.get().programmes).isEqualTo(countA)
        assertThat(resultB.get().programmes).isEqualTo(countB)
        assertThat(db.programmeDao().count()).isEqualTo(countA + countB)
        EpgMapper(db).map(srcId)
        val rows = db.channelDao().matchRows(srcId).associateBy { it.name }
        val day = 24 * 3_600_000L
        val repo = EpgRepository(db)
        fun titles(name: String) = runBlocking { repo.programmes(rows.getValue(name).epgChannelId!!, 0, now - day, now + 3 * day) }.map { it.title }
        assertThat(titles("Cat One")).hasSize(countA)
        assertThat(titles("Cat One").first()).isEqualTo("a0")
        assertThat(titles("Cat One").last()).isEqualTo("a${countA - 1}")
        assertThat(titles("Plain One")).hasSize(countB)
        assertThat(db.programmeDao().stagedMinStarts()).isEmpty()
    }

    @Test fun aCancelledWaitDoesNotHoldTheStagingLock() {
        val (srcId, epgA, _) = epgSetup()
        val file = File(tmp.root, "a.xml").also { it.writeText("<tv><channel id=\"cat.one\"><display-name>Cat One</display-name></channel></tv>") }
        val job = kotlinx.coroutines.Job()
        job.cancel()
        // Not blocked by a previous run, and a cancelled job stops at the first checkpoint.
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            importer().importFile(epgA, srcId, file, settings, now, job)
        }
        // The lock was released: a normal import goes through.
        assertThat(importer().importFile(epgA, srcId, file, settings, now).channels).isEqualTo(1)
    }

    @Test fun hugeTextNodeInAGuideFileIsRejectedAndLeavesNothingStaged() {
        val (srcId, epgA, _) = epgSetup()
        val big = "x".repeat(5 * 1024 * 1024) // over the 4 MB text node limit
        val xml = "<tv><channel id=\"cat.one\"><display-name>Cat One</display-name></channel>" +
            prog("cat.one", now, now + 60_000, "ok") +
            "<programme start=\"${ts(now + 120_000)}\" stop=\"${ts(now + 180_000)}\" channel=\"cat.one\"><title>t</title><desc>$big</desc></programme></tv>"
        val file = File(tmp.root, "big.xml").also { it.writeText(xml) }
        val e = assertThrows(IOException::class.java) { importer().importFile(epgA, srcId, file, settings, now) }
        assertThat(e).isInstanceOf(InputLimitException::class.java)
        assertThat(e).hasMessageThat().isEqualTo("Guide file is malformed")
        // Neither a partial swap nor leftover staging rows.
        assertThat(db.programmeDao().count()).isEqualTo(0)
        assertThat(db.programmeDao().stagedMinStarts()).isEmpty()
    }

    @Test fun hugeCdataWithAngleBracketsIsRejectedToo() {
        val (srcId, epgA, _) = epgSetup()
        val xml = "<tv><channel id=\"cat.one\"><display-name><![CDATA[" + "<x".repeat(3 * 1024 * 1024) + "]]></display-name></channel></tv>"
        val file = File(tmp.root, "cdata.xml").also { it.writeText(xml) }
        assertThrows(InputLimitException::class.java) { importer().importFile(epgA, srcId, file, settings, now) }
    }

    @Test fun hostileGuideDownloadFailsPermanentlyWithoutRetrying() {
        val server = MockWebServer().also { it.start() }
        try {
            val big = "x".repeat(5 * 1024 * 1024)
            server.enqueue(MockResponse.Builder().body("<tv><channel id=\"cat.one\"><display-name>$big</display-name></channel></tv>").build())
            val src = db.addSource()
            M3uImporter(db).import(src, "#EXTM3U\n#EXTINF:-1 tvg-id=\"cat.one\",Cat One\nhttp://s/1\n".byteInputStream())
            val epgId = EpgSourceManager(db, SourceSecrets(FakeCipher(), Redactor())).addUserUrl(src.id, server.url("/guide.xml").toString())
            val e = assertThrows(EpgImportException::class.java) { importer().import(epgId, settings, force = true, nowMs = now) }
            assertThat(e.retryable).isFalse()
            assertThat(e).hasMessageThat().isEqualTo("Guide file is malformed")
            assertThat(File(tmp.root, "epg-$epgId.tmp").exists()).isFalse()
            assertThat(db.epgDao().getSource(epgId)!!.lastError).isEqualTo("Guide file is malformed")
        } finally {
            server.close()
        }
    }
}
