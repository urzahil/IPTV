package io.github.urzahil.iptv.sync

import io.github.urzahil.iptv.data.db.AppDatabase
import io.github.urzahil.iptv.data.db.entities.EpgKind
import io.github.urzahil.iptv.data.db.entities.EpgSourceEntity
import io.github.urzahil.iptv.data.db.entities.SourceEntity
import io.github.urzahil.iptv.data.db.entities.SourceType
import io.github.urzahil.iptv.data.security.SourceSecrets
import java.security.MessageDigest

/** Maintains the epg_sources rows of a playlist source. Blocking. */
class EpgSourceManager(private val db: AppDatabase, private val secrets: SourceSecrets) {
    private val dao = db.epgDao()

    /**
     * Makes the EPG sources match the playlist: Xtream gets its `xmltv.php`; M3U gets the header URLs.
     * User-added URLs are never touched. Returns the ids of all EPG sources of the owner.
     */
    fun syncFromPlaylist(source: SourceEntity, headerUrls: List<String>): List<Long> {
        when (source.sourceType) {
            SourceType.XTREAM -> ensure(source.id, EpgKind.XTREAM, XTREAM_HASH, "Provider XMLTV", null, 0, false)
            else -> {
                val urls = headerUrls.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                val hashes = urls.map { hash(it) }
                dao.deleteHeaderSourcesExcept(source.id, hashes)
                urls.forEachIndexed { i, u ->
                    ensure(source.id, EpgKind.URL, hash(u), display(u), secrets.encrypt(u), HEADER_PRIORITY + i, false)
                }
            }
        }
        return dao.sourcesOfBlocking(source.id).map { it.id }
    }

    fun addUserUrl(ownerId: Long, url: String): Long {
        val clean = url.trim()
        val priority = USER_PRIORITY + dao.sourcesOfBlocking(ownerId).count { it.userAdded }
        return ensure(ownerId, EpgKind.URL, hash(clean), display(clean), secrets.encrypt(clean), priority, true)
    }

    private fun ensure(owner: Long, kind: String, hash: String, display: String, urlEnc: String?, priority: Int, user: Boolean): Long {
        dao.findByHash(owner, hash)?.let { return it.id }
        return dao.insertSource(
            EpgSourceEntity(
                ownerSourceId = owner, kind = kind, urlEnc = urlEnc, urlHash = hash,
                displayName = display, priority = priority, userAdded = user,
            ),
        )
    }

    companion object {
        const val XTREAM_HASH = "xtream"
        const val USER_PRIORITY = 1
        const val HEADER_PRIORITY = 10

        fun hash(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray()).joinToString("") { "%02x".format(it) }

        /** Host and path of a URL, without query string or credentials. */
        fun display(url: String): String {
            val noQuery = url.substringBefore('?').substringBefore('#')
            val rest = noQuery.substringAfter("://", noQuery)
            return rest.substringAfter('@')
        }
    }
}
