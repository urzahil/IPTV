// Opt-in screenshot generator: renders the app's real Compose UI (AppNavHost + real ViewModels over a
// seeded database) on the JVM with Robolectric native graphics + Roborazzi. No emulator needed.
//
// Regenerate (writes PNGs to <repo>/screenshots/):
//   ./gradlew :app:testDebugUnitTest -Pscreenshots --tests 'rocks.urzahil.iptv.screenshots.ScreenshotTest'
//
// Without -Pscreenshots the test is excluded, so the normal CI run does not execute it.
// All data below is invented (example.com / example.org hosts, generic channel names).
package rocks.urzahil.iptv.screenshots

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import rocks.urzahil.iptv.sync.SyncProgress
import rocks.urzahil.iptv.sync.SyncScheduler
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode
import rocks.urzahil.iptv.App
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.settings.AppSettings
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.sync.EpgImporter
import rocks.urzahil.iptv.sync.EpgMapper
import rocks.urzahil.iptv.sync.EpgSourceManager
import rocks.urzahil.iptv.sync.FakeCipher
import rocks.urzahil.iptv.sync.M3uImporter
import rocks.urzahil.iptv.ui.AppNavHost
import rocks.urzahil.iptv.ui.theme.IptvTheme
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** WorkManager is not initialised under Robolectric; syncing is irrelevant for static screenshots. */
@Implements(SyncScheduler::class)
class ShadowSyncScheduler {
    @Implementation fun schedulePeriodic() {}
    @Implementation fun checkNow() {}
    @Implementation fun requestPrune() {}
    @Implementation fun cancelSource(sourceId: Long) {}
    @Implementation fun syncPlaylist(sourceId: Long, manual: Boolean, needsNetwork: Boolean) {}
    @Implementation fun syncEpg(epgSourceId: Long, ownerSourceId: Long, force: Boolean) {}
    @Implementation fun observeProgress(sourceId: Long): Flow<SyncProgress?> = flowOf(null)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w360dp-h780dp-xxhdpi", shadows = [ShadowSyncScheduler::class])
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = System.getProperty("iptv.screenshots.dir") ?: "build/screenshots"
    private lateinit var container: AppContainer

    private data class Ch(val name: String, val group: String, val catchup: Boolean = false)

    private val sample = listOf(
        Ch("News 24", "News", true), Ch("World Report", "News", true), Ch("Daily Headlines", "News"),
        Ch("Business Today", "News"), Ch("Metro News", "News"),
        Ch("Sports Arena 1", "Sports", true), Ch("Sports Arena 2", "Sports", true), Ch("Football Live", "Sports"),
        Ch("Tennis Court", "Sports"), Ch("Racing Channel", "Sports"), Ch("Extreme Sports", "Sports"),
        Ch("Cinema Classic", "Movies", true), Ch("Action Movies", "Movies"), Ch("Comedy Central Stage", "Movies"),
        Ch("Premiere Cinema", "Movies", true), Ch("Horror Nights", "Movies"), Ch("Romance Movies", "Movies"),
        Ch("Kids Planet", "Kids", true), Ch("Cartoon Club", "Kids"), Ch("Little Explorers", "Kids"),
        Ch("Toon Time", "Kids"), Ch("Junior Junction", "Kids"),
        Ch("Hits Radio TV", "Music"), Ch("Rock Nation", "Music"), Ch("Pop Express", "Music"),
        Ch("Jazz Lounge", "Music"), Ch("Classical Hall", "Music"), Ch("Dance Floor", "Music"),
        Ch("Nature World", "Documentary", true), Ch("History Files", "Documentary"), Ch("Science Today", "Documentary"),
        Ch("Ocean Planet", "Documentary"), Ch("Wild Safari", "Documentary"), Ch("Ancient Worlds", "Documentary"),
        Ch("Travel Diaries", "Lifestyle"), Ch("Home Kitchen", "Lifestyle"), Ch("Fashion Week", "Lifestyle"),
        Ch("Garden Life", "Lifestyle"), Ch("Auto Show", "Lifestyle"), Ch("Weather Now", "News"),
    )

