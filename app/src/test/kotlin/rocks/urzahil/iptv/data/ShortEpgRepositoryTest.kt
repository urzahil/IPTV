package rocks.urzahil.iptv.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.ChannelEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.repo.ShortEpgRepository
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.SettingsRepository
import rocks.urzahil.iptv.sync.FakeCipher
import rocks.urzahil.iptv.sync.newDb

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShortEpgRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: ShortEpgRepository
    private var channelId = 0L
    private var clockMs = 1_794_398_400_000L
    private val requests = AtomicInteger()
    private val urls = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** What the fake panel answers; set per test. */
    @Volatile private var respond: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(404).build() }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun listings(vararg titles: String): String = titles.mapIndexed { i, t ->
        val start = 1_794_398_400L + i * 3600
        """{"id":"$i","title":"${b64(t)}","description":"${b64("About $t")}","start_timestamp":"$start","stop_timestamp":"${start + 3600}"}"""
    }.joinToString(",", """{"epg_listings":[""", "]}")

    private fun json(body: String) = MockResponse.Builder().body(body).build()

    @Before fun setUp() {
        db = newDb()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                urls += request.url.toString()
                return respond(request)
            }
        }
        server.start()
        val cipher = FakeCipher()
        runBlocking {
            val sid = db.sourceDao().insert(
                SourceEntity(
                    type = SourceType.XTREAM.name, name = "X", serverUrl = server.url("/").toString().trimEnd('/'),
                    usernameEnc = cipher.encrypt("alice77"), passwordEnc = cipher.encrypt("s3cretPass"),
                ),
            )
            db.channelDao().upsertAll(
                listOf(ChannelEntity(sourceId = sid, providerKey = "11", name = "One", nameNorm = "one", sortIndex = 0, xtreamStreamId = 11, shiftMin = 60)),
            )
            channelId = db.channelDao().matchRows(sid).first().id
        }
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(tmp.root, "s.preferences_pb") }
        repo = ShortEpgRepository(db, SourceSecrets(cipher, Redactor()), OkHttpClient(), SettingsRepository(store), clock = { clockMs })
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun load() = runBlocking { repo.load(channelId) }

    @Test fun happyPathReturnsShiftedProgrammesWithDescriptionsAndCachesThem() {
        respond = { json(listings("Morning", "Noon")) }
        val rows = load()
        assertThat(rows.map { it.title }).containsExactly("Morning", "Noon").inOrder()
        // tvg-shift of +60 minutes is applied; ids are negative (not database rows).
        assertThat(rows[0].startMs).isEqualTo(1_794_398_400_000L + 3_600_000L)
        assertThat(rows.all { it.id < 0 }).isTrue()
        assertThat(repo.description(channelId, rows[0].id)).isEqualTo("About Morning")
        assertThat(urls.single()).contains("action=get_simple_data_table")
        assertThat(urls.single()).contains("stream_id=11")

        val before = requests.get()
        assertThat(load().map { it.title }).containsExactly("Morning", "Noon").inOrder()
        assertThat(requests.get()).isEqualTo(before)
    }

    @Test fun fallsBackToTheShortListWhenThePanelHasNoDataTable() {
        respond = { r -> if (r.url.queryParameter("action") == "get_short_epg") json(listings("Short")) else json("""{"epg_listings":[]}""") }
        assertThat(load().map { it.title }).containsExactly("Short")
        assertThat(urls.map { Regex("action=([a-z_]+)").find(it)!!.groupValues[1] }).containsExactly("get_simple_data_table", "get_short_epg").inOrder()
    }

    @Test fun oversizeResponseIsIgnoredAndRetriedSoonerThanARealAnswer() {
        val big = "x".repeat(2 * 1024 * 1024 + 10)
        respond = { json("""{"epg_listings":[{"title":"$big","start_timestamp":"1794398400","stop_timestamp":"1794402000"}]}""") }
        assertThat(load()).isEmpty()
        val afterFirst = requests.get()
        assertThat(afterFirst).isEqualTo(2) // data table, then short list

        // Within the retry delay nothing is requested again.
        clockMs += 60_000
        assertThat(load()).isEmpty()
        assertThat(requests.get()).isEqualTo(afterFirst)

        // After it a sane response is picked up (a real empty answer would have been cached for 30 minutes).
        clockMs += 2 * 60_000
        respond = { json(listings("Recovered")) }
        assertThat(load().map { it.title }).containsExactly("Recovered")
    }

    @Test fun errorResponseGivesAnEmptyListAndIsRetriedLater() {
        respond = { MockResponse.Builder().code(500).body("server error for alice77 s3cretPass").build() }
        assertThat(load()).isEmpty()
        val afterFirst = requests.get()

        clockMs += 60_000
        assertThat(load()).isEmpty()
        assertThat(requests.get()).isEqualTo(afterFirst)

        clockMs += 2 * 60_000
        respond = { json(listings("Back")) }
        assertThat(load().map { it.title }).containsExactly("Back")
    }

    @Test fun nonJsonAndEmptyBodiesDoNotThrow() {
        respond = { json("<html>maintenance</html>") }
        assertThat(load()).isEmpty()
        clockMs += 3 * 60_000
        respond = { json("") }
        assertThat(load()).isEmpty()
    }

    @Test fun aRealEmptyAnswerIsCachedForTheFullTime() {
        respond = { json("""{"epg_listings":[]}""") }
        assertThat(load()).isEmpty()
        val n = requests.get()
        clockMs += 10 * 60_000
        assertThat(load()).isEmpty()
        assertThat(requests.get()).isEqualTo(n)
    }

    @Test fun nothingIsRequestedForAChannelThatIsNotXtream() {
        runBlocking {
            val sid = db.sourceDao().insert(SourceEntity(type = SourceType.M3U_URL.name, name = "M"))
            db.channelDao().upsertAll(listOf(ChannelEntity(sourceId = sid, providerKey = "k", name = "M", nameNorm = "m", sortIndex = 0, streamUrl = "http://s/1")))
            val id = db.channelDao().matchRows(sid).first().id
            assertThat(repo.load(id)).isEmpty()
        }
        assertThat(requests.get()).isEqualTo(0)
    }
}
