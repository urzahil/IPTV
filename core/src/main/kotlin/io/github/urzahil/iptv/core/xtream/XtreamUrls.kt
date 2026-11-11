package io.github.urzahil.iptv.core.xtream

import io.github.urzahil.iptv.core.util.Urls

object XtreamUrls {
    private val SUFFIXES = listOf("/player_api.php", "/get.php", "/xmltv.php")

    /** Normalises a user-entered server address to scheme://host[:port][/path] without trailing slash. */
    fun normalizeBase(input: String): String {
        var s = Urls.withDefaultScheme(input.trim(), "http")
        s = s.substringBefore('#').substringBefore('?').trimEnd('/')
        for (suf in SUFFIXES) {
            if (s.endsWith(suf, ignoreCase = true)) { s = s.substring(0, s.length - suf.length); break }
        }
        return s.trimEnd('/')
    }

    private fun auth(u: String, p: String) = "username=${Urls.encodeQuery(u)}&password=${Urls.encodeQuery(p)}"

    fun account(base: String, u: String, p: String) = "$base/player_api.php?${auth(u, p)}"
    fun liveCategories(base: String, u: String, p: String) = account(base, u, p) + "&action=get_live_categories"
    fun liveStreams(base: String, u: String, p: String) = account(base, u, p) + "&action=get_live_streams"
    fun shortEpg(base: String, u: String, p: String, streamId: Long, limit: Int) =
        account(base, u, p) + "&action=get_short_epg&stream_id=$streamId&limit=$limit"
    fun simpleDataTable(base: String, u: String, p: String, streamId: Long) =
        account(base, u, p) + "&action=get_simple_data_table&stream_id=$streamId"
    fun xmltv(base: String, u: String, p: String) = "$base/xmltv.php?${auth(u, p)}"

    fun live(base: String, u: String, p: String, streamId: Long, ext: String = "ts") =
        "$base/live/${Urls.encodePath(u)}/${Urls.encodePath(p)}/$streamId.$ext"

    /** [start] formatted "yyyy-MM-dd:HH-mm". */
    fun timeshiftPath(base: String, u: String, p: String, durMin: Long, start: String, streamId: String) =
        "$base/timeshift/${Urls.encodePath(u)}/${Urls.encodePath(p)}/$durMin/$start/$streamId.ts"

    fun timeshiftPhp(base: String, u: String, p: String, durMin: Long, start: String, streamId: String) =
        "$base/streaming/timeshift.php?${auth(u, p)}&stream=$streamId&start=$start&duration=$durMin"
}
