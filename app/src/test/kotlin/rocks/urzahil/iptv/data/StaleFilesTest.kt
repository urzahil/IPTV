package rocks.urzahil.iptv.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rocks.urzahil.iptv.sync.StaleFiles

class StaleFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    private fun file(name: String, ageMs: Long) = File(tmp.root, name).also { it.writeText("x"); it.setLastModified(now - ageMs) }

    @Test fun deletesOnlyFilesOlderThanTheLimit() {
        val oldPlaylist = file("playlist-1.m3u", 30 * h)
        val oldTmp = file("epg-2.tmp", 25 * h)
        val oldPart = file("epg-2.tmp.part", 48 * h)
        val fresh = file("playlist-3.m3u", 2 * h)
        val edge = file("playlist-4.m3u", 24 * h)
        val n = StaleFiles.deleteOlderThan(tmp.root, StaleFiles.MAX_AGE_MS, now)
        assertThat(n).isEqualTo(3)
        assertThat(listOf(oldPlaylist, oldTmp, oldPart).any { it.exists() }).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(edge.exists()).isTrue()
    }

    @Test fun leavesDirectoriesAndMissingFoldersAlone() {
        val dir = File(tmp.root, "sub").also { it.mkdir(); it.setLastModified(now - 99 * h) }
        assertThat(StaleFiles.deleteOlderThan(tmp.root, StaleFiles.MAX_AGE_MS, now)).isEqualTo(0)
        assertThat(dir.exists()).isTrue()
        assertThat(StaleFiles.deleteOlderThan(File(tmp.root, "nope"), StaleFiles.MAX_AGE_MS, now)).isEqualTo(0)
    }
}
