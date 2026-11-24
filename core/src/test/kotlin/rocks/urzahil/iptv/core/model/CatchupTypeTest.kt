package rocks.urzahil.iptv.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatchupTypeTest {
    @Test fun storedCodesAreStable() {
        // These values are persisted in the database: changing one corrupts existing channels.
        assertThat(CatchupType.entries.associate { it.name to it.code }).containsExactly(
            "NONE", 0, "DEFAULT", 1, "APPEND", 2, "SHIFT", 3, "FLUSSONIC", 4, "XTREAM", 5, "XTREAM_API", 6,
        )
        assertThat(CatchupType.entries.map { it.code }).containsNoDuplicates()
    }

    @Test fun unknownCodeMapsToNone() {
        assertThat(CatchupType.fromCode(99)).isEqualTo(CatchupType.NONE)
        assertThat(CatchupType.fromCode(6)).isEqualTo(CatchupType.XTREAM_API)
    }
}
