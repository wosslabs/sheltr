package com.sheltr.shared.sync

/**
 * When to try a failed sync again. Pure: the scheduler (M7, A2, I2) asks it for a delay and does the waiting.
 * Delays grow exponentially from [baseDelaySeconds] (doubling per attempt) and never exceed [maxDelaySeconds].
 */
data class RetryPolicy(
    /** Total attempts including the first. */
    val maxAttempts: Int = 5,
    val baseDelaySeconds: Long = 30,
    val maxDelaySeconds: Long = 3_600,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelaySeconds > 0) { "baseDelaySeconds must be positive" }
        require(maxDelaySeconds >= baseDelaySeconds) { "maxDelaySeconds must be at least baseDelaySeconds" }
    }

    /**
     * Seconds to wait after failed attempt number [failedAttempts] (1 for the first attempt) before the next one,
     * or null if no attempt is left. A [result] that retrying cannot fix (a rejected signature, or success) also
     * returns null.
     */
    fun delayBeforeRetry(failedAttempts: Int, result: SyncResult): Long? {
        require(failedAttempts >= 1) { "failedAttempts starts at 1" }
        if (result !is SyncResult.Failed) return null
        if (failedAttempts >= maxAttempts) return null
        // base * 2^(n-1), capped. Stops doubling at the cap, so it cannot overflow.
        var delay = baseDelaySeconds
        repeat(failedAttempts - 1) {
            if (delay > maxDelaySeconds / 2) return maxDelaySeconds
            delay *= 2
        }
        return delay
    }
}
