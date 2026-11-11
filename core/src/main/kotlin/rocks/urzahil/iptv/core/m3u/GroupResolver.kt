package rocks.urzahil.iptv.core.m3u

object GroupResolver {
    const val UNCATEGORIZED = "Uncategorized"

    fun split(value: String?): List<String> =
        value.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** group-title wins; #EXTGRP only when group-title yields nothing; else Uncategorized. */
    fun resolve(groupTitle: String?, extGrp: String?): List<String> {
        val g = split(groupTitle)
        if (g.isNotEmpty()) return g
        val e = split(extGrp)
        if (e.isNotEmpty()) return e
        return listOf(UNCATEGORIZED)
    }
}
