package rocks.urzahil.iptv.player.model

/** Reconnect backoff: 1, 2, 4, 8, 15 s (then 15 s), up to [maxAttempts]. */
class RetryPolicy(val maxAttempts: Int = DEFAULT_MAX) {
    private var attempts = 0

    val attempt: Int get() = attempts

    /** Delay before the next try, or null when the attempts are used up. Counts the attempt. */
    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        val d = DELAYS_MS[minOf(attempts, DELAYS_MS.lastIndex)]
        attempts++
        return d
    }

    fun reset() { attempts = 0 }

    companion object {
        const val DEFAULT_MAX = 5
        /** Playing this long without trouble resets the attempt counter. */
        const val STABLE_MS = 15_000L
        val DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        /** Failures worth retrying with the same request. */
        fun isRetryable(f: PlayerFailure): Boolean = when (f.kind) {
            FailureKind.NETWORK, FailureKind.HTTP_SERVER_ERROR, FailureKind.BEHIND_LIVE_WINDOW -> true
            else -> false
        }
    }
}
