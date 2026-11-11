package rocks.urzahil.iptv.data

import com.google.common.truth.Truth.assertThat
import rocks.urzahil.iptv.core.model.StreamHeaders
import rocks.urzahil.iptv.core.xtream.XLiveStream
import rocks.urzahil.iptv.data.repo.M3uUrlInspector
import rocks.urzahil.iptv.data.repo.SearchQuery
import rocks.urzahil.iptv.data.repo.StreamHeadersJson
import rocks.urzahil.iptv.sync.EpgSourceManager
import rocks.urzahil.iptv.sync.XtreamImporter
import org.junit.Test

class PureLogicTest {
    @Test fun ftsQueryStripsOperators() {
        assertThat(SearchQuery.toFts("BBC  One")).isEqualTo("bbc* one*")
        assertThat(SearchQuery.toFts("\"news\" OR (sport)* -x")).isEqualTo("news* or* sport* x*")
        assertThat(SearchQuery.toFts("  ***  ")).isEmpty()
        assertThat(SearchQuery.toFts("Das Erste: HD")).isEqualTo("das* erste* hd*")
    }

    @Test fun detectsXtreamPlaylistUrl() {
        val a = M3uUrlInspector.detectXtream("http://host.tv:8080/get.php?username=u1&password=p%26w&type=m3u_plus")!!
        assertThat(a.base).isEqualTo("http://host.tv:8080")
        assertThat(a.username).isEqualTo("u1")
        assertThat(a.password).isEqualTo("p&w")
        assertThat(M3uUrlInspector.detectXtream("http://host.tv/list.m3u")).isNull()
        assertThat(M3uUrlInspector.detectXtream("http://host.tv/get.php?username=u")).isNull()
    }

    @Test fun headersJsonRoundTrip() {
        val h = StreamHeaders(userAgent = "UA", referrer = "http://r/", extra = mapOf("Cookie" to "a=b"))
        val back = StreamHeadersJson.decode(StreamHeadersJson.encode(h))
        assertThat(back).isEqualTo(h)
        assertThat(StreamHeadersJson.encode(StreamHeaders.EMPTY)).isNull()
        assertThat(StreamHeadersJson.decode("garbage")).isEqualTo(StreamHeaders.EMPTY)
    }

    @Test fun xtreamCategoryKeysFallBackToUncategorized() {
        fun s(cat: String?, cats: List<String> = emptyList()) = XLiveStream(
            1, "n", "live", 1, null, null, cat, cats, false, 0, false, null,
        )
        val known = setOf("1", "2")
        assertThat(XtreamImporter.categoryKeys(s("1"), known)).containsExactly("1")
        assertThat(XtreamImporter.categoryKeys(s("9"), known)).containsExactly(XtreamImporter.UNCATEGORIZED_KEY)
        assertThat(XtreamImporter.categoryKeys(s(null), known)).containsExactly(XtreamImporter.UNCATEGORIZED_KEY)
        assertThat(XtreamImporter.categoryKeys(s("1", listOf("2", "1", "9", "2")), known)).containsExactly("2", "1").inOrder()
    }

    @Test fun epgDisplayDropsQueryAndUserInfo() {
        assertThat(EpgSourceManager.display("http://u:p@host.tv/epg/guide.xml.gz?token=abc")).isEqualTo("host.tv/epg/guide.xml.gz")
        assertThat(EpgSourceManager.hash("http://a")).hasLength(64)
    }
}
