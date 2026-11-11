package io.github.urzahil.iptv.ui

object Routes {
    const val CHANNELS = "channels"
    const val GUIDE = "guide"
    const val SEARCH = "search"
    const val FAVOURITES = "favourites"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val ADD_XTREAM = "sources/add-xtream"
    const val ADD_M3U = "sources/add-m3u"
    const val EDIT_ARG = "id"
    const val EDIT_SOURCE = "sources/edit/{$EDIT_ARG}"

    fun editSource(id: Long) = "sources/edit/$id"
}
