package rocks.urzahil.iptv.data.net

import rocks.urzahil.iptv.core.util.Redactor
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadResult {
    data object NotModified : DownloadResult
    data class Downloaded(val file: File, val etag: String?, val lastModified: String?) : DownloadResult
    /** [message] is already redacted. */
    data class Failed(val message: String, val retryable: Boolean) : DownloadResult
}

/** Conditional GET into a cache file. Content is never decoded here (see core Compression). */
class Downloader(private val redactor: Redactor) {
    fun download(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        etag: String?,
        lastModified: String?,
        dest: File,
        onProgress: ((bytes: Long) -> Unit)? = null,
    ): DownloadResult {
        val req = try {
            Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (!etag.isNullOrEmpty()) header("If-None-Match", etag)
                if (!lastModified.isNullOrEmpty()) header("If-Modified-Since", lastModified)
            }.build()
        } catch (e: IllegalArgumentException) {
            return DownloadResult.Failed("Invalid address", retryable = false)
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 304 -> DownloadResult.NotModified
                    !resp.isSuccessful -> DownloadResult.Failed("HTTP ${resp.code}", retryable = resp.code >= 500 || resp.code == 429)
                    else -> {
                        dest.parentFile?.mkdirs()
                        var total = 0L
                        resp.body.byteStream().use { input ->
                            tmp.outputStream().buffered(BUFFER).use { out ->
                                val buf = ByteArray(BUFFER)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    total += n
                                    onProgress?.invoke(total)
                                }
                            }
                        }
                        if (dest.exists()) dest.delete()
                        if (!tmp.renameTo(dest)) throw IOException("Could not store download")
                        DownloadResult.Downloaded(dest, resp.header("ETag"), resp.header("Last-Modified"))
                    }
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            DownloadResult.Failed(redactor.redact(e), retryable = true)
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
