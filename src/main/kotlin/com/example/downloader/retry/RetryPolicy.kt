package com.example.downloader.retry

import kotlin.time.Duration

/**
 * Pattern: **Strategy** - pluggable retry behavior. The downloader and the
 * [com.example.downloader.http.RetryingHttpRangeFetcher] decorator both depend on this interface;
 * concrete strategies ([ExponentialBackoffRetry], [NoRetry]) are chosen at composition time.
 *
 * Implementations should retry on [TransientFetchException] and rethrow [NonRetryableFetchException],
 * [ValidatorMismatchException], and any other exception type without retry. This preserves the
 * visibility of programmer errors and deterministic HTTP failures (404, 416, 200-on-If-Range) that
 * retrying cannot help.
 */
interface RetryPolicy {
    /**
     * Execute [block], retrying on transient failures per the strategy's own schedule.
     *
     * @return the result of the (possibly retried) successful invocation.
     * @throws TransientFetchException when retries are exhausted; the last attempt's exception is rethrown.
     * @throws NonRetryableFetchException immediately on first occurrence - never retried.
     * @throws ValidatorMismatchException immediately on first occurrence - never retried; a
     *   server's `If-Range` rejection means the resource changed and a fresh attempt at the same
     *   chunk cannot recover.
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

/**
 * The server's `If-Range` evaluation rejected the validator we sent on a chunk GET and replied
 * 200 + full body instead of 206. Deterministic in the same sense as [NonRetryableFetchException]
 * (retrying the same request can't recover - the resource has moved on), but a sibling type rather
 * than a subtype because the orchestrator surfaces it as
 * [com.example.downloader.DownloadResult.ValidatorMismatch], not as `HttpError`. Any retry policy
 * MUST rethrow this without retry, exactly like `NonRetryableFetchException`.
 *
 * The [message] is parameterized so the construction site can include diagnostic context
 * (which range was being fetched). The validator strings themselves are deliberately NOT in
 * the message - they're server-controlled metadata and `Throwable.message` is the field that
 * default logging surfaces, so we keep validators on the typed [expected] / [observed] fields
 * where a caller has to opt in to read them.
 *
 * @property expected the validator the chunk GET carried in `If-Range`. Never null - the fetcher
 *   only constructs this exception when an `If-Range` header was sent.
 * @property observed the validator the 200 reply carried (the server's view of the current
 *   representation), or `null` when the response had neither `ETag` nor `Last-Modified`.
 */
class ValidatorMismatchException(
    message: String,
    val expected: String,
    val observed: String?,
) : Exception(message)
