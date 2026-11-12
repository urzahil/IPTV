package rocks.urzahil.iptv.sync

import org.robolectric.RobolectricTestRunner
import com.google.common.truth.Truth.assertThat
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.security.XtreamCredentials
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class XtreamImporterTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private var authBody = """{"user_info":{"auth":1,"status":"Active","exp_date":"1900000000","max_connections":"2"},
        "server_info":{"timezone":"Europe/Berlin"}}"""

    @Before fun setUp() {
        db = newDb()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.queryParameter("action")) {
                    null -> authBody
                    "get_live_categories" -> """[{"category_id":"5","category_name":"News"},{"category_id":"2","category_name":"Sport"},{"category_id":"7","category_name":"Empty"}]"""
                    "get_live_streams" -> """[
                        {"num":1,"name":"One","stream_type":"live","stream_id":"11","epg_channel_id":"one.tv","category_id":"5","tv_archive":"1","tv_archive_duration":"3"},
                        {"num":2,"name":"Two","stream_type":"live","stream_id":12,"category_ids":[2,5],"tv_archive":0},
                        {"num":3,"name":"Three","stream_type":"live","stream_id":13,"category_id":null},
                        {"num":4,"name":"Film","stream_type":"movie","stream_id":14,"category_id":"5"},
                        {"num":5,"name":"Radio","stream_type":"radio_streams","stream_id":15,"category_id":"99"}]"""
                    else -> "[]"
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun creds() = XtreamCredentials(server.url("/").toString().trimEnd('/'), "user", "p&ss")

    @Test fun importsCategoriesInProviderOrderWithUncategorizedLast() {
        val src = db.addSource(SourceType.XTREAM)
        val r = XtreamImporter(db, OkHttpClient()).import(src, creds(), "UA")
        assertThat(r.channels).isEqualTo(4) // movie skipped

        val cats = db.categoryDao().getAllBlocking(src.id)
        assertThat(cats.map { it.name }).containsExactly("News", "Sport", "Empty", "Uncategorized").inOrder()
        assertThat(cats.associate { it.name to it.channelCount })
            .containsExactly("News", 2, "Sport", 1, "Empty", 0, "Uncategorized", 2)

        val s = db.sourceDao().getBlocking(src.id)!!
        assertThat(s.maxConnections).isEqualTo(2)
        assertThat(s.serverTz).isEqualTo("Europe/Berlin")
        assertThat(s.status).isEqualTo("OK")

        val one = db.channelDao().matchRows(src.id).first { it.name == "One" }
        assertThat(one.catchupDays).isEqualTo(3)
        assertThat(one.tvgId).isEqualTo("one.tv")
        // Credentials are never stored per channel.
        assertThat(runBlocking { db.channelDao().get(one.id)!!.streamUrl }).isNull()
    }

    @Test fun rejectedLoginThrows() {
        authBody = """{"user_info":{"auth":0}}"""
        val src = db.addSource(SourceType.XTREAM)
        val e = runCatching { XtreamImporter(db, OkHttpClient()).import(src, creds(), "UA") }.exceptionOrNull()
        assertThat(e).isInstanceOf(rocks.urzahil.iptv.core.xtream.XtreamException::class.java)
        assertThat(db.channelDao().count(src.id)).isEqualTo(0)
    }

    private fun <T> runBlocking(b: suspend () -> T): T = kotlinx.coroutines.runBlocking { b() }
}
