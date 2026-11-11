package rocks.urzahil.iptv.core.xtream

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class JsonLooseTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun numbers_as_strings() {
        val j = o("""{"a":"12","b":12,"c":"12.0","d":12.7,"e":"x","f":" 7 "}""")
        assertThat(j.int("a")).isEqualTo(12)
        assertThat(j.int("b")).isEqualTo(12)
        assertThat(j.long("c")).isEqualTo(12L)
        assertThat(j.int("d")).isEqualTo(12)
        assertThat(j.int("e")).isNull()
        assertThat(j.long("f")).isEqualTo(7L)
        assertThat(j.int("missing")).isNull()
        assertThat(o("""{"big":"1794340800"}""").long("big")).isEqualTo(1794340800L)
    }

    @Test fun empty_and_null() {
        val j = o("""{"a":"","b":null,"c":" x "}""")
        assertThat(j.str("a")).isEqualTo("")
        assertThat(j.strNotBlank("a")).isNull()
        assertThat(j.str("b")).isNull()
        assertThat(j.int("a")).isNull()
        assertThat(j.int("b")).isNull()
        assertThat(j.strNotBlank("c")).isEqualTo("x")
        assertThat(j.bool("a")).isNull()
        assertThat(j.bool("b")).isNull()
    }

    @Test fun bool_vs_string_one() {
        val j = o("""{"a":true,"b":"1","c":1,"d":false,"e":"0","f":0,"g":"true","h":"no"}""")
        assertThat(listOf("a", "b", "c", "g").map { j.bool(it) }).containsExactly(true, true, true, true)
        assertThat(listOf("d", "e", "f", "h").map { j.bool(it) }).containsExactly(false, false, false, false)
        assertThat(j.int("a")).isEqualTo(1)
    }

    @Test fun empty_array_where_object_expected() {
        val j = o("""{"x":[],"y":{"k":"v"},"z":"s"}""")
        assertThat(j.obj("x")).isNull()
        assertThat(j.obj("y")!!.str("k")).isEqualTo("v")
        assertThat(j.obj("z")).isNull()
        assertThat(j.arr("x")).isEmpty()
        assertThat(j.arr("y")).hasSize(1)
        assertThat(j.arr("missing")).isEmpty()
    }

    @Test fun lists() {
        val j = o("""{"a":[1,"2",null,""],"b":"3,4","c":5,"d":[],"e":null,"f":{"x":"fmt"}}""")
        assertThat(j.intList("a")).containsExactly(1, 2).inOrder()
        assertThat(j.intList("b")).containsExactly(3, 4).inOrder()
        assertThat(j.intList("c")).containsExactly(5)
        assertThat(j.intList("d")).isEmpty()
        assertThat(j.intList("e")).isEmpty()
        assertThat(j.stringList("f")).containsExactly("fmt")
    }
}
