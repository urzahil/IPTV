package rocks.urzahil.iptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.player.model.FailureKind
import rocks.urzahil.iptv.player.model.PlayerFailure
import rocks.urzahil.iptv.player.model.RetryPolicy

class RetryPolicyTest {
    @Test fun backoffSequenceThenExhausted() {
        val p = RetryPolicy(5)
        assertThat(generateSequence { p.nextDelayMs() }.toList()).containsExactly(1000L, 2000L, 4000L, 8000L, 15000L).inOrder()
        assertThat(p.nextDelayMs()).isNull()
    }

    @Test fun moreAttemptsRepeatTheCap() {
        val p = RetryPolicy(7)
        assertThat(generateSequence { p.nextDelayMs() }.toList().takeLast(2)).containsExactly(15000L, 15000L)
    }

    @Test fun zeroAttemptsDisablesRetry() {
        assertThat(RetryPolicy(0).nextDelayMs()).isNull()
    }

    @Test fun resetRestartsSequence() {
        val p = RetryPolicy(2)
        p.nextDelayMs(); p.nextDelayMs()
        p.reset()
        assertThat(p.nextDelayMs()).isEqualTo(1000L)
    }

    @Test fun retryableKinds() {
        assertThat(RetryPolicy.isRetryable(PlayerFailure(FailureKind.NETWORK))).isTrue()
        assertThat(RetryPolicy.isRetryable(PlayerFailure(FailureKind.HTTP_SERVER_ERROR))).isTrue()
        assertThat(RetryPolicy.isRetryable(PlayerFailure(FailureKind.HTTP_CLIENT_ERROR))).isFalse()
        assertThat(RetryPolicy.isRetryable(PlayerFailure(FailureKind.DECODER))).isFalse()
    }
}
