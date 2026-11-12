package rocks.urzahil.iptv.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.sync.FakeCipher

class SourceSecretsRedactionTest {
    private val redactor = Redactor()
    private val secrets = SourceSecrets(FakeCipher(), redactor)

    @Test fun pathCredentialsOfAnXtreamStyleM3uUrlAreMasked() {
        val url = "http://host.example:8080/alice77/s3cretPass/123.ts"
        // Without registration the Redactor's /live/ pattern does not match this shape.
        assertThat(redactor.redact("failed: $url")).contains("alice77")
        secrets.registerUrlSecrets(url)
        val out = redactor.redact("Unable to connect to $url (GET $url)")
        assertThat(out).doesNotContain("alice77")
        assertThat(out).doesNotContain("s3cretPass")
        assertThat(out).contains("host.example")
    }

    @Test fun liveStylePathAndEncodedValuesAreMasked() {
        secrets.registerUrlSecrets("http://h/live/us%40er99/pa%24%24word1/55.m3u8")
        val out = redactor.redact("http://h/live/us%40er99/pa%24%24word1/55.m3u8 and us@er99 / pa\$\$word1")
        assertThat(out).doesNotContain("us%40er99")
        assertThat(out).doesNotContain("us@er99")
        assertThat(out).doesNotContain("pa\$\$word1")
    }

    @Test fun queryValuesAreStillRegistered() {
        secrets.registerUrlSecrets("http://h/get.php?username=bob12&password=hunter22&type=m3u#frag")
        assertThat(redactor.redact("bob12 hunter22")).doesNotContain("hunter22")
    }

    @Test fun urlsWithoutSecretsAreIgnored() {
        secrets.registerUrlSecrets("http://h/stream/channel.m3u8")
        secrets.registerUrlSecrets("not a url")
        assertThat(redactor.redact("http://h/stream/channel.m3u8")).isEqualTo("http://h/stream/channel.m3u8")
    }
}
