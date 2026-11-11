// Opt-in, like ScreenshotTest: renders the player controls overlay with a fake engine state (no engine, no network).
//   ./gradlew :app:testDebugUnitTest -Pscreenshots --tests 'rocks.urzahil.iptv.screenshots.PlayerScreenshotTest'
package rocks.urzahil.iptv.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.urzahil.iptv.player.ChannelInfo
import rocks.urzahil.iptv.player.Phase
import rocks.urzahil.iptv.player.PlayerUiState
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.PlayMode
import rocks.urzahil.iptv.player.model.ProgrammeGuide
import rocks.urzahil.iptv.player.model.ProgrammeSlot
import rocks.urzahil.iptv.player.model.TimelineState
import rocks.urzahil.iptv.player.model.TrackOption
import rocks.urzahil.iptv.player.model.TrackSet
import rocks.urzahil.iptv.player.ui.PlayerActions
import rocks.urzahil.iptv.player.ui.PlayerOverlay
import rocks.urzahil.iptv.ui.theme.IptvTheme
import java.io.File
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w780dp-h360dp-xxhdpi")
class PlayerScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = System.getProperty("iptv.screenshots.dir") ?: "build/screenshots"

    @Before fun check() {
        assumeTrue("Screenshots are opt-in (-Pscreenshots)", System.getProperty("iptv.screenshots") == "true")
    }

    private fun state(behindMin: Long): PlayerUiState {
        val start = 1794340800_000L // 20:00 UTC
        val now = start + 41 * 60_000 + 20_000 // 20:41:20
        val wall = now - behindMin * 60_000
        val guide = ProgrammeGuide(listOf(ProgrammeSlot(1, start, start + 3_600_000, "The Evening Report")))
        return PlayerUiState(
            channel = ChannelInfo(1, "News 24", 101, null, false, 7),
            phase = Phase.PLAYING, playing = true, engine = EngineKind.MEDIA3,
            mode = if (behindMin > 0) PlayMode.CATCHUP else PlayMode.LIVE,
            timeline = TimelineState.of(if (behindMin > 0) PlayMode.CATCHUP else PlayMode.LIVE, 7, now, wall, guide),
            nextProgrammeTitle = "Late Edition",
            tracks = TrackSet(audio = listOf(TrackOption("0:0", "English", "en", "AC-3 5.1", true), TrackOption("1:0", "Deutsch", "de", "AAC stereo", false))),
        )
    }

    @Test fun controlsBehindLive() {
        compose.setContent {
            IptvTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    PlayerOverlay(state(behindMin = 29), controlsVisible = true, actions = PlayerActions(onPip = {}), zone = ZoneOffset.UTC)
                }
            }
        }
        compose.waitForIdle()
        File(outDir).mkdirs()
        compose.onRoot().captureRoboImage("$outDir/player.png")
    }
}
