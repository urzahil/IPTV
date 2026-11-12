package rocks.urzahil.iptv.player

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import rocks.urzahil.iptv.data.repo.ChannelPrefsRepository
import rocks.urzahil.iptv.data.repo.ChannelRepository
import rocks.urzahil.iptv.data.repo.EpgRepository
import rocks.urzahil.iptv.data.repo.StreamInputsRepository
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.SettingsRepository
import rocks.urzahil.iptv.player.engine.EngineConfig
import rocks.urzahil.iptv.player.engine.EngineFactory
import rocks.urzahil.iptv.player.engine.PlayerEngine
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.sync.FakeCipher
import rocks.urzahil.iptv.sync.M3uImporter
import rocks.urzahil.iptv.sync.addSource
import rocks.urzahil.iptv.sync.newDb

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaybackCrashTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private var channelId = 0L

    @Before fun setUp() {
        db = newDb()
        val src = db.addSource()
        M3uImporter(db).import(src, "#EXTM3U\n#EXTINF:-1,One\nhttp://s.example/stream/one.ts\n".byteInputStream())
        channelId = db.channelDao().matchRows(src.id).first().id
    }

    @After fun tearDown() { db.close() }

    private class ThrowingFactory(val error: Throwable) : EngineFactory {
        override fun create(kind: EngineKind, config: EngineConfig): PlayerEngine = throw error
    }

    private fun controller(factory: EngineFactory, handled: (Throwable) -> Unit = {}): PlaybackController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(tmp.root, "s.preferences_pb") }
        val settings = SettingsRepository(store)
        val redactor = Redactor()
        lateinit var c: PlaybackController
        // The same wiring as AppContainer.playback.
        val handler = CoroutineExceptionHandler { _, t -> handled(t); c.onCrash(t) }
        c = PlaybackController(
            context, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler),
            StreamInputsRepository(db, SourceSecrets(FakeCipher(), redactor), settings),
            ChannelRepository(db), EpgRepository(db), ChannelPrefsRepository(db.channelPrefDao()), db.sourceDao(),
            settings, OkHttpClient(), redactor, factory,
        )
        return c
    }

    private fun awaitPhase(c: PlaybackController, phase: Phase) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (c.ui.value.phase != phase && System.nanoTime() < end) Thread.sleep(20)
    }

    @Test fun anExceptionEscapingAPlaybackCoroutineEndsInAnErrorStateNotACrash() {
        val seen = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val c = controller(ThrowingFactory(IllegalStateException("could not create http://s.example/user/pass/1.ts")), seen::set)
        c.play(PlayRequest(channelId, null, null, null, null))
        awaitPhase(c, Phase.ERROR)
        assertThat(seen.get()).isInstanceOf(IllegalStateException::class.java)
        val ui = c.ui.value
        assertThat(ui.phase).isEqualTo(Phase.ERROR)
        assertThat(ui.playing).isFalse()
        assertThat(ui.error!!.title).isEqualTo("Playback failed")
        assertThat(ui.error!!.kind).isEqualTo(ErrorKind.GENERIC)
        // Nothing of the exception text, which could carry a URL, reaches the UI state.
        assertThat(ui.error.toString()).doesNotContain("s.example")
        assertThat(c.engine.value).isNull()
        assertThat(c.backgroundHold.value).isFalse()
    }

    @Test fun errorsThatAreNotExceptionsAreHandledToo() {
        val c = controller(ThrowingFactory(NoClassDefFoundError("org/videolan/libvlc/LibVLC")))
        c.play(PlayRequest(channelId, null, null, null, null))
        awaitPhase(c, Phase.ERROR)
        assertThat(c.ui.value.phase).isEqualTo(Phase.ERROR)
    }

    @Test fun onCrashIsSafeWithoutASession() {
        val c = controller(ThrowingFactory(IllegalStateException()))
        c.onCrash(RuntimeException("x"))
        assertThat(c.ui.value.phase).isEqualTo(Phase.ERROR)
    }
}
