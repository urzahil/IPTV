package rocks.urzahil.iptv.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SourceType { XTREAM, M3U_URL, M3U_FILE }

/** Credentials and URLs are stored encrypted (`*Enc` columns, see CredentialCipher). */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    /** Xtream base URL (no credentials). */
    val serverUrl: String? = null,
    val usernameEnc: String? = null,
    val passwordEnc: String? = null,
    val m3uUrlEnc: String? = null,
    val fileUri: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** "ts" or "m3u8" (Xtream live container). */
    val streamFormat: String = "ts",
    /** "path" or "php" (Xtream timeshift URL style). */
    val timeshiftStyle: String = "path",
    /** Server zone reported by the panel (IANA id or offset). */
    val serverTz: String? = null,
    /** User override of [serverTz]. */
    val tzOverride: String? = null,
    val syncGen: Int = 0,
    val lastPlaylistSyncMs: Long = 0,
    val lastEpgSyncMs: Long = 0,
    val playlistEtag: String? = null,
    val playlistLastModified: String? = null,
    /** Outcome of the last playlist sync: NEW, OK, ERROR or NEEDS_CREDENTIALS. */
    val status: String = STATUS_NEW,
    /** Redacted. */
    val lastError: String? = null,
    /** Redacted error of the last EPG sync. */
    val epgError: String? = null,
    val lastSummary: String? = null,
    val expiresAtMs: Long? = null,
    val maxConnections: Int? = null,
    /** Comma-separated allowed_output_formats. */
    val allowedFormats: String? = null,
    /** True when the source talks plain HTTP (UI hint). */
    val insecureHttp: Boolean = false,
    val channelCount: Int = 0,
) {
    val sourceType: SourceType get() = SourceType.valueOf(type)
    val effectiveTz: String? get() = tzOverride ?: serverTz

    companion object {
        const val STATUS_NEW = "NEW"
        const val STATUS_OK = "OK"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_NEEDS_CREDENTIALS = "NEEDS_CREDENTIALS"
    }
}
