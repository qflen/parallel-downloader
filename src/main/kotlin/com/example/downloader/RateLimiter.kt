package com.example.downloader

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Throughput-limiting gate. Each [acquire] call returns immediately when the configured
 * rate hasn't been exhausted, or suspends until enough tokens are available. Semantics are
 * leaky-bucket: the limiter doesn't accumulate burst credit during idle periods, so the
 * realized throughput converges to [bytesPerSecond] for any sustained traffic.
 *
 * Thread-safe. The acquire path holds a mutex only long enough to read-and-update the
 * "next-eligible-time" cursor; the actual wait is suspended outside the lock so other
 * coroutines aren't blocked from queueing.
 *
 * Used by [FileDownloader] when [DownloadConfig.rateLimitBytesPerSec] is non-null. The
 * gate sits in front of every write to the destination channel, so the bound applies to
 * total throughput across all chunks (not per-chunk).
 */
class RateLimiter(
    val bytesPerSecond: Long,
    private val nanoClock: () -> Long = System::nanoTime,
) {

    init {
        require(bytesPerSecond > 0) { "bytesPerSecond must be > 0, got $bytesPerSecond" }
    }

    private val mutex = Mutex()
    /**
     * Earliest absolute time (in the nanoClock reference frame) at which the next acquire is
     * eligible to proceed. 0 means unconstrained (limiter has never been exercised yet, or
     * has been idle long enough that the cursor is in the past).
     *
     * `internal` so tests can read post-state for invariant checks. The mutex protects writes;
     * external reads see a snapshot value.
     */
    @Volatile
    internal var earliestNextNanos: Long = 0L
        private set

    suspend fun acquire(bytes: Int) {
        val waitNanos = reserveWaitNanos(bytes)
        if (waitNanos > 0) delay(waitNanos / NANOS_PER_MILLI)
    }

    /**
     * Atomic reservation step: advances the cursor by `bytes / bytesPerSecond` and returns the
     * wait the caller should sleep before proceeding. Public `acquire` is a thin wrapper that
     * also performs the wait. Exposed as `internal` so concurrency tests (Lincheck) can drive
     * the locked region directly without involving `delay()`, which Lincheck's stress runner
     * cannot meaningfully cap.
     */
    internal suspend fun reserveWaitNanos(bytes: Int): Long {
        require(bytes >= 0) { "bytes must be >= 0, got $bytes" }
        if (bytes == 0) return 0L
        return mutex.withLock {
            val now = nanoClock()
            val base = maxOf(now, earliestNextNanos)
            val durationNanos = bytes.toLong() * NANOS_PER_SECOND / bytesPerSecond
            earliestNextNanos = base + durationNanos
            base - now
        }
    }

    private companion object {
        const val NANOS_PER_SECOND: Long = 1_000_000_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
