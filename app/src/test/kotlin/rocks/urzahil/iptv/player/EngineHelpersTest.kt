package rocks.urzahil.iptv.player

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.core.xtream.XtreamAccountInfo
import rocks.urzahil.iptv.player.engine.Media3Errors
import rocks.urzahil.iptv.player.engine.StreamKind
import rocks.urzahil.iptv.player.engine.StreamTypeProbe
import rocks.urzahil.iptv.player.model.FailureKind
import rocks.urzahil.iptv.player.model.PlayMode
import rocks.urzahil.iptv.player.model.PlayerFailure
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.StreamRequest

class EngineHelpersTest {
    @Test fun media3ErrorCodesMapToFailureKinds() {
        fun k(code: Int, http: Int? = null) = Media3Errors.classify(code, http).kind
        assertThat(k(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)).isEqualTo(FailureKind.DECODER)
        assertThat(k(PlaybackException.ERROR_CODE_DECODING_FAILED)).isEqualTo(FailureKind.DECODER)
        assertThat(k(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)).isEqualTo(FailureKind.FORMAT_UNSUPPORTED)
        assertThat(k(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)).isEqualTo(FailureKind.CONTAINER_UNSUPPORTED)
        assertThat(k(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)).isEqualTo(FailureKind.CONTAINER_MALFORMED)
        assertThat(k(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)).isEqualTo(FailureKind.AUDIO_SINK)
        assertThat(k(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)).isEqualTo(FailureKind.BEHIND_LIVE_WINDOW)
        assertThat(k(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)).isEqualTo(FailureKind.NETWORK)
        assertThat(k(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404)).isEqualTo(FailureKind.HTTP_CLIENT_ERROR)
        assertThat(k(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503)).isEqualTo(FailureKind.HTTP_SERVER_ERROR)
        assertThat(k(PlaybackException.ERROR_CODE_UNSPECIFIED)).isEqualTo(FailureKind.OTHER)
    }

    @Test fun onlyExtensionlessHttpUrlsAreProbed() {
        assertThat(StreamTypeProbe.shouldProbe("http://h/live/u/p/1")).isTrue()
        assertThat(StreamTypeProbe.shouldProbe("http://h/stream?id=3")).isTrue()
        assertThat(StreamTypeProbe.shouldProbe("http://h/live/u/p/1.ts")).isFalse()
        assertThat(StreamTypeProbe.shouldProbe("https://h/x/index.m3u8?token=a.b")).isFalse()
        assertThat(StreamTypeProbe.shouldProbe("rtsp://h/x")).isFalse()
        assertThat(StreamTypeProbe.shouldProbe("udp://@239.1.1.1:1234")).isFalse()
    }

    @Test fun probeClassifiesBodies() {
        assertThat(StreamTypeProbe.classify("application/vnd.apple.mpegurl", ByteArray(0))).isEqualTo(StreamKind.HLS)
        assertThat(StreamTypeProbe.classify("text/plain", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray())).isEqualTo(StreamKind.HLS)
        assertThat(StreamTypeProbe.classify(null, "<?xml version=\"1.0\"?><MPD xmlns=...".toByteArray())).isEqualTo(StreamKind.DASH)
        val ts = ByteArray(400).also { it[0] = 0x47; it[188] = 0x47 }
        assertThat(StreamTypeProbe.classify("application/octet-stream", ts)).isEqualTo(StreamKind.TRANSPORT_STREAM)
        assertThat(StreamTypeProbe.classify("video/mp4", ByteArray(400))).isEqualTo(StreamKind.PROGRESSIVE)
        assertThat(StreamTypeProbe.mimeOf(StreamKind.TRANSPORT_STREAM)).isEqualTo("video/mp2t")
    }

    @Test fun xtreamAccountDiagnosis() {
        fun info(auth: Boolean = true, status: String? = "Active", exp: Long? = null, max: Int? = 1, act: Int? = 0) =
            XtreamAccountInfo(auth, status, "u", exp, max, act, emptyList())
        assertThat(AccountDiagnosis.explain(info(auth = false), 1000)).contains("Login refused")
        assertThat(AccountDiagnosis.explain(info(status = "Expired"), 1000)).contains("expired")
        assertThat(AccountDiagnosis.explain(info(exp = 500), 1000)).contains("expired")
        assertThat(AccountDiagnosis.explain(info(max = 2, act = 2), 1000)).contains("Maximum connections")
        assertThat(AccountDiagnosis.explain(info(exp = 5000, max = 2, act = 1), 1000)).isNull()
    }

    @Test fun errorTextsAreUserFacing() {
        val catchup = ErrorMessages.describe(PlayerFailure(FailureKind.HTTP_CLIENT_ERROR, httpStatus = 404), PlayMode.CATCHUP, EngineKind.MEDIA3)
        assertThat(catchup.kind).isEqualTo(ErrorKind.CATCHUP_UNAVAILABLE)
        val live404 = ErrorMessages.describe(PlayerFailure(FailureKind.HTTP_CLIENT_ERROR, httpStatus = 404), PlayMode.LIVE, EngineKind.MEDIA3)
        assertThat(live404.kind).isEqualTo(ErrorKind.NOT_FOUND)
        val account = ErrorMessages.describe(PlayerFailure(FailureKind.HTTP_CLIENT_ERROR, httpStatus = 403), PlayMode.LIVE, EngineKind.VLC, "The account has expired.")
        assertThat(account.title).isEqualTo("The account has expired.")
        assertThat(ErrorMessages.describe(PlayerFailure(FailureKind.DECODER), PlayMode.LIVE, EngineKind.VLC).title).contains("VLC")
    }

    @Test fun streamRequestNeverPrintsTheUrl() {
        val r = StreamRequest("http://host/live/secretuser/secretpass/1.ts", null, null, emptyMap(), null, true, "Chan", null)
        assertThat(r.toString()).doesNotContain("secret")
        assertThat(r.scheme).isEqualTo("http")
    }
}
