package com.example.downloader.retry

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Strategy: exponential-backoff retry with bounded jitter.
 *
 * Retries only on [TransientFetchException]; rethrows [NonRetryableFetchException] and any other
 * exception (including `CancellationException`) on first occurrence so cancellation and
 * programmer errors propagate without delay.
 *
 * @param maxAttempts total attempts including the first; must be >= 1. `maxAttempts == 1` ≡ NoRetry.
 * @param initialDelay delay before the second attempt; doubled (or scaled by [multiplier]) thereafter.
 * @param maxDelay caps the delay between attempts. Once reached, the delay stays flat.
 * @param multiplier per-attempt multiplier; must be > 1.0 (otherwise it's not exponential).
 * @param jitter fractional jitter in `[0.0, 1.0]`; the realized delay is in `[d * (1-jitter), d * (1+jitter)]`.
 *   Reduces thundering-herd effects when many clients retry against the same upstream.
 */
class ExponentialBackoffRetry(
    private val maxAttempts: Int,
    private val initialDelay: Duration,
    private val maxDelay: Duration,
    private val multiplier: Double = DEFAULT_MULTIPLIER,
    private val jitter: Double = DEFAULT_JITTER,
    private val random: Random = Random.Default,
) : RetryPolicy {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(initialDelay.isPositive()) { "initialDelay must be > 0, got $initialDelay" }
        require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must be >= initialDelay ($initialDelay)" }
        require(multiplier > 1.0) { "multiplier must be > 1.0, got $multiplier" }
        require(jitter in 0.0..1.0) { "jitter must be in [0.0, 1.0], got $jitter" }
    }

    override suspend fun <T> execute(block: suspend () -> T): T {
        var attempt = 0
        var nextDelayMs = initialDelay.inWholeMilliseconds
        val maxDelayMs = maxDelay.inWholeMilliseconds
        while (true) {
            try {
                return block()
            } catch (transient: TransientFetchException) {
                attempt++
                if (attempt >= maxAttempts) throw transient
            }
            // CancellationException and NonRetryableFetchException propagate - never caught above.
            delay(jitterMs(nextDelayMs))
            nextDelayMs = (nextDelayMs.toDouble() * multiplier).toLong().coerceAtMost(maxDelayMs)
        }
    }

    private fun jitterMs(baseDelayMs: Long): Long {
        val span = if (jitter > 0.0) (baseDelayMs * jitter).toLong() else 0L
        return if (span <= 0L) {
            baseDelayMs
        } else {
            // Random.nextLong(low, high) is half-open at the top, so +1 to make it symmetric.
            (baseDelayMs + random.nextLong(-span, span + 1)).coerceAtLeast(0L)
        }
    }

    companion object {
        const val DEFAULT_MULTIPLIER: Double = 2.0
        const val DEFAULT_JITTER: Double = 0.1
    }
}