    private val titles = mapOf(
        "News" to listOf("Morning Briefing", "Headlines", "Market Watch", "The Evening Report", "World at Noon", "Late Edition"),
        "Sports" to listOf("Match of the Week", "Live: League Round 12", "Sports Roundup", "Highlights Show", "Grand Prix Qualifying", "Pre-Match Analysis"),
        "Movies" to listOf("The Lighthouse Keeper", "Midnight Express Line", "A Summer in Town", "Desert Run", "The Last Orchard", "Paper Moon Street"),
        "Kids" to listOf("Captain Pebble", "Forest Friends", "Space Pups", "The Tiny Train", "Bubble Bay", "Puzzle Island"),
        "Music" to listOf("Top 20 Countdown", "Unplugged Sessions", "Chart Hits", "Night Grooves", "Live in Concert", "Video Mix"),
        "Documentary" to listOf("Secrets of the Reef", "Lost Kingdoms", "The Deep Forest", "How It Is Made", "Mountains from Above", "River Journeys"),
        "Lifestyle" to listOf("Weekend Kitchen", "Dream Gardens", "Road Test", "Style Studio", "Cozy Homes", "Travel Notes"),
    )

    @Before fun setUp() {
        assumeTrue("Screenshots are opt-in (-Pscreenshots)", System.getProperty("iptv.screenshots") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>() as App
        container = app.container
        // The real cipher needs the Android Keystore, which Robolectric lacks.
        AppContainer::class.java.getDeclaredField("cipher\$delegate").apply { isAccessible = true }
            .set(container, lazyOf(FakeCipher()))
        seed()
    }

    private fun seed() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val db = container.db
        val xtream = db.sourceDao().insert(
            SourceEntity(
                type = SourceType.XTREAM.name, name = "Harbor TV (Xtream)", serverUrl = "http://iptv.example.com:8080",
                usernameEnc = container.cipher.encrypt("demo"), passwordEnc = container.cipher.encrypt("demo"),
                insecureHttp = true, sortOrder = 0,
            ),
        ).let { db.sourceDao().get(it)!! }
        val m3u = db.sourceDao().insert(
            SourceEntity(
                type = SourceType.M3U_URL.name, name = "Community Playlist", sortOrder = 1,
                m3uUrlEnc = container.cipher.encrypt("https://playlists.example.org/community/live.m3u"),
            ),
        ).let { db.sourceDao().get(it)!! }

        val playlist = buildString {
            appendLine("#EXTM3U")
            sample.forEachIndexed { i, c ->
                val id = c.name.lowercase().replace(' ', '.')
                val cu = if (c.catchup) " catchup=\"shift\" catchup-days=\"3\"" else ""
                appendLine("#EXTINF:-1 tvg-id=\"$id\" tvg-chno=\"${101 + i}\" group-title=\"${c.group}\"$cu,${c.name}")
                appendLine("http://iptv.example.com/live/${i + 1}.m3u8")
            }
        }
        M3uImporter(db).import(xtream, playlist.byteInputStream())
        val extra = listOf("Retro TV", "Open Air Cinema", "City Council Live", "Kids Corner", "Indie Music", "Chess Channel", "Night Owl", "Cooking Basics")
        val m3uText = "#EXTM3U\n" + extra.mapIndexed { i, n ->
            "#EXTINF:-1 tvg-id=\"c.$i\" group-title=\"${if (i % 2 == 0) "Entertainment" else "Community"}\",$n\nhttps://streams.example.org/c/$i.m3u8"
        }.joinToString("\n")
        M3uImporter(db).import(m3u, m3uText.byteInputStream())

        // XMLTV around "now": back-to-back programmes, staggered per channel so progress bars differ.
        val min = 60_000L
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun ts(ms: Long) = fmt.format(Date(ms))
        val xml = buildString {
            append("<?xml version=\"1.0\"?><tv>")
            sample.forEachIndexed { i, c ->
                val id = c.name.lowercase().replace(' ', '.')
                append("<channel id=\"$id\"><display-name>${c.name}</display-name></channel>")
                val t = titles.getValue(c.group)
                val lens = listOf(60, 45, 90, 30, 60, 75)
                var start = now - (4 * 60 + (i * 17) % 50) * min
                var k = i
                while (start < now + 8 * 60 * min) {
                    val len = lens[k % lens.size] * min
                    append("<programme start=\"${ts(start)}\" stop=\"${ts(start + len)}\" channel=\"$id\">")
                    append("<title>${t[k % t.size].replace("&", "&amp;")}</title><desc>Sample programme.</desc></programme>")
                    start += len; k++
                }
            }
            append("</tv>")
        }
        val file = File.createTempFile("epg", ".xml").also { it.writeText(xml); it.deleteOnExit() }
        val epgId = EpgSourceManager(db, container.secrets).addUserUrl(xtream.id, "http://epg.example.com/guide.xml")
        val settings = AppSettings()
        container.epgImporter.importFile(epgId, xtream.id, file, settings, now)
        EpgMapper(db).map(xtream.id)

        db.epgDao().markSynced(epgId, null, null, now - 25 * min, null)
        db.sourceDao().setEpgResult(xtream.id, now - 25 * min, null)
        db.sourceDao().setAccountInfo(xtream.id, now + 214L * 24 * 60 * min, 2, "UTC", "ts,m3u8")
        db.sourceDao().setPlaylistOk(xtream.id, 1, now - 25 * min, "${sample.size} channels, 7 groups", sample.size, null, null)
        db.sourceDao().setPlaylistOk(m3u.id, 1, now - 3 * 60 * min, "${extra.size} channels, 2 groups", extra.size, null, null)

        // Favourites: a mix across groups.
        val repo = container.channelRepository
        for (name in listOf("News 24", "Sports Arena 1", "Cinema Classic", "Kids Planet", "Nature World", "Jazz Lounge")) {
            db.channelDao().findByNumber(xtream.id, 101 + sample.indexOfFirst { it.name == name })?.let { repo.toggleFavourite(it) }
        }
    }

