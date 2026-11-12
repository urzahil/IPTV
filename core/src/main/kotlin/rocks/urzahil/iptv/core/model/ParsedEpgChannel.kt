package rocks.urzahil.iptv.core.model

data class ParsedEpgChannel(
    val id: String,
    val displayNames: List<String>,
    val icon: String?,
)
