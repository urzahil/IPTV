package io.github.urzahil.iptv.data.net

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream

/** Opens local playlist files chosen through the Storage Access Framework. */
class ContentSource(private val resolver: ContentResolver) {
    fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("File is not available")

    fun releasePermission(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    fun takePermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }
}
