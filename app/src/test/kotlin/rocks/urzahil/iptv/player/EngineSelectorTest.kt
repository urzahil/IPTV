package rocks.urzahil.iptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EnginePreference
import rocks.urzahil.iptv.player.model.EngineSelector
import rocks.urzahil.iptv.player.model.FailureKind
import rocks.urzahil.iptv.player.model.FallbackAction
import rocks.urzahil.iptv.player.model.FallbackPolicy
import rocks.urzahil.iptv.player.model.PlayerFailure
import rocks.urzahil.iptv.player.model.TrackGroupInfo
import rocks.urzahil.iptv.player.model.TrackType

class EngineSelectorTest {
    private fun choose(
        scheme: String = "http",
        extra: Boolean = false,
        global: EnginePreference = EnginePreference.AUTO,
        channel: EnginePreference = EnginePreference.AUTO,
        remembered: EngineKind? = null,
        age: Long = 0,
    ) = EngineSelector.choose(scheme, extra, global, channel, remembered, age)

    @Test fun defaultsToMedia3() {
        assertThat(choose().kind).isEqualTo(EngineKind.MEDIA3)
        assertThat(choose("rtsp").kind).isEqualTo(EngineKind.MEDIA3)
        assertThat(choose("udp").kind).isEqualTo(EngineKind.MEDIA3)
        assertThat(choose("rtmp").kind).isEqualTo(EngineKind.MEDIA3)
    }

    @Test fun unsupportedSchemesGoStraightToVlc() {
        for (s in listOf("rtp", "mms", "mmsh", "SRT")) assertThat(choose(s).kind).isEqualTo(EngineKind.VLC)
    }

    @Test fun channelOverridesGlobalOverridesAuto() {
        assertThat(choose(global = EnginePreference.VLC).kind).isEqualTo(EngineKind.VLC)
        assertThat(choose(global = EnginePreference.VLC, channel = EnginePreference.MEDIA3).kind).isEqualTo(EngineKind.MEDIA3)
        assertThat(choose(global = EnginePreference.MEDIA3, channel = EnginePreference.VLC).kind).isEqualTo(EngineKind.VLC)
        assertThat(choose("rtp", channel = EnginePreference.MEDIA3).forced).isTrue()
    }

    @Test fun rememberedFallbackAppliesInAutoUntilExpiry() {
        assertThat(choose(remembered = EngineKind.VLC, age = 1000).kind).isEqualTo(EngineKind.VLC)
        assertThat(choose(remembered = EngineKind.VLC, age = EngineSelector.AUTO_ENGINE_TTL_MS).kind).isEqualTo(EngineKind.MEDIA3)
        assertThat(choose(remembered = EngineKind.VLC, channel = EnginePreference.MEDIA3).kind).isEqualTo(EngineKind.MEDIA3)
    }

    @Test fun extraHeadersKeepAutoOnMedia3() {
        assertThat(choose(extra = true, remembered = EngineKind.VLC).kind).isEqualTo(EngineKind.MEDIA3)
        assertThat(choose("rtp", extra = true).kind).isEqualTo(EngineKind.VLC)
    }

    private fun fail(k: FailureKind) = PlayerFailure(k)

    @Test fun decoderAndFormatErrorsFallBackOnce() {
        for (k in listOf(FailureKind.DECODER, FailureKind.FORMAT_UNSUPPORTED, FailureKind.CONTAINER_UNSUPPORTED, FailureKind.AUDIO_SINK)) {
            val p = FallbackPolicy(automatic = true)
            assertThat(p.onFailure(EngineKind.MEDIA3, fail(k))).isInstanceOf(FallbackAction.SwitchToVlc::class.java)
            assertThat(p.onFailure(EngineKind.MEDIA3, fail(k))).isEqualTo(FallbackAction.None)
        }
    }

    @Test fun networkErrorsDoNotFallBack() {
        val p = FallbackPolicy(true)
        for (k in listOf(FailureKind.NETWORK, FailureKind.HTTP_CLIENT_ERROR, FailureKind.HTTP_SERVER_ERROR, FailureKind.OTHER)) {
            assertThat(p.onFailure(EngineKind.MEDIA3, fail(k))).isEqualTo(FallbackAction.None)
        }
    }

    @Test fun malformedContainerNeedsTwoInARow() {
        val p = FallbackPolicy(true)
        assertThat(p.onFailure(EngineKind.MEDIA3, fail(FailureKind.CONTAINER_MALFORMED))).isEqualTo(FallbackAction.None)
        assertThat(p.onFailure(EngineKind.MEDIA3, fail(FailureKind.CONTAINER_MALFORMED))).isInstanceOf(FallbackAction.SwitchToVlc::class.java)
        val q = FallbackPolicy(true)
        q.onFailure(EngineKind.MEDIA3, fail(FailureKind.CONTAINER_MALFORMED))
        q.onFailure(EngineKind.MEDIA3, fail(FailureKind.NETWORK))
        assertThat(q.onFailure(EngineKind.MEDIA3, fail(FailureKind.CONTAINER_MALFORMED))).isEqualTo(FallbackAction.None)
    }

    @Test fun forcedEngineAndVlcNeverFallBack() {
        assertThat(FallbackPolicy(false).onFailure(EngineKind.MEDIA3, fail(FailureKind.DECODER))).isEqualTo(FallbackAction.None)
        assertThat(FallbackPolicy(true).onFailure(EngineKind.VLC, fail(FailureKind.DECODER))).isEqualTo(FallbackAction.None)
    }

    @Test fun unsupportedMainGroupsTriggerFallback() {
        val audioOnlyMpeg2 = listOf(
            TrackGroupInfo(TrackType.VIDEO, listOf(false)),
            TrackGroupInfo(TrackType.AUDIO, listOf(true)),
        )
        assertThat(FallbackPolicy(true).onTracksSettled(EngineKind.MEDIA3, audioOnlyMpeg2)).isInstanceOf(FallbackAction.SwitchToVlc::class.java)
        val noAudio = listOf(TrackGroupInfo(TrackType.VIDEO, listOf(true)), TrackGroupInfo(TrackType.AUDIO, listOf(false, false)))
        assertThat(EngineSelector.hasUnsupportedMainTrack(noAudio)).isTrue()
    }

    @Test fun oneSupportedTrackPerTypeIsEnough() {
        val ok = listOf(
            TrackGroupInfo(TrackType.VIDEO, listOf(true)),
            TrackGroupInfo(TrackType.AUDIO, listOf(false)),
            TrackGroupInfo(TrackType.AUDIO, listOf(true)),
            TrackGroupInfo(TrackType.TEXT, listOf(false)),
        )
        assertThat(EngineSelector.hasUnsupportedMainTrack(ok)).isFalse()
        assertThat(EngineSelector.hasUnsupportedMainTrack(emptyList())).isFalse()
        assertThat(EngineSelector.hasUnsupportedMainTrack(listOf(TrackGroupInfo(TrackType.AUDIO, emptyList())))).isFalse()
    }

    @Test fun onlyOneFallbackPerRequestAcrossBothTriggers() {
        val p = FallbackPolicy(true)
        p.onFailure(EngineKind.MEDIA3, fail(FailureKind.DECODER))
        assertThat(p.onTracksSettled(EngineKind.MEDIA3, listOf(TrackGroupInfo(TrackType.VIDEO, listOf(false))))).isEqualTo(FallbackAction.None)
    }
}
