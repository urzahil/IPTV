package io.github.urzahil.iptv.core.model

data class ParsedChannel(
    /** Stable provider key, see [io.github.urzahil.iptv.core.m3u.ChannelKeys]. */
    val key: String,
    val name: String,
    val url: String,
    val groups: List<String>,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val chno: Int?,
    val shiftMin: Int,
    val catchup: CatchupSpec,
    /** Channel-level headers only (no app/source defaults). */
    val headers: StreamHeaders,
    val isRadio: Boolean,
    val kodiProps: Map<String, String>,
    /** DRM was declared; the channel may not play. */
    val drm: Boolean,
    /** "hls" or "dash" when declared by KODIPROP. */
    val mimeHint: String? = null,
)
