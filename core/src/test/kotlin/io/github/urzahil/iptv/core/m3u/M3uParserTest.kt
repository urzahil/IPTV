package io.github.urzahil.iptv.core.m3u

import com.google.common.truth.Truth.assertThat
import io.github.urzahil.iptv.core.model.CatchupType
import io.github.urzahil.iptv.core.model.ParsedChannel
import org.junit.Assert.assertThrows
import org.junit.Test

class M3uParserTest {
    private class Out(val channels: MutableList<ParsedChannel> = ArrayList(), var header: M3uHeader? = null, var stats: M3uStats? = null)

    private fun parse(text: String): Out = parse(text.toByteArray())

    private fun parse(bytes: ByteArray): Out {
        val o = Out()
        o.stats = M3uParser().parse(bytes.inputStream(), { o.header = it }) { o.channels.add(it) }
        return o
    }

    @Test fun bom_and_crlf() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "#EXTM3U\r\n#EXTINF:-1 tvg-id=\"a\",One\r\nhttp://h/1.ts\r\n#EXTINF:-1,Two\r\nhttp://h/2.ts\r\n".toByteArray()
        val o = parse(bytes)
        assertThat(o.channels.map { it.name }).containsExactly("One", "Two").inOrder()
        assertThat(o.channels[0].tvgId).isEqualTo("a")
    }

    @Test fun no_extm3u_header() {
        val o = parse("#EXTINF:-1,One\nhttp://h/1.ts\n")
        assertThat(o.channels).hasSize(1)
        assertThat(o.header!!.epgUrls).isEmpty()
    }

    @Test fun header_epg_urls() {
        val o = parse("#EXTM3U url-tvg=\"http://a/x.xml, http://b/y.xml.gz\" x-tvg-url=\"http://a/x.xml http://c/z.xml\"\n#EXTINF:-1,T\nhttp://h/1.ts\n")
        assertThat(o.header!!.epgUrls).containsExactly("http://a/x.xml", "http://b/y.xml.gz", "http://c/z.xml").inOrder()
    }

    @Test fun header_catchup_inherited_and_overridden() {
        val o = parse(
            """
            #EXTM3U catchup="append" catchup-source="?utc={utc}&lutc={lutc}" catchup-days="5" catchup-correction="1" tvg-shift="2"
            #EXTINF:-1,Inherits
            http://h/1.m3u8
            #EXTINF:-1 catchup="shift" catchup-days="2" catchup-correction="0" tvg-shift="-1.5",Overrides
            http://h/2.m3u8
            """.trimIndent(),
        )
        val a = o.channels[0]
        assertThat(a.catchup.type).isEqualTo(CatchupType.APPEND)
        assertThat(a.catchup.source).isEqualTo("?utc={utc}&lutc={lutc}")
        assertThat(a.catchup.days).isEqualTo(5)
        assertThat(a.catchup.correctionSec).isEqualTo(3600)
        assertThat(a.shiftMin).isEqualTo(120)
        val b = o.channels[1]
        assertThat(b.catchup.type).isEqualTo(CatchupType.SHIFT)
        assertThat(b.catchup.days).isEqualTo(2)
        assertThat(b.catchup.correctionSec).isEqualTo(0)
        assertThat(b.shiftMin).isEqualTo(-90)
    }

    @Test fun groups_split_deduped() {
        val o = parse("#EXTINF:-1 group-title=\"A;B; ;A\",X\nhttp://h/1.ts\n")
        assertThat(o.channels[0].groups).containsExactly("A", "B").inOrder()
    }

    @Test fun extgrp_only_when_group_title_absent() {
        val o = parse(
            "#EXTINF:-1,One\n#EXTGRP:News;Sport\nhttp://h/1.ts\n" +
                "#EXTINF:-1 group-title=\"Kids\",Two\n#EXTGRP:Ignored\nhttp://h/2.ts\n" +
                "#EXTINF:-1,Three\nhttp://h/3.ts\n",
        )
        assertThat(o.channels[0].groups).containsExactly("News", "Sport").inOrder()
        assertThat(o.channels[1].groups).containsExactly("Kids")
        assertThat(o.channels[2].groups).containsExactly("Uncategorized")
    }

    @Test fun extgrp_does_not_leak_to_next_entry() {
        val o = parse("#EXTINF:-1,One\n#EXTGRP:G\nhttp://h/1.ts\n#EXTINF:-1,Two\nhttp://h/2.ts\n")
        assertThat(o.channels[1].groups).containsExactly("Uncategorized")
    }

    @Test fun vlcopt_exthttp_kodiprop() {
        val o = parse(
            """
            #EXTINF:-1,A
            #EXTVLCOPT:http-user-agent=VlcUA
            #EXTVLCOPT:http-referrer=http://ref/
            #EXTVLCOPT:http-origin=http://orig
            #EXTVLCOPT:network-caching=1000
            http://h/a.ts
            #EXTINF:-1,B
            #EXTHTTP:{"User-Agent":"JsonUA","Cookie":"a=b","referer":"http://jr/"}
            http://h/b.ts
            #EXTINF:-1,C
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=KodiUA&Referer=http%3A%2F%2Fk%2F&X-Foo=a%20b
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            http://h/c.mpd
            #EXTINF:-1,D
            http://h/d.ts
            """.trimIndent(),
        )
        val (a, b, c, d) = o.channels
        assertThat(a.headers.userAgent).isEqualTo("VlcUA")
        assertThat(a.headers.referrer).isEqualTo("http://ref/")
        assertThat(a.headers.extra).containsExactly("Origin", "http://orig")
        assertThat(b.headers.userAgent).isEqualTo("JsonUA")
        assertThat(b.headers.referrer).isEqualTo("http://jr/")
        assertThat(b.headers.extra).containsExactly("Cookie", "a=b")
        assertThat(c.headers.userAgent).isEqualTo("KodiUA")
        assertThat(c.headers.referrer).isEqualTo("http://k/")
        assertThat(c.headers.extra).containsExactly("X-Foo", "a b")
        assertThat(c.drm).isTrue()
        assertThat(c.mimeHint).isEqualTo("dash")
        assertThat(c.kodiProps).containsKey("inputstream.adaptive.license_type")
        assertThat(d.headers.isEmpty).isTrue()
        assertThat(d.drm).isFalse()
    }

    @Test fun pipe_suffix_with_encoding_and_precedence() {
        val o = parse(
            "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=Vlc\n#EXTVLCOPT:http-referrer=http://vlc/\n" +
                "#EXTHTTP:{\"User-Agent\":\"Json\",\"Cookie\":\"c=1\"}\n" +
                "http://h/a.m3u8?t=1|User-Agent=My%20UA%2F1.0&Referer=http%3A%2F%2Fpipe%2F&Origin=http%3A%2F%2Fo\n",
        )
        val c = o.channels[0]
        assertThat(c.url).isEqualTo("http://h/a.m3u8?t=1")
        assertThat(c.headers.userAgent).isEqualTo("My UA/1.0")
        assertThat(c.headers.referrer).isEqualTo("http://pipe/")
        assertThat(c.headers.extra).containsExactly("Cookie", "c=1", "Origin", "http://o")
    }

    @Test fun pipe_without_equals_is_not_a_suffix() {
        val o = parse("#EXTINF:-1,A\nhttp://h/a|b\n")
        assertThat(o.channels[0].url).isEqualTo("http://h/a|b")
    }

    @Test fun entries_without_url_dropped() {
        val o = parse("#EXTINF:-1,No url\n#EXTINF:-1,Has url\nhttp://h/1.ts\n#EXTINF:-1,Trailing\n")
        assertThat(o.channels.map { it.name }).containsExactly("Has url")
        assertThat(o.stats!!.dropped).isEqualTo(2)
    }

    @Test fun bare_urls() {
        val o = parse("http://h/path/chan1.m3u8?x=1\nhttp://h/chan2.ts\n")
        assertThat(o.channels.map { it.name }).containsExactly("chan1.m3u8", "chan2.ts").inOrder()
        assertThat(o.channels[0].groups).containsExactly("Uncategorized")
    }

    @Test fun name_fallbacks() {
        val o = parse("#EXTINF:-1 tvg-name=\"TvgName\",\nhttp://h/1.ts\n#EXTINF:-1,\nhttp://h/x/last.ts\n")
        assertThat(o.channels[0].name).isEqualTo("TvgName")
        assertThat(o.channels[1].name).isEqualTo("last.ts")
    }

    @Test fun hls_media_playlist_is_an_error() {
        val e = assertThrows(M3uParseException::class.java) {
            parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg1.ts\n")
        }
        assertThat(e.message).contains("stream, not a playlist")
    }

    @Test fun vod_skipping() {
        val o = parse(
            """
            #EXTINF:-1,Live
            http://h/live/u/p/1.ts
            #EXTINF:-1,Movie
            http://h/movie/u/p/2.mp4
            #EXTINF:-1,Series
            http://h/series/u/p/3.mkv
            #EXTINF:-1,File
            http://h/files/x.mkv?token=1
            #EXTINF:5400,Timed
            http://h/u/p/4
            #EXTINF:-1,Live2
            http://h/u/p/5
            #EXTINF:-1,NoExt
            http://h/stream/abc
            #EXTINF:-1,Dash
            http://h/a.mpd
            """.trimIndent(),
        )
        assertThat(o.channels.map { it.name }).containsExactly("Live", "Live2", "NoExt", "Dash").inOrder()
        assertThat(o.stats!!.skippedVod).isEqualTo(4)
    }

    @Test fun tvg_shift_and_chno_and_logo_radio() {
        val o = parse("#EXTINF:-1 tvg-shift=\"-1.5\" tvg-chno=\"7\" logo=\"http://l/x.png\" radio=\"true\",A\nhttp://h/1.ts\n" +
            "#EXTINF:-1 channel-number=\"9\" tvg-logo=\"http://l/y.png\",B\nhttp://h/2.ts\n#EXTINF:-1 tvg-num=\"3\",C\nhttp://h/3.ts\n")
        val (a, b, c) = o.channels
        assertThat(a.shiftMin).isEqualTo(-90)
        assertThat(a.chno).isEqualTo(7)
        assertThat(a.logo).isEqualTo("http://l/x.png")
        assertThat(a.isRadio).isTrue()
        assertThat(b.chno).isEqualTo(9)
        assertThat(b.logo).isEqualTo("http://l/y.png")
        assertThat(b.isRadio).isFalse()
        assertThat(c.chno).isEqualTo(3)
    }

    @Test fun timeshift_and_tvg_rec_as_days() {
        val o = parse("#EXTINF:-1 catchup=\"shift\" timeshift=\"3\",A\nhttp://h/1.ts\n" +
            "#EXTINF:-1 catchup=\"shift\" tvg-rec=\"4\",B\nhttp://h/2.ts\n" +
            "#EXTINF:-1 catchup=\"shift\" catchup-days=\"99\",C\nhttp://h/3.ts\n" +
            "#EXTINF:-1 catchup=\"shift\",D\nhttp://h/4.ts\n")
        assertThat(o.channels.map { it.catchup.days }).containsExactly(3, 4, 30, 7).inOrder()
    }

    @Test fun catchup_type_resolution() {
        val o = parse(
            """
            #EXTINF:-1 catchup-source="http://a/{utc}",SourceOnly
            http://h/1.ts
            #EXTINF:-1 catchup-days="3",XtreamUrl
            http://h/live/u/p/5.ts
            #EXTINF:-1 catchup-days="3",PlainUrl
            http://h/ch.ts
            #EXTINF:-1 catchup="default",DefaultXtream
            http://h/live/u/p/6.ts
            #EXTINF:-1 catchup="default",DefaultNoSource
            http://h/ch2.ts
            #EXTINF:-1 catchup="fs",Fs
            http://h/ch3/mpegts
            #EXTINF:-1 catchup="xc",Xc
            http://h/live/u/p/7.ts
            #EXTINF:-1 catchup="append",Append
            http://h/ch4.ts
            #EXTINF:-1,Nothing
            http://h/ch5.ts
            """.trimIndent(),
        )
        assertThat(o.channels.map { it.catchup.type }).containsExactly(
            CatchupType.DEFAULT, CatchupType.XTREAM, CatchupType.NONE, CatchupType.XTREAM, CatchupType.SHIFT,
            CatchupType.FLUSSONIC, CatchupType.XTREAM, CatchupType.APPEND, CatchupType.NONE,
        ).inOrder()
        assertThat(o.channels[0].catchup.days).isEqualTo(7)
        assertThat(o.channels[8].catchup.days).isEqualTo(0)
    }

    @Test fun stable_duplicate_keys() {
        val text = "#EXTINF:-1 tvg-id=\"a\",Dup\nhttp://h/1.ts?tok=1\n#EXTINF:-1 tvg-id=\"a\",Dup\nhttp://h/2.ts\n#EXTINF:-1,Dup\nhttp://h/3.ts\n"
        val k1 = parse(text).channels.map { it.key }
        assertThat(k1).containsExactly("a|Dup|#0", "a|Dup|#1", "|Dup|#0").inOrder()
        val k2 = parse(text.replace("tok=1", "tok=2")).channels.map { it.key }
        assertThat(k2).isEqualTo(k1)
    }

    @Test fun category_order_is_first_appearance() {
        val o = parse("#EXTINF:-1 group-title=\"B\",1\nhttp://h/1\n#EXTINF:-1,2\nhttp://h/2\n#EXTINF:-1 group-title=\"A;B\",3\nhttp://h/3\n")
        val order = LinkedHashSet<String>()
        o.channels.forEach { order.addAll(it.groups) }
        assertThat(order).containsExactly("B", "Uncategorized", "A").inOrder()
    }

    @Test fun large_playlist_streams_quickly() {
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until 50_000) {
            sb.append("#EXTINF:-1 tvg-id=\"id$i\" tvg-name=\"Channel $i\" tvg-logo=\"http://l/$i.png\" group-title=\"G${i % 100}\",Channel $i\n")
            sb.append("http://h:8080/live/u/p/$i.ts\n")
        }
        val bytes = sb.toString().toByteArray()
        var count = 0
        val t0 = System.nanoTime()
        val stats = M3uParser().parse(bytes.inputStream()) { count++ }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertThat(count).isEqualTo(50_000)
        assertThat(stats.channels).isEqualTo(50_000)
        assertThat(ms).isLessThan(5_000)
    }
}
