package rocks.urzahil.iptv.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.data.net.DownloadResult
import rocks.urzahil.iptv.data.net.Downloader

class DownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val downloader = Downloader(Redactor())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.close() }

    private fun url() = server.url("/list.m3u?token=sekret123").toString()

    private fun download(dest: File, max: Long): DownloadResult =
        downloader.download(client, url(), emptyMap(), null, null, dest, maxBytes = max)

    @Test fun downloadWithinTheLimitIsStored() {
        server.enqueue(MockResponse.Builder().body("a".repeat(900)).addHeader("ETag", "v1").build())
        val dest = File(tmp.root, "p.m3u")
        val r = download(dest, 1000) as DownloadResult.Downloaded
        assertThat(r.etag).isEqualTo("v1")
        assertThat(dest.length()).isEqualTo(900)
    }

    @Test fun declaredOversizeIsRefusedWithoutWritingAnything() {
        server.enqueue(MockResponse.Builder().body("a".repeat(2000)).build())
        val dest = File(tmp.root, "p.m3u")
        val r = download(dest, 1000) as DownloadResult.Failed
        assertThat(r.message).isEqualTo("File is too large")
        assertThat(r.retryable).isFalse()
        assertThat(dest.exists()).isFalse()
        assertThat(File(tmp.root, "p.m3u.part").exists()).isFalse()
    }

    @Test fun undeclaredOversizeIsAbortedWhileStreamingAndTheTempFileRemoved() {
        // Chunked: no Content-Length, so only the running total can catch it.
        server.enqueue(MockResponse.Builder().chunkedBody("a".repeat(5000), 500).build())
        val dest = File(tmp.root, "p.m3u")
        val r = download(dest, 1000) as DownloadResult.Failed
        assertThat(r.message).isEqualTo("File is too large")
        assertThat(r.retryable).isFalse()
        assertThat(dest.exists()).isFalse()
        assertThat(File(tmp.root, "p.m3u.part").exists()).isFalse()
        assertThat(r.message).doesNotContain("sekret123")
    }

    @Test fun anOversizeDownloadDoesNotReplaceTheOldFile() {
        val dest = File(tmp.root, "p.m3u").also { it.writeText("old") }
        server.enqueue(MockResponse.Builder().chunkedBody("a".repeat(5000), 500).build())
        download(dest, 1000)
        assertThat(dest.readText()).isEqualTo("old")
    }

    @Test fun errorStatusesCarryNoUrl() {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        val a = download(File(tmp.root, "a"), 1000) as DownloadResult.Failed
        val b = download(File(tmp.root, "b"), 1000) as DownloadResult.Failed
        assertThat(a.message).isEqualTo("HTTP 503")
        assertThat(a.retryable).isTrue()
        assertThat(b.message).isEqualTo("HTTP 404")
        assertThat(b.retryable).isFalse()
    }

    @Test fun defaultLimitsMatchTheDocumentedCaps() {
        assertThat(Downloader.PLAYLIST_MAX_BYTES).isEqualTo(512L * 1024 * 1024)
        assertThat(Downloader.EPG_MAX_BYTES).isEqualTo(1024L * 1024 * 1024)
    }
}
