package com.example.downloader

import kotlinx.coroutines.runBlocking
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property tests for the leaky-bucket invariant of [RateLimiter]. The realized throughput
 * over any window where the limiter has had a chance to amortize its first-call grace must
 * not exceed the configured rate.
 *
 * Driven through [RateLimiter.reserveWaitNanos] with a frozen `nanoClock` so the test reads
 * the cursor as an absolute "next-eligible-time" coordinate, independent of real wall time.
 * Public `acquire(bytes)` would block in `delay()` for tens of seconds at the small rates
 * jqwik likes to generate; the locked reservation step is the entire concurrency-relevant
 * surface, and what `acquire` wraps.
 *
 * The leaky-bucket model: each call advances the cursor by `bytes * NANOS_PER_SECOND / rate`
 * nanoseconds. Over a sequence of N calls with sizes `b_i`, the cursor's final value is
 * `sum(b_i) * NANOS / rate`. Realized throughput across the full sequence is exactly the
 * configured rate (in floating-point math). Integer truncation in the limiter can leave
 * cursor advance slightly below the ideal for tiny `b_i / rate` ratios, which would mean
 * the limiter under-throttles - but never over-throttles. So the property is one-sided:
 * realized rate ≤ configured rate × (1 + epsilon), with epsilon for floating-point slack.
 */
class RateLimiterPropertyTest {

    @Property
    fun `realized rate over a request sequence never exceeds the configured rate`(
        @ForAll @LongRange(min = MIN_RATE, max = MAX_RATE) rate: Long,
        @ForAll @From("positiveByteSequences") bytesPerCall: List<Int>,
    ) {
        val limiter = RateLimiter(rate, nanoClock = { 0L })
        var totalBytes = 0L
        runBlocking {
            for (b in bytesPerCall) {
                limiter.reserveWaitNanos(b)
                totalBytes += b
            }
        }
        val cursorNanos = limiter.earliestNextNanos
        if (cursorNanos == 0L) return  // degenerate: every call had bytes/rate truncate to zero

        // Realized throughput across the entire request sequence, in bytes per second.
        val realizedBytesPerSec = totalBytes.toDouble() * NANOS_PER_SECOND / cursorNanos
        // Integer truncation in the limiter's `bytes * NANOS / rate` step can leave the cursor
        // slightly short of the ideal advance, which means realizedBytesPerSec can sit a hair
        // above `rate`. The slack scales with the number of truncations (one per call); we
        // bound it loosely with EPSILON.
        assertTrue(
            realizedBytesPerSec <= rate * (1.0 + EPSILON),
            "rate=$rate bytes=$totalBytes cursor=$cursorNanos realized=$realizedBytesPerSec",
        )
    }

    @Property
    fun `cursor is monotonic - it never moves backward across a request sequence`(
        @ForAll @LongRange(min = MIN_RATE, max = MAX_RATE) rate: Long,
        @ForAll @From("nonNegativeByteSequences") bytesPerCall: List<Int>,
    ) {
        val limiter = RateLimiter(rate, nanoClock = { 0L })
        var prevCursor = 0L
        runBlocking {
            for (b in bytesPerCall) {
                limiter.reserveWaitNanos(b)
                val current = limiter.earliestNextNanos
                assertTrue(
                    current >= prevCursor,
                    "cursor regressed: $prevCursor -> $current after acquire($b)",
                )
                prevCursor = current
            }
        }
    }

    @Property
    fun `reserveWaitNanos returns zero on the first call regardless of rate or bytes`(
        @ForAll @LongRange(min = MIN_RATE, max = MAX_RATE) rate: Long,
        @ForAll @IntRange(min = 0, max = MAX_BYTES) bytes: Int,
    ) {
        // First call: cursor is 0, now (frozen) is 0, so base = max(0, 0) = 0 and the
        // returned wait is base - now = 0. The leaky bucket gives one immediate-pass grace
        // for whoever is first; subsequent calls have to wait. This corner is what the
        // "burst window" in the property's name refers to.
        val limiter = RateLimiter(rate, nanoClock = { 0L })
        val wait = runBlocking { limiter.reserveWaitNanos(bytes) }
        assertTrue(wait == 0L, "first call wait must be zero, got $wait")
    }

    @Provide
    fun positiveByteSequences(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(1, MAX_BYTES).list().ofMinSize(1).ofMaxSize(MAX_REQUESTS)

    @Provide
    fun nonNegativeByteSequences(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, MAX_BYTES).list().ofMinSize(1).ofMaxSize(MAX_REQUESTS)

    private companion object {
        // Rate range chosen so that for any `bytes >= 1`, the per-call cursor advance
        // (`bytes * NANOS / rate`) is at least 1000 nanoseconds. Integer truncation in that
        // step is then bounded to <0.1% relative error per call - small enough that the
        // realized-rate property holds with a tight epsilon. Higher rates are exercised by
        // the Lincheck test and the existing real-time RateLimiterTest; the property test
        // stays in the regime where the leaky-bucket invariant is mathematically clean.
        const val MIN_RATE: Long = 1_000L              // 1 KB/s
        const val MAX_RATE: Long = 1_000_000L          // 1 MB/s
        const val MAX_BYTES: Int = 1_048_576           // 1 MiB per call
        const val MAX_REQUESTS: Int = 50
        const val NANOS_PER_SECOND: Long = 1_000_000_000L
        // Truncation slack across 50 calls at MAX_RATE / MIN_BYTES = 1 ns per call out of a
        // 1000 ns step → 0.1% per call → ~5% bound across worst-case inputs. Pad to 1% as
        // an epsilon and the property still catches gross violations (a limiter that fails
        // to throttle would land at orders of magnitude over).
        const val EPSILON: Double = 0.05
    }
}
