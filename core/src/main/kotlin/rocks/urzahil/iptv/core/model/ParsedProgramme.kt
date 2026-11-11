package rocks.urzahil.iptv.core.model

data class ParsedProgramme(
    val xmltvChannelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val subTitle: String?,
    val desc: String?,
    /** Categories joined with ", ". */
    val categories: String?,
    val episode: String?,
    val icon: String?,
    val catchupId: String?,
)
