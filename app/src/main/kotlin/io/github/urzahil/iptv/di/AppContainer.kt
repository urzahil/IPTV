package io.github.urzahil.iptv.di

import android.content.Context
import io.github.urzahil.iptv.core.util.Redactor
import io.github.urzahil.iptv.data.db.AppDatabase
import io.github.urzahil.iptv.data.net.ContentSource
import io.github.urzahil.iptv.data.net.Downloader
import io.github.urzahil.iptv.data.net.HttpClientFactory
import io.github.urzahil.iptv.data.repo.ChannelRepository
import io.github.urzahil.iptv.data.repo.EpgRepository
import io.github.urzahil.iptv.data.repo.SourceRepository
import io.github.urzahil.iptv.data.repo.StreamInputsRepository
import io.github.urzahil.iptv.data.security.CredentialCipher
import io.github.urzahil.iptv.data.security.SecretCipher
import io.github.urzahil.iptv.data.security.SourceSecrets
import io.github.urzahil.iptv.data.settings.SettingsRepository
import io.github.urzahil.iptv.sync.EpgImporter
import io.github.urzahil.iptv.sync.PlaylistSyncRunner
import io.github.urzahil.iptv.sync.Pruner
import io.github.urzahil.iptv.sync.SyncScheduler
import io.github.urzahil.iptv.util.AppLog
import java.io.File

/** Hand-written service locator: lazy singletons. Nothing here touches the database on the main thread. */
class AppContainer(private val context: Context) {
    val redactor = Redactor().also { AppLog.redactor = it }
    private val http by lazy { HttpClientFactory() }
    private val cacheDir: File get() = File(context.cacheDir, "sync").apply { mkdirs() }

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
    val streamInputs by lazy { StreamInputsRepository(db, secrets, settings) }

    val playlistSyncRunner by lazy { PlaylistSyncRunner(db, http.client, downloader, secrets, redactor, contentSource, cacheDir) }
    val epgImporter by lazy { EpgImporter(db, http.epgClient, downloader, secrets, redactor, cacheDir) }
    val pruner by lazy { Pruner(db) }
}
