package rocks.urzahil.iptv.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.iptv.data.settings.AppSettings
import rocks.urzahil.iptv.data.settings.SettingsRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsSanitiseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo() = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(tmp.root, "s.preferences_pb") },
    )

    @Test fun userAgentIsReducedToPrintableAscii() = runBlocking {
        val r = repo()
        r.setDefaultUserAgent("  My\r\nX-Injected: 1 Pläyer/2  ")
        assertThat(r.current().defaultUserAgent).isEqualTo("MyX-Injected: 1 Plyer/2")
    }

    @Test fun aUserAgentThatCleansToNothingFallsBackToTheDefault() = runBlocking {
        val r = repo()
        r.setDefaultUserAgent("äö\r\n")
        assertThat(r.current().defaultUserAgent).isEqualTo(AppSettings.DEFAULT_USER_AGENT)
    }
}
