package rocks.urzahil.iptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.core.catchup.TimeshiftStyle
import rocks.urzahil.iptv.core.catchup.UnsupportedCatchup
import rocks.urzahil.iptv.core.catchup.XtreamTarget
import rocks.urzahil.iptv.core.model.CatchupSpec
import rocks.urzahil.iptv.core.model.CatchupType
import rocks.urzahil.iptv.player.model.CatchupAttempt
import rocks.urzahil.iptv.player.model.CatchupAttemptPlanner
import rocks.urzahil.iptv.player.model.CatchupRequests
import rocks.urzahil.iptv.player.model.ProgrammeList
import rocks.urzahil.iptv.player.model.ProgrammeSlot
import rocks.urzahil.iptv.player.model.RowState
import java.time.ZoneId

class CatchupPlannerTest {
    private val S = 1794340800L
    private val E = 1794344400L
    private val N = 1794398400L
    private val target = XtreamTarget("http://xc.tv:8080", "user", "pass", "123")
    private val api = CatchupSpec(CatchupType.XTREAM_API, null, 7, 0, ZoneId.of("Europe/Berlin"))

    @Test fun pastProgrammeHasOneEndAndBothStylesForXtream() {
        val p = CatchupAttemptPlanner(E, N, xtreamApi = true, savedStyle = TimeshiftStyle.PATH)
        assertThat(p.current).isEqualTo(CatchupAttempt(E, TimeshiftStyle.PATH))
        assertThat(p.advance()).isEqualTo(CatchupAttempt(E, TimeshiftStyle.PHP))
        assertThat(p.advance()).isNull()
    }

    @Test fun rewindCurrentRetriesWithNowThenOtherStyle() {
        val future = N + 1800
        val p = CatchupAttemptPlanner(future, N, true, TimeshiftStyle.PATH)
        val seen = mutableListOf(p.current)
        while (true) seen += p.advance() ?: break
        assertThat(seen).containsExactly(
            CatchupAttempt(future, TimeshiftStyle.PATH), CatchupAttempt(N, TimeshiftStyle.PATH),
            CatchupAttempt(future, TimeshiftStyle.PHP), CatchupAttempt(N, TimeshiftStyle.PHP),
        ).inOrder()
    }

    @Test fun savedPhpStyleIsTriedFirstAndRememberedOnlyOnChange() {
        val p = CatchupAttemptPlanner(E, N, true, TimeshiftStyle.PHP)
        assertThat(p.current.style).isEqualTo(TimeshiftStyle.PHP)
        assertThat(p.styleToRemember()).isNull()
        p.advance()
        assertThat(p.styleToRemember()).isEqualTo(TimeshiftStyle.PATH)
    }

    @Test fun nonXtreamHasNoStyleVariants() {
        val p = CatchupAttemptPlanner(N + 600, N, false, TimeshiftStyle.PATH)
        assertThat(p.current.style).isNull()
        assertThat(p.advance()).isEqualTo(CatchupAttempt(N, null))
        assertThat(p.advance()).isNull()
        assertThat(p.styleToRemember()).isNull()
    }

    @Test fun xtreamPathAndPhpUrlsFollowTheAttemptStyle() {
        val path = CatchupRequests.build("ignored", api, target, S * 1000, N * 1000, CatchupAttempt(E, TimeshiftStyle.PATH))
        assertThat(path.url).isEqualTo("http://xc.tv:8080/timeshift/user/pass/60/2026-11-10:21-00/123.ts")
        val php = CatchupRequests.build("ignored", api, target, S * 1000, N * 1000, CatchupAttempt(E, TimeshiftStyle.PHP))
        assertThat(php.url).isEqualTo("http://xc.tv:8080/streaming/timeshift.php?username=user&password=pass&stream=123&start=2026-11-10:21-00&duration=60")
    }

    @Test fun rewindCurrentStartsOnMinuteAndKeepsInitialSeek() {
        val start = 1794397530L // 11:45:30
        val end = 1794400200L // 12:30
        val u = CatchupRequests.build("x", api.copy(tz = ZoneId.of("UTC")), target, start * 1000, N * 1000, CatchupAttempt(end, TimeshiftStyle.PATH))
        assertThat(u.url).isEqualTo("http://xc.tv:8080/timeshift/user/pass/45/2026-11-11:11-45/123.ts")
        assertThat(u.streamOriginSec).isEqualTo(1794397500L)
        assertThat(u.initialSeekMs).isEqualTo(30_000L)
    }

    @Test fun m3uTypesUseTheBuilderUnchanged() {
        val spec = CatchupSpec(CatchupType.SHIFT, null, 3)
        val u = CatchupRequests.build("http://p.tv/ch1/index.m3u8?token=abc", spec, null, S * 1000, N * 1000, CatchupAttempt(E, null))
        assertThat(u.url).isEqualTo("http://p.tv/ch1/index.m3u8?token=abc&utc=$S&lutc=$N")
        assertThat(u.streamOriginSec).isEqualTo(S)
    }

    @Test fun unsupportedCatchupPropagates() {
        val bad = runCatching { CatchupRequests.build("http://x/movie/u/p/1.ts", CatchupSpec(CatchupType.XTREAM, null, 3), null, S * 1000, N * 1000, CatchupAttempt(E, null)) }
        assertThat(bad.exceptionOrNull()).isInstanceOf(UnsupportedCatchup::class.java)
    }

    @Test fun programmeRowsMarkPastNowUpcoming() {
        val nowMs = N * 1000
        val day = 86_400_000L
        val slots = listOf(
            ProgrammeSlot(1, nowMs - 5 * day, nowMs - 5 * day + 3_600_000, "old"),
            ProgrammeSlot(2, nowMs - 3_600_000 * 2, nowMs - 3_600_000, "past"),
            ProgrammeSlot(3, nowMs - 600_000, nowMs + 600_000, "now"),
            ProgrammeSlot(4, nowMs + 600_000, nowMs + 3_600_000, "next"),
        )
        val rows = ProgrammeList.build(slots, nowMs, 3, nowMs - 3_700_000)
        assertThat(rows.map { it.state }).containsExactly(RowState.PAST_UNAVAILABLE, RowState.PAST_PLAYABLE, RowState.NOW, RowState.UPCOMING).inOrder()
        assertThat(rows[1].playing).isTrue()
        assertThat(ProgrammeList.nowIndex(rows)).isEqualTo(2)
        assertThat(ProgrammeList.build(slots, nowMs, 0, null).map { it.state }[1]).isEqualTo(RowState.PAST_UNAVAILABLE)
    }

    @Test fun catchupTemplateWithALocalSchemeIsRejected() {
        val planner = CatchupAttemptPlanner(E, N, xtreamApi = false, savedStyle = TimeshiftStyle.PATH)
        for (src in listOf("file:///data/x?s={utc}", "content://media/{utc}", "android.resource://p/1")) {
            val spec = CatchupSpec(CatchupType.DEFAULT, src, 7, 0, ZoneId.of("UTC"))
            org.junit.Assert.assertThrows(UnsupportedCatchup::class.java) {
                CatchupRequests.build("http://h/live/1.ts", spec, null, S * 1000, N * 1000, planner.current)
            }
        }
        val ok = CatchupSpec(CatchupType.DEFAULT, "http://h/arch/{utc}.ts", 7, 0, ZoneId.of("UTC"))
        assertThat(CatchupRequests.build("http://h/live/1.ts", ok, null, S * 1000, N * 1000, planner.current).url).startsWith("http://h/arch/")
    }
}
