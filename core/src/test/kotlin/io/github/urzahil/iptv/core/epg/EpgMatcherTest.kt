package io.github.urzahil.iptv.core.epg

import com.google.common.truth.Truth.assertThat
import io.github.urzahil.iptv.core.epg.EpgMatcher.Entry
import org.junit.Test

class EpgMatcherTest {
    private val m = EpgMatcher(listOf(
        Entry(1, "BBC1.uk", NameNormalizer.normalize("BBC One")),
        Entry(2, "other.id", NameNormalizer.normalize("BBC One HD")),
        Entry(3, "cnn.us", "cnn"),
        Entry(4, "BBC1.uk", "bbcone"),   // lower priority duplicate
        Entry(5, "arte.fr", "arte"),
    ))

    @Test fun tvg_id_beats_names_and_is_case_insensitive() {
        assertThat(m.match("bbc1.UK", "CNN", "Arte")).isEqualTo(1)
        assertThat(m.match(" CNN.US ", null, null)).isEqualTo(3)
    }

    @Test fun tvg_name_beats_name() {
        assertThat(m.match("unknown", "CNN", "Arte")).isEqualTo(3)
    }

    @Test fun name_fallback_and_no_match() {
        assertThat(m.match(null, null, "ARTE HD")).isEqualTo(5)
        assertThat(m.match("", "", "Nothing")).isNull()
        assertThat(m.match(null, null, null)).isNull()
    }

    @Test fun first_source_in_priority_order_wins() {
        assertThat(m.match(null, null, "BBC One")).isEqualTo(1)
    }
}
