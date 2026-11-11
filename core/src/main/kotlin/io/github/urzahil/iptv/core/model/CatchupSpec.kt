package io.github.urzahil.iptv.core.model

import java.time.ZoneId
import java.time.ZoneOffset

data class CatchupSpec(
    val type: CatchupType,
    val source: String? = null,
    val days: Int = 0,
    val correctionSec: Long = 0,
    val tz: ZoneId = ZoneOffset.UTC,
) {
    val enabled: Boolean get() = type != CatchupType.NONE && days > 0

    companion object {
        val NONE = CatchupSpec(CatchupType.NONE)
    }
}
