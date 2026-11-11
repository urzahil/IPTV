package rocks.urzahil.iptv.core.xmltv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XmltvTimeTest {
    private val base = 1794340800_000L // 2026-11-10 20:00:00Z

    @Test fun utc_forms() {
        assertThat(XmltvTime.parse("20261110200000 +0000")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110200000 Z")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110200000UTC")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110200000 GMT")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110200000")).isEqualTo(base)
        assertThat(XmltvTime.parse("  20261110200000 +0000  ")).isEqualTo(base)
    }

    @Test fun offsets() {
        assertThat(XmltvTime.parse("20261110210000 +0100")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110210000 +01:00")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110143000 -0530")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110210000+0100")).isEqualTo(base)
    }

    @Test fun partial_precision() {
        assertThat(XmltvTime.parse("202611102000")).isEqualTo(base)
        assertThat(XmltvTime.parse("2026111020")).isEqualTo(base)
        assertThat(XmltvTime.parse("20261110")).isEqualTo(base - 20 * 3_600_000L)
    }

    @Test fun garbage() {
        assertThat(XmltvTime.parse("garbage")).isNull()
        assertThat(XmltvTime.parse("")).isNull()
        assertThat(XmltvTime.parse(null)).isNull()
        assertThat(XmltvTime.parse("20261310200000")).isNull()
        assertThat(XmltvTime.parse("20261110200000 +99")).isNull()
    }
}
