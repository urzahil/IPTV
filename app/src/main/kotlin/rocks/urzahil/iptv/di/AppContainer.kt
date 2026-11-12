package rocks.urzahil.iptv.di

import android.content.Context
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.net.ContentSource
import rocks.urzahil.iptv.data.net.Downloader
import rocks.urzahil.iptv.data.net.HttpClientFactory
import rocks.urzahil.iptv.data.repo.ChannelPrefsRepository
import rocks.urzahil.iptv.data.repo.ChannelRepository
import rocks.urzahil.iptv.data.repo.EpgRepository
import rocks.urzahil.iptv.data.repo.ShortEpgRepository
import rocks.urzahil.iptv.data.repo.SourceRepository
import rocks.urzahil.iptv.data.repo.StreamInputsRepository
import rocks.urzahil.iptv.data.security.CredentialCipher
import rocks.urzahil.iptv.data.security.SecretCipher
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.SettingsRepository
import rocks.urzahil.iptv.player.PlaybackController
import rocks.urzahil.iptv.player.engine.DefaultEngineFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import rocks.urzahil.iptv.sync.EpgImporter
import rocks.urzahil.iptv.sync.PlaylistSyncRunner
import rocks.urzahil.iptv.sync.Pruner
import rocks.urzahil.iptv.sync.SyncScheduler
import rocks.urzahil.iptv.util.AppLog
import java.io.File

/** Hand-written service locator: lazy singletons. Nothing here touches the database on the main thread. */
class AppContainer(private val context: Context) {
    val redactor = Redactor().also { AppLog.redactor = it }
    private val http by lazy { HttpClientFactory() }
    /** Temp files of running syncs (downloads, playlist copies). */
    val syncCacheDir: File get() = File(context.cacheDir, "sync").apply { mkdirs() }

    val httpClient get() = http.client

    val db: AppDatabase by lazy { AppDatabase.create(context) }
    val cipher: SecretCipher by lazy { CredentialCipher() }
    val secrets by lazy { SourceSecrets(cipher, redactor) }
    val settings by lazy { SettingsRepository(context) }
    val contentSource by lazy { ContentSource(context.contentResolver) }
    val downloader by lazy { Downloader(redactor) }
    val syncScheduler by lazy { SyncScheduler(context) }

    val sourceRepository by lazy { SourceRepository(db, secrets, http.client, redactor, contentSource, settings) }
    val channelRepository by lazy { ChannelRepository(db) }
    val epgRepository by lazy { EpgRepository(db) }
    val channelPrefs by lazy { ChannelPrefsRepository(db.channelPrefDao()) }
    val streamInputs by lazy { StreamInputsRepository(db, secrets, settings) }

    val playlistSyncRunner by lazy { PlaylistSyncRunner(db, http.client, downloader, secrets, redactor, contentSource, syncCacheDir) }
    val epgImporter by lazy { EpgImporter(db, http.epgClient, downloader, secrets, redactor, syncCacheDir) }
    val pruner by lazy { Pruner(db) }

    /** Source/category filter shared by the Channels and Guide tabs. */
    val browseSelection = kotlinx.coroutines.flow.MutableStateFlow(rocks.urzahil.iptv.ui.channels.Selection())
    val shortEpg by lazy { ShortEpgRepository(db, secrets, http.client, settings) }

    /**
     * App-scoped so the player survives activity recreation, PiP and the background-audio hand-over. An exception
     * that escapes a playback coroutine is handled by [PlaybackController.onCrash] instead of ending the process.
     */
    val playback by lazy {
        lateinit var controller: PlaybackController
        val handler = CoroutineExceptionHandler { _, t -> controller.onCrash(t) }
        controller = PlaybackController(
            context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler), streamInputs, channelRepository,
            epgRepository, channelPrefs, db.sourceDao(), settings, http.client, redactor,
            DefaultEngineFactory(context, http.client),
        )
        controller
    }
}
