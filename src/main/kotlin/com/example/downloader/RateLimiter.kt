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
class RateLimiter(val bytesPerSecond: Long) {

    init {
        require(bytesPerSecond > 0) { "bytesPerSecond must be > 0, got $bytesPerSecond" }
    }

    private val mutex = Mutex()
    /**
     * Earliest absolute time (System.nanoTime() reference) at which the next acquire is
     * eligible to proceed. 0 means unconstrained (limiter has never been exercised yet, or
     * has been idle long enough that the cursor is in the past).
     */
    private var earliestNextNanos: Long = 0L

    suspend fun acquire(bytes: Int) {
        require(bytes >= 0) { "bytes must be >= 0, got $bytes" }
        if (bytes == 0) return
        val waitNanos = mutex.withLock {
            val now = System.nanoTime()
            val base = maxOf(now, earliestNextNanos)
            val durationNanos = bytes.toLong() * NANOS_PER_SECOND / bytesPerSecond
            earliestNextNanos = base + durationNanos
            base - now
        }
        if (waitNanos > 0) delay(waitNanos / NANOS_PER_MILLI)
    }

    private companion object {
        const val NANOS_PER_SECOND: Long = 1_000_000_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