    private fun settle(text: String) {
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            shot("debug-timeout")
            println("TREE: " + compose.onRoot().printToString(100))
            throw e
        }
        compose.waitForIdle()
        // Let flows (now/next) and paging settle a little longer.
        Thread.sleep(500); compose.waitForIdle()
    }

    private fun shot(name: String) {
        File(outDir).mkdirs()
        compose.onRoot().captureRoboImage("$outDir/$name.png")
    }

    @Test fun capture() {
        compose.setContent { IptvTheme(dynamicColor = false) { AppNavHost() } }
        settle("News 24")
        shot("channels")

        compose.onNodeWithText("Guide").performClick()
        settle("World at Noon")
        shot("guide")
        // Drag the grid to the right (back in time), then open a past programme of a catchup channel.
        compose.onAllNodesWithText("World at Noon", substring = true)[0].performTouchInput { swipeRight(startX = centerX, endX = centerX + 150.dp.toPx(), durationMillis = 400) }
        settle("The Evening Report")
        compose.onAllNodesWithText("The Evening Report", substring = true)[0].performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Available on catchup").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(500); compose.waitForIdle()
        File(outDir).mkdirs()
        captureScreenRoboImage("$outDir/guide_programme.png")
        compose.onNodeWithText("Watch").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()

        compose.onNodeWithText("Favourites").performClick()
        settle("Sports Arena 1")
        shot("favourites")

        compose.onNodeWithText("Search").performClick()
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("sports")
        settle("Sports Arena 2")
        shot("search")

        compose.onNodeWithText("Sources").performClick()
        settle("Harbor TV (Xtream)")
        shot("sources")

        compose.onNodeWithContentDescription("Settings").performClick()
        settle("Default user agent")
        shot("settings")
    }
}
