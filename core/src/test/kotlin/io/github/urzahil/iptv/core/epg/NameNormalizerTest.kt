package io.github.urzahil.iptv.core.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameNormalizerTest {
    private fun n(s: String) = NameNormalizer.normalize(s)

    @Test fun plan_examples() {
        assertThat(n("UK: BBC One HD")).isEqualTo("bbcone")
        assertThat(n("BBC One +1")).isEqualTo("bbcone+1")
    }

    @Test fun diacritics_and_ampersand() {
        assertThat(n("Télévision Française")).isEqualTo("televisionfrancaise")
        assertThat(n("Fox & Friends")).isEqualTo("foxandfriends")
        assertThat(n("Ünsal TV")).isEqualTo("unsaltv")
    }

    @Test fun brackets_quality_tags() {
        assertThat(n("[DE] Das Erste (Backup) FHD")).isEqualTo("daserste")
        assertThat(n("Sky Cinema 4K HEVC")).isEqualTo("skycinema")
        assertThat(n("RTL [HD] 1080p")).isEqualTo("rtl")
        assertThat(n("US | ESPN 2")).isEqualTo("espn2")
    }

    @Test fun blank() {
        assertThat(n("")).isEmpty()
        assertThat(NameNormalizer.normalize(null)).isEmpty()
    }
}
