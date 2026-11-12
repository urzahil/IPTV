package rocks.urzahil.iptv.data.repo

import rocks.urzahil.iptv.core.util.Urls
import rocks.urzahil.iptv.core.xtream.XtreamUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Detects Xtream `get.php?username=..&password=..` playlist URLs so the user can add them as an account instead. */
object M3uUrlInspector {
    data class XtreamAccount(val base: String, val username: String, val password: String) {
        override fun toString() = "XtreamAccount(***)"
    }

    fun detectXtream(input: String): XtreamAccount? {
        val url = Urls.withDefaultScheme(input.trim()).toHttpUrlOrNull() ?: return null
        if (!url.encodedPath.endsWith("/get.php")) return null
        val user = url.queryParameter("username")?.takeIf { it.isNotEmpty() } ?: return null
        val pass = url.queryParameter("password")?.takeIf { it.isNotEmpty() } ?: return null
        return XtreamAccount(XtreamUrls.normalizeBase(input), user, pass)
    }
}
