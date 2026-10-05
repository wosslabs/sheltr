package com.sheltr.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RetryPolicyTest {

    private val failed = SyncResult.Failed("connection reset")

    @Test
    fun delaysDoubleFromTheBase() {
        val policy = RetryPolicy(maxAttempts = 5, baseDelaySeconds = 30, maxDelaySeconds = 3_600)
        assertEquals(listOf<Long?>(30, 60, 120, 240), (1..4).map { policy.delayBeforeRetry(it, failed) })
    }

    @Test
    fun delayIsCappedAtTheMaximum() {
        val policy = RetryPolicy(maxAttempts = 100, baseDelaySeconds = 30, maxDelaySeconds = 100)
        assertEquals(60, policy.delayBeforeRetry(2, failed))
        assertEquals(100, policy.delayBeforeRetry(3, failed))
        assertEquals(100, policy.delayBeforeRetry(99, failed), "large attempt counts do not overflow")
    }

    @Test
    fun noRetryOnceAttemptsAreUsedUp() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertEquals(60, policy.delayBeforeRetry(2, failed))
        assertNull(policy.delayBeforeRetry(3, failed))
        assertNull(policy.delayBeforeRetry(4, failed))
    }

    @Test
    fun onlyFailuresAreRetried() {
        val policy = RetryPolicy()
        assertNull(policy.delayBeforeRetry(1, SyncResult.RejectedSignature), "a bad signature will not fix itself")
        assertNull(policy.delayBeforeRetry(1, SyncResult.UpToDate("v")))
        assertNull(policy.delayBeforeRetry(1, SyncResult.Updated("v", 1)))
    }

    @Test
    fun singleAttemptPolicyNeverRetries() {
        assertNull(RetryPolicy(maxAttempts = 1).delayBeforeRetry(1, failed))
    }

    @Test
    fun invalidPoliciesAreRejected() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(baseDelaySeconds = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(baseDelaySeconds = 60, maxDelaySeconds = 30) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy().delayBeforeRetry(0, failed) }
    }
}
