package com.example.downloader.retry

import kotlin.time.Duration

/**
 * Pattern: **Strategy** - pluggable retry behavior. The downloader and the
 * [com.example.downloader.http.RetryingHttpRangeFetcher] decorator both depend on this interface;
 * concrete strategies ([ExponentialBackoffRetry], [NoRetry]) are chosen at composition time.
 *
 * Implementations should retry on [TransientFetchException] and rethrow [NonRetryableFetchException]
 * (and any other exception type) without retry. This preserves the visibility of programmer
 * errors and deterministic HTTP failures (404, 416) that retrying cannot help.
 */
interface RetryPolicy {
    /**
     * Execute [block], retrying on transient failures per the strategy's own schedule.
     *
     * @return the result of the (possibly retried) successful invocation.
     * @throws TransientFetchException when retries are exhausted; the last attempt's exception is rethrown.
     * @throws NonRetryableFetchException immediately on first occurrence - never retried.
     * @throws kotlin.coroutines.cancellation.CancellationException on cooperative cancellation -
     *   never wrapped, never retried.
     */
    suspend fun <T> execute(block: suspend () -> T): T
}

/**
 * Transient failure: the request *might* succeed on a fresh attempt. 5xx server errors,
 * 429 Too Many Requests, connection resets, premature EOF, mismatched `Content-Range`
 * headers all map to this.
 *
 * @property retryAfter when a 429 or 5xx response carried a parseable RFC 7231 §7.1.3
 *   `Retry-After` header (delta-seconds or HTTP-date), this is its value as a Duration.
 *   The retry strategy uses it as a lower bound on the next attempt's delay, capped at
 *   `maxDelay`, so a misbehaving server cannot pin the client past its own retry budget.
 *   Null when the header was absent, malformed, or negative.
 */
class TransientFetchException(
    message: String,
    cause: Throwable? = null,
    val retryAfter: Duration? = null,
) : Exception(message, cause)

/**
 * Deterministic failure: retrying will not help. 4xx, 416, malformed Content-Range surface
 * as this. Caller should propagate as a [com.example.downloader.DownloadResult.HttpError]
 * rather than retrying.
 *
 * [statusCode] is non-nullable on purpose: every construction site sets it, and the downstream
 * consumer (`FileDownloader`) uses it directly without defensive `?: 0` fallbacks.
 */
class NonRetryableFetchException(
    message: String,
    val statusCode: Int,
    cause: Throwable? = null,
) : Exception(message, cause)
