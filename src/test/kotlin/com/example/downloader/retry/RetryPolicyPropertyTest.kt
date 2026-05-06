package com.example.downloader.retry

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.LongRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Property tests for [ExponentialBackoffRetry]'s effective-delay formula:
 *
 * ```
 * effectiveDelay = max(jitter(scheduledBackoff), retryAfter).coerceAtMost(maxDelay)
 * ```
 *
 * The realized delay between attempts must equal this for arbitrary input combinations - the
 * server's Retry-After hint must NOT be ignored, the policy's maxDelay must NOT be exceeded
 * even by a hostile Retry-After (e.g., `Retry-After: 86400`), and a null Retry-After must
 * fall back to the scheduled backoff.
 *
 * Driven through [kotlinx.coroutines.test.runTest] so `delay()` advances virtual time
 * deterministically; the realized elapsed time read off the test scheduler is the precise
 * value the policy passed to `delay()`. With jitter pinned to 0, the formula is
 * deterministic and we can assert `==`. The jitter property below verifies that with
 * non-zero jitter the realized delay lands inside the bounded span.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyPropertyTest {

    @Property
    fun `realized delay with no jitter is max of scheduled backoff and Retry-After clamped to maxDelay`(
        @ForAll @LongRange(min = 1, max = MAX_INITIAL) initialMs: Long,
        @ForAll @LongRange(min = 1, max = MAX_BOUND) maxMs: Long,
        @ForAll @LongRange(min = 0, max = MAX_RETRY_AFTER) retryAfterMs: Long,
    ) {
        if (maxMs < initialMs) return  // not a valid policy configuration; skip
        val policy = ExponentialBackoffRetry(
            maxAttempts = 2,
            initialDelay = initialMs.milliseconds,
            maxDelay = maxMs.milliseconds,
            multiplier = 2.0,
            jitter = 0.0,
        )
        val retryAfter = if (retryAfterMs > 0L) retryAfterMs.milliseconds else null
        var realized = -1L
        runTest {
            val before = currentTime
            try {
                policy.execute<Unit> {
                    throw TransientFetchException("err", retryAfter = retryAfter)
                }
            } catch (_: TransientFetchException) {
                // expected: maxAttempts=2, second attempt also throws because we always throw
            }
            realized = currentTime - before
        }
        // The first retry's scheduled backoff is `initialDelay`. With jitter=0, no perturbation.
        val expected = maxOf(initialMs, retryAfterMs).coerceAtMost(maxMs)
        assertEquals(expected, realized)
    }

    @Property
    fun `Retry-After larger than maxDelay always clamps to maxDelay`(
        @ForAll @LongRange(min = 1, max = MAX_INITIAL) initialMs: Long,
        @ForAll @LongRange(min = 1, max = MAX_BOUND) maxMs: Long,
        @ForAll @LongRange(min = 1, max = HOSTILE_RETRY_AFTER) hostileRetryAfterMs: Long,
    ) {
        if (maxMs < initialMs) return
        if (hostileRetryAfterMs <= maxMs) return  // we want the hostile case explicitly
        val policy = ExponentialBackoffRetry(
            maxAttempts = 2,
            initialDelay = initialMs.milliseconds,
            maxDelay = maxMs.milliseconds,
            jitter = 0.0,
        )
        var realized = -1L
        runTest {
            val before = currentTime
            try {
                policy.execute<Unit> {
                    throw TransientFetchException(
                        "rate limited",
                        retryAfter = hostileRetryAfterMs.milliseconds,
                    )
                }
            } catch (_: TransientFetchException) { /* expected after maxAttempts */ }
            realized = currentTime - before
        }
        // The whole point of the clamp: a misbehaving server returning a huge Retry-After
        // must NOT be able to pin the client past its own retry budget.
        assertEquals(maxMs, realized)
    }

    @Property
    fun `null Retry-After falls back to the scheduled backoff`(
        @ForAll @LongRange(min = 1, max = MAX_INITIAL) initialMs: Long,
        @ForAll @LongRange(min = 1, max = MAX_BOUND) maxMs: Long,
    ) {
        if (maxMs < initialMs) return
        val policy = ExponentialBackoffRetry(
            maxAttempts = 2,
            initialDelay = initialMs.milliseconds,
            maxDelay = maxMs.milliseconds,
            jitter = 0.0,
        )
        var realized = -1L
        runTest {
            val before = currentTime
            try {
                policy.execute<Unit> {
                    throw TransientFetchException("err", retryAfter = null)
                }
            } catch (_: TransientFetchException) { /* expected after maxAttempts */ }
            realized = currentTime - before
        }
        assertEquals(initialMs.coerceAtMost(maxMs), realized)
    }

    @Property
    fun `with non-zero jitter the realized delay lands inside the bounded span`(
        @ForAll @LongRange(min = 10, max = MAX_INITIAL) initialMs: Long,
        @ForAll @LongRange(min = 10, max = MAX_BOUND) maxMs: Long,
        @ForAll @LongRange(min = 1, max = JITTER_PERCENT_MAX) jitterPercent: Long,
        @ForAll @LongRange(min = 0, max = SEED_MAX) seed: Long,
    ) {
        if (maxMs < initialMs) return
        val jitter = jitterPercent.toDouble() / 100.0
        val policy = ExponentialBackoffRetry(
            maxAttempts = 2,
            initialDelay = initialMs.milliseconds,
            maxDelay = maxMs.milliseconds,
            jitter = jitter,
            random = Random(seed),
        )
        var realized = -1L
        runTest {
            val before = currentTime
            try {
                policy.execute<Unit> {
                    throw TransientFetchException("err", retryAfter = null)
                }
            } catch (_: TransientFetchException) { /* expected after maxAttempts */ }
            realized = currentTime - before
        }
        // jitterMs(initialMs) lands in [initialMs - initialMs*jitter, initialMs + initialMs*jitter].
        // The policy then `coerceAtMost(maxDelayMs)`. So lower bound: max(0, (1-j)*initialMs);
        // upper bound: min(maxMs, (1+j)*initialMs).
        val low = ((initialMs * (1.0 - jitter)).toLong()).coerceAtLeast(0L)
        val high = ((initialMs * (1.0 + jitter)).toLong()).coerceAtMost(maxMs)
        assertTrue(
            realized in low..high,
            "realized=$realized not in [$low, $high] (initial=$initialMs, jitter=$jitter, max=$maxMs)",
        )
    }

    private companion object {
        const val MAX_INITIAL: Long = 10_000L
        const val MAX_BOUND: Long = 60_000L
        const val MAX_RETRY_AFTER: Long = 30_000L
        const val HOSTILE_RETRY_AFTER: Long = 86_400_000L  // one day
        const val JITTER_PERCENT_MAX: Long = 100L
        const val SEED_MAX: Long = 1_000L
    }
}
