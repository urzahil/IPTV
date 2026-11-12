package rocks.urzahil.iptv.core.catchup

object FlussonicRewriter {
    /** [startSec] and [durationSec] are already correction-adjusted. */
    fun rewrite(url: String, startSec: Long, durationSec: Long): String {
        val hash = url.indexOf('#')
        val noFrag = if (hash >= 0) url.substring(0, hash) else url
        val frag = if (hash >= 0) url.substring(hash) else ""
        val q = noFrag.indexOf('?')
        val base = if (q >= 0) noFrag.substring(0, q) else noFrag
        val query = if (q >= 0) noFrag.substring(q) else ""

        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) throw UnsupportedCatchup("Not an absolute URL")
        val pathStart = base.indexOf('/', schemeEnd + 3)
        if (pathStart < 0) throw UnsupportedCatchup("Flussonic URL has no path")
        val origin = base.substring(0, pathStart)
        val path = base.substring(pathStart).trimEnd('/')
        if (path.isEmpty()) throw UnsupportedCatchup("Flussonic URL has no path")
        val slash = path.lastIndexOf('/')
        val dir = path.substring(0, slash + 1) // keeps trailing '/'
        val file = path.substring(slash + 1)

        val newPath = when {
            file.endsWith(".m3u8", ignoreCase = true) ->
                dir + file.dropLast(5) + "-$startSec-$durationSec.m3u8"
            file == "mpegts" || file.endsWith(".ts", ignoreCase = true) ->
                dir + "timeshift_abs-$startSec.ts"
            '.' !in file -> "$path/timeshift_abs-$startSec.ts"
            else -> throw UnsupportedCatchup("Unsupported Flussonic stream type: $file")
        }
        return origin + newPath + query + frag
    }
}
