package rocks.urzahil.iptv.data.repo

import android.net.Uri
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.core.util.Urls
import rocks.urzahil.iptv.core.xtream.XtreamException
import rocks.urzahil.iptv.core.xtream.XtreamUrls
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.EpgSourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.net.ContentSource
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.security.XtreamCredentials
import rocks.urzahil.iptv.data.settings.SettingsRepository
import rocks.urzahil.iptv.sync.EpgSourceManager
import rocks.urzahil.iptv.sync.XtreamImporter
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Editable fields of a source. [password] and [location] blank on update keep the stored value. */
data class SourceForm(
    val name: String,
    /** Xtream server address or M3U URL. */
    val location: String = "",
    val username: String = "",
    val password: String = "",
    val userAgent: String = "",
    val referrer: String = "",
    val streamFormat: String = "ts",
    val tzOverride: String = "",
)

/** What the edit screen may show. The password is never exposed. */
data class EditableSource(val source: SourceEntity, val location: String, val username: String)

sealed interface XtreamLoginResult {
    data class Ok(
        val status: String?,
        val expiresAtMs: Long?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        val formats: List<String>,
    ) : XtreamLoginResult

    /** [message] is redacted. */
    data class Failed(val message: String) : XtreamLoginResult
}

class SourceRepository(
    private val db: AppDatabase,
    private val secrets: SourceSecrets,
    private val client: OkHttpClient,
    private val redactor: Redactor,
    private val content: ContentSource,
    private val settings: SettingsRepository,
) {
    private val dao = db.sourceDao()
    private val epgSources = EpgSourceManager(db, secrets)

    val sources: Flow<List<SourceEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<SourceEntity?> = dao.observe(id)

    fun observeEpgSources(ownerId: Long): Flow<List<EpgSourceEntity>> = db.epgDao().observeSourcesOf(ownerId)

    suspend fun epgSourcesOf(ownerId: Long): List<EpgSourceEntity> = withContext(Dispatchers.IO) { db.epgDao().sourcesOfBlocking(ownerId) }

    suspend fun get(id: Long): SourceEntity? = dao.get(id)

    suspend fun getAll(): List<SourceEntity> = dao.getAll()

    suspend fun editable(id: Long): EditableSource? = withContext(Dispatchers.IO) {
        val s = dao.get(id) ?: return@withContext null
        val loc = when (s.sourceType) {
            SourceType.XTREAM -> s.serverUrl.orEmpty()
            SourceType.M3U_URL -> secrets.m3uUrl(s).orEmpty()
            SourceType.M3U_FILE -> ""
        }
        EditableSource(s, loc, secrets.decrypt(s.usernameEnc).orEmpty())
    }

    /** Inserts a new source and returns its id. */
    suspend fun add(type: SourceType, form: SourceForm, fileUri: Uri? = null): Long = withContext(Dispatchers.IO) {
        require(form.name.isNotBlank()) { "Name is required" }
        var s = SourceEntity(
            type = type.name,
            name = form.name.trim(),
            sortOrder = dao.nextSortOrder(),
            userAgent = form.userAgent.trim().ifEmpty { null },
            referrer = form.referrer.trim().ifEmpty { null },
            streamFormat = if (form.streamFormat == "m3u8") "m3u8" else "ts",
            tzOverride = form.tzOverride.trim().ifEmpty { null },
        )
        s = when (type) {
            SourceType.XTREAM -> withXtream(s, form, keepPassword = null)
            SourceType.M3U_URL -> withUrl(s, form.location)
            SourceType.M3U_FILE -> {
                val uri = requireNotNull(fileUri) { "File is required" }
                content.takePermission(uri)
                s.copy(fileUri = uri.toString())
            }
        }
        dao.insert(s)
    }

    /** Updates a source. Changing the address or credentials forgets the cached validators so the next sync is full. */
    suspend fun update(id: Long, form: SourceForm) = withContext(Dispatchers.IO) {
        val old = dao.get(id) ?: return@withContext
        var s = old.copy(
            name = form.name.trim().ifEmpty { old.name },
            userAgent = form.userAgent.trim().ifEmpty { null },
            referrer = form.referrer.trim().ifEmpty { null },
            streamFormat = if (form.streamFormat == "m3u8") "m3u8" else "ts",
            tzOverride = form.tzOverride.trim().ifEmpty { null },
        )
        when (old.sourceType) {
            SourceType.XTREAM -> if (form.location.isNotBlank() || form.username.isNotBlank() || form.password.isNotBlank()) {
                s = withXtream(s, form.copy(
                    location = form.location.ifBlank { old.serverUrl.orEmpty() },
                    username = form.username.ifBlank { secrets.decrypt(old.usernameEnc).orEmpty() },
                ), keepPassword = old.passwordEnc)
            }
            SourceType.M3U_URL -> if (form.location.isNotBlank()) s = withUrl(s, form.location)
            SourceType.M3U_FILE -> Unit
        }
        dao.update(s)
        if (s.serverUrl != old.serverUrl || s.usernameEnc != old.usernameEnc || s.passwordEnc != old.passwordEnc ||
            s.m3uUrlEnc != old.m3uUrlEnc
        ) {
            dao.clearPlaylistValidators(id)
            db.epgDao().clearValidators(id)
        }
    }

    private fun withXtream(s: SourceEntity, f: SourceForm, keepPassword: String?): SourceEntity {
        val base = XtreamUrls.normalizeBase(f.location)
        require(f.username.isNotBlank()) { "Username is required" }
        val passEnc = if (f.password.isNotEmpty()) {
            redactor.register(f.password)
            secrets.encrypt(f.password)
        } else {
            keepPassword ?: throw IllegalArgumentException("Password is required")
        }
        redactor.register(f.username.trim())
        return s.copy(
            serverUrl = base,
            usernameEnc = secrets.encrypt(f.username.trim()),
            passwordEnc = passEnc,
            insecureHttp = base.startsWith("http://", ignoreCase = true),
            status = SourceEntity.STATUS_NEW,
        )
    }

    private fun withUrl(s: SourceEntity, raw: String): SourceEntity {
        val url = Urls.withDefaultScheme(raw.trim())
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "Enter an http(s) address" }
        secrets.registerUrlSecrets(url)
        return s.copy(
            m3uUrlEnc = secrets.encrypt(url),
            insecureHttp = url.startsWith("http://", ignoreCase = true),
            status = SourceEntity.STATUS_NEW,
        )
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** Deletes a source with its channels, categories and EPG data. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val s = dao.get(id) ?: return@withContext
        s.fileUri?.let { content.releasePermission(it) }
        // Programmes have no foreign key to epg_channels: remove them explicitly before the cascade.
        val epgChannelIds = db.epgDao().channelIdsOf(id)
        for (chunk in epgChannelIds.chunked(200)) {
            db.runInTransaction {
                for (ch in chunk) {
                    val r = rocks.urzahil.iptv.core.epg.ProgrammeId.channelRange(ch)
                    db.programmeDao().deleteRange(r.first, r.last)
                }
            }
        }
        dao.delete(id)
    }

    // ---- EPG URLs ----

    suspend fun addEpgUrl(ownerId: Long, url: String): Long = withContext(Dispatchers.IO) {
        val u = Urls.withDefaultScheme(url.trim())
        require(u.startsWith("http://", true) || u.startsWith("https://", true)) { "Enter an http(s) address" }
        secrets.registerUrlSecrets(u)
        epgSources.addUserUrl(ownerId, u)
    }

    suspend fun removeEpgSource(id: Long) = db.epgDao().deleteSource(id)

    // ---- login test ----

    suspend fun testXtream(form: SourceForm): XtreamLoginResult = withContext(Dispatchers.IO) {
        if (form.location.isBlank() || form.username.isBlank() || form.password.isEmpty()) {
            return@withContext XtreamLoginResult.Failed("Enter server, username and password")
        }
        val creds = XtreamCredentials(XtreamUrls.normalizeBase(form.location), form.username.trim(), form.password)
        redactor.register(creds.username)
        redactor.register(creds.password)
        val ua = form.userAgent.trim().ifEmpty { settings.current().defaultUserAgent }
        try {
            val auth = XtreamImporter(db, client).login(creds, mapOf("User-Agent" to ua))
            XtreamLoginResult.Ok(
                auth.account.status,
                auth.account.expiresAtSec?.takeIf { it > 0 }?.times(1000),
                auth.account.maxConnections,
                auth.account.activeConnections,
                auth.account.allowedFormats,
            )
        } catch (e: XtreamException) {
            XtreamLoginResult.Failed(redactor.redact(e.message))
        } catch (e: IOException) {
            XtreamLoginResult.Failed(redactor.redact(e))
        } catch (e: IllegalArgumentException) {
            XtreamLoginResult.Failed("Invalid server address")
        }
    }
}
