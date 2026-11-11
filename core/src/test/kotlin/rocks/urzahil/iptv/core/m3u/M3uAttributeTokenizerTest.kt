package rocks.urzahil.iptv.core.m3u

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uAttributeTokenizerTest {
    private fun p(s: String) = M3uAttributeTokenizer.parseExtInf(s)

    @Test fun quoted_values_with_commas_and_spaces() {
        val e = p("""-1 tvg-id="a b" tvg-name="Name, With Comma" group-title="X;Y",Real Title""")
        assertThat(e.duration).isEqualTo(-1.0)
        assertThat(e.attrs["tvg-id"]).isEqualTo("a b")
        assertThat(e.attrs["tvg-name"]).isEqualTo("Name, With Comma")
        assertThat(e.attrs["group-title"]).isEqualTo("X;Y")
        assertThat(e.title).isEqualTo("Real Title")
    }

    @Test fun single_quotes() {
        val e = p("-1 tvg-id='x y' tvg-logo='http://l/a.png',T")
        assertThat(e.attrs["tvg-id"]).isEqualTo("x y")
        assertThat(e.attrs["tvg-logo"]).isEqualTo("http://l/a.png")
        assertThat(e.title).isEqualTo("T")
    }

    @Test fun unquoted_values() {
        val e = p("0 tvg-id=abc tvg-chno=12 radio=true,Title")
        assertThat(e.attrs).containsExactly("tvg-id", "abc", "tvg-chno", "12", "radio", "true")
        assertThat(e.title).isEqualTo("Title")
        assertThat(e.duration).isEqualTo(0.0)
    }

    @Test fun missing_closing_quote() {
        val e = p("""-1 tvg-name="Broken name,Title""")
        assertThat(e.attrs["tvg-name"]).isEqualTo("Broken name")
        assertThat(e.title).isEqualTo("Title")
        val e2 = p("""-1 tvg-name="Oops""")
        assertThat(e2.attrs["tvg-name"]).isEqualTo("Oops")
        assertThat(e2.title).isEmpty()
    }

    @Test fun duplicate_keys_first_non_empty_wins() {
        val e = p("""-1 tvg-id="" tvg-id="one" tvg-id="two",T""")
        assertThat(e.attrs["tvg-id"]).isEqualTo("one")
    }

    @Test fun uppercase_keys() {
        val e = p("""-1 TVG-ID="x" Group-Title="G",T""")
        assertThat(e.attrs["tvg-id"]).isEqualTo("x")
        assertThat(e.attrs["group-title"]).isEqualTo("G")
    }

    @Test fun title_with_comma() {
        val e = p("-1,Name, With Comma")
        assertThat(e.title).isEqualTo("Name, With Comma")
        assertThat(e.attrs).isEmpty()
        assertThat(e.duration).isEqualTo(-1.0)
    }

    @Test fun no_duration() {
        val e = p("""tvg-id="a",Name""")
        assertThat(e.duration).isNull()
        assertThat(e.attrs["tvg-id"]).isEqualTo("a")
        assertThat(e.title).isEqualTo("Name")
        assertThat(p(",Just").title).isEqualTo("Just")
    }

    @Test fun float_duration() {
        assertThat(p("-1.5 a=b,T").duration).isEqualTo(-1.5)
    }

    @Test fun attributes_after_the_comma_fallback() {
        val e = p("""-1,tvg-id="a" group-title="G",Real Name""")
        assertThat(e.attrs["tvg-id"]).isEqualTo("a")
        assertThat(e.attrs["group-title"]).isEqualTo("G")
        assertThat(e.title).isEqualTo("Real Name")
    }

    @Test fun flag_without_value_and_empty_title() {
        val e = p("""-1 foo tvg-name="N",""")
        assertThat(e.attrs).containsKey("foo")
        assertThat(e.attrs["foo"]).isEmpty()
        assertThat(e.title).isEmpty()
    }

    @Test fun header_attributes() {
        val a = M3uAttributeTokenizer.parseAttributes(""" url-tvg="http://a/x.xml,http://b/y.xml" catchup=append""")
        assertThat(a["url-tvg"]).isEqualTo("http://a/x.xml,http://b/y.xml")
        assertThat(a["catchup"]).isEqualTo("append")
    }
}
