package com.example.downloader.retry

import kotlin.time.Duration

/**
 * Strategy: exponential-backoff retry with full jitter.
 *
 * Filled in during Phase 3.
 */
class ExponentialBackoffRetry(
    private val maxAttempts: Int,
    private val initialDelay: Duration,
    private val maxDelay: Duration,
    private val multiplier: Double,
    private val jitter: Double,
) : RetryPolicy {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(initialDelay.isPositive()) { "initialDelay must be > 0" }
        require(maxDelay >= initialDelay) { "maxDelay must be >= initialDelay" }
        require(multiplier > 1.0) { "multiplier must be > 1.0, got $multiplier" }
        require(jitter in 0.0..1.0) { "jitter must be in [0.0, 1.0], got $jitter" }
    }

    override suspend fun <T> execute(block: suspend () -> T): T = TODO("phase 3")
}
