package rocks.urzahil.iptv.core.xtream

import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamParserTest {
    @Test fun auth_ok_with_sloppy_types() {
        val a = XtreamParser.parseAuth(
            """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1800000000","max_connections":"2","active_cons":0,"allowed_output_formats":["m3u8","ts"]},
               "server_info":{"url":"h","port":"80","timezone":"Europe/Berlin","timestamp_now":1794398400,"time_now":"2026-11-11 13:00:00"}}""",
        )
        assertThat(a.account.isActive).isTrue()
        assertThat(a.account.expiresAtSec).isEqualTo(1800000000L)
        assertThat(a.account.maxConnections).isEqualTo(2)
        assertThat(a.account.activeConnections).isEqualTo(0)
        assertThat(a.account.allowedFormats).containsExactly("m3u8", "ts").inOrder()
        assertThat(a.server.timezone).isEqualTo("Europe/Berlin")
        assertThat(a.server.timestampNow).isEqualTo(1794398400L)
        for (v in listOf("\"1\"", "true")) {
            assertThat(XtreamParser.parseAuth("""{"user_info":{"auth":$v,"status":"Active"}}""").account.auth).isTrue()
        }
    }

    @Test fun auth_zero_and_missing_fields() {
        val a = XtreamParser.parseAuth("""{"user_info":{"auth":0},"server_info":[]}""")
        assertThat(a.account.auth).isFalse()
        assertThat(a.account.isActive).isFalse()
        assertThat(a.server.timezone).isNull()
        assertThat(XtreamParser.parseAuth("""{"user_info":{"auth":1,"exp_date":null,"max_connections":""}}""").account.expiresAtSec).isNull()
    }

    @Test fun auth_expired_status() {
        val a = XtreamParser.parseAuth("""{"user_info":{"auth":1,"status":"Expired","exp_date":"1"}}""")
        assertThat(a.account.auth).isTrue()
        assertThat(a.account.status).isEqualTo("Expired")
        assertThat(a.account.isActive).isFalse()
    }

    @Test fun auth_errors() {
        for (body in listOf("", "  ", "[]", "<html>nope</html>", "{}", "{\"user_info\":[]}", "\"str\"")) {
            assertThrows(body, XtreamException::class.java) { XtreamParser.parseAuth(body) }
        }
    }

    @Test fun categories_in_order() {
        val c = XtreamParser.parseCategories("""[{"category_id":"5","category_name":"News","parent_id":0},{"category_id":2,"category_name":"Sport"},{"category_name":"bad"},{"category_id":"9","category_name":""}]""")
        assertThat(c.map { it.id }).containsExactly("5", "2", "9").inOrder()
        assertThat(c.map { it.name }).containsExactly("News", "Sport", "9").inOrder()
        assertThat(XtreamParser.parseCategories("[]")).isEmpty()
    }

    @Test fun live_streams_streamed() {
        val json = """[
            {"num":1,"name":"A","stream_type":"live","stream_id":"101","stream_icon":"http://i/a.png","epg_channel_id":"a.id","category_id":"5","category_ids":[5,6],"tv_archive":"1","tv_archive_duration":"3","is_adult":"0","direct_source":""},
            {"num":"2","name":"B","stream_type":"created_live","stream_id":102,"stream_icon":"","epg_channel_id":"","category_id":null,"category_ids":[],"tv_archive":0,"tv_archive_duration":null},
            {"num":3,"name":"Movie","stream_type":"movie","stream_id":103},
            {"num":4,"name":"R","stream_type":"radio_streams","stream_id":104,"category_id":"7","tv_archive":true,"tv_archive_duration":0},
            {"name":"NoId"},
            "garbage",
            {"num":5,"name":"C","stream_id":105,"category_id":8,"unknown":{"x":1}}
        ]"""
        val out = ArrayList<XLiveStream>()
        val n = XtreamParser.streamLiveStreams(json.byteInputStream()) { out.add(it) }
        assertThat(n).isEqualTo(4)
        val (a, b, r, c) = out
        assertThat(a.streamId).isEqualTo(101L)
        assertThat(a.tvArchive).isTrue()
        assertThat(a.tvArchiveDuration).isEqualTo(3)
        assertThat(a.epgChannelId).isEqualTo("a.id")
        assertThat(a.groups).containsExactly("5", "6").inOrder()
        assertThat(a.icon).isEqualTo("http://i/a.png")
        assertThat(a.directSource).isNull()
        assertThat(b.epgChannelId).isNull()
        assertThat(b.icon).isNull()
        assertThat(b.groups).isEmpty()
        assertThat(b.num).isEqualTo(2)
        assertThat(b.tvArchive).isFalse()
        assertThat(r.groups).containsExactly("7")
        assertThat(r.tvArchive).isTrue()
        assertThat(r.tvArchiveDuration).isEqualTo(0)
        assertThat(c.groups).containsExactly("8")
        assertThat(c.streamType).isNull()
    }

    @Test fun live_streams_empty_and_bad() {
        assertThat(XtreamParser.parseLiveStreams("[]")).isEmpty()
        assertThrows(XtreamException::class.java) { XtreamParser.parseLiveStreams("{\"user_info\":{}}") }
        assertThrows(XtreamException::class.java) { XtreamParser.parseLiveStreams("<html>") }
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun short_epg_base64_and_plain_fallback_with_timestamps() {
        val body = """{"epg_listings":[
            {"id":"1","title":"${b64("Morning Show")}","description":"${b64("Fun & games")}","start":"2026-11-11 10:00:00","end":"2026-11-11 11:00:00","start_timestamp":"1794391200","stop_timestamp":"1794394800","has_archive":1,"now_playing":0},
            {"id":"2","title":"Plain title!","description":"","start_timestamp":1794394800,"stop_timestamp":1794398400,"now_playing":"1"}
        ]}"""
        val l = XtreamParser.parseShortEpg(body, ZoneOffset.ofHours(5))
        assertThat(l).hasSize(2)
        assertThat(l[0].title).isEqualTo("Morning Show")
        assertThat(l[0].description).isEqualTo("Fun & games")
        assertThat(l[0].startSec).isEqualTo(1794391200L) // timestamp preferred over local string
        assertThat(l[0].hasArchive).isTrue()
        assertThat(l[1].title).isEqualTo("Plain title!")
        assertThat(l[1].nowPlaying).isTrue()
    }

    @Test fun short_epg_local_string_times_use_server_zone() {
        val body = """{"epg_listings":[{"title":"${b64("X")}","start":"2026-11-11 13:00:00","end":"2026-11-11 14:00:00"}]}"""
        val l = XtreamParser.parseShortEpg(body, ZoneOffset.ofHours(1))
        assertThat(l[0].startSec).isEqualTo(1794398400L)
        assertThat(l[0].endSec).isEqualTo(1794402000L)
    }

    @Test fun short_epg_empty_forms_and_map() {
        assertThat(XtreamParser.parseShortEpg("""{"epg_listings":[]}""", ZoneOffset.UTC)).isEmpty()
        assertThat(XtreamParser.parseShortEpg("""{"epg_listings":{}}""", ZoneOffset.UTC)).isEmpty()
        assertThat(XtreamParser.parseShortEpg("[]", ZoneOffset.UTC)).isEmpty()
        val m = XtreamParser.parseShortEpg("""{"epg_listings":{"a":{"title":"T","start_timestamp":1,"stop_timestamp":2}}}""", ZoneOffset.UTC)
        assertThat(m).hasSize(1)
        // listing without usable times is dropped
        assertThat(XtreamParser.parseShortEpg("""{"epg_listings":[{"title":"T"}]}""", ZoneOffset.UTC)).isEmpty()
    }
}
