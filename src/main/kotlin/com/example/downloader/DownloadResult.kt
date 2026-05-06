package com.example.downloader

import java.nio.file.Path
import kotlin.time.Duration

/**
 * Outcome of a [FileDownloader.download] call.
 *
 * Pattern: **Sealed Result Type** - Kotlin's idiomatic equivalent of Either/Result. Expected
 * failure modes (404, length mismatch, cancellation) are visible in the type system and cannot
 * be silently ignored. [FileDownloader] throws only for programmer errors (negative chunk size,
 * blank URL, destination is a directory) - those are bugs in the caller, not transport
 * conditions worth pattern-matching at every call site.
 */
sealed interface DownloadResult {

    /** The file was downloaded successfully. */
    data class Success(val path: Path, val bytes: Long, val elapsed: Duration) : DownloadResult

    /**
     * The server returned a non-success status code we cannot recover from.
     * [phase] distinguishes a probe-time failure (HEAD 404) from a chunk-time failure (a GET 503
     * after retries are exhausted) so callers can tell whether *any* bytes were ever attempted.
     */
    data class HttpError(val status: Int, val phase: Phase) : DownloadResult {
        enum class Phase { PROBE, CHUNK }
    }

    /**
     * The server's `If-Range` evaluation rejected the validator we sent on a chunk GET and
     * replied 200 + full body instead of 206. The resource changed mid-download (or a Vary-keyed
     * intermediary served a different representation between the probe and the chunk request);
     * either way splicing the new body's bytes at a chunk offset would corrupt the file, so the
     * download fails loudly rather than silently producing a Frankenstein file.
     *
     * Distinct from `HttpError(200, CHUNK)`, which is reserved for the *server-bug* case where a
     * 200 came back on a ranged GET that did NOT carry an `If-Range` header. The two look
     * identical at the wire-status level but are deterministic vs. recoverable in different ways:
     * a validator mismatch can never be retried into success on the same destination, while
     * the ignored-Range case is a server defect a caller may want to report or work around.
     *
     * @property expected the validator we sent in `If-Range` (the value the probe yielded -
     *   ETag preferred, falling back to `Last-Modified`).
     * @property observed the validator the 200 reply carried (`ETag`, falling back to
     *   `Last-Modified`), or `null` when the server didn't include either header on its
     *   200 response.
     */
    data class ValidatorMismatch(val expected: String, val observed: String?) : DownloadResult

    /**
     * The server doesn't support range requests AND fallback to single-GET is disabled.
     * With the default config the downloader transparently falls back, so this is reserved for
     * forward compatibility / a future strict-mode flag.
     */
    data object RangeNotSupported : DownloadResult

    /**
     * The number of bytes written to disk doesn't match what the server promised in
     * `Content-Length`. The partial file has been deleted.
     */
    data class LengthMismatch(val expected: Long, val actual: Long) : DownloadResult

    /** The download was cancelled (parent coroutine cancelled). The partial file has been deleted. */
    data object Cancelled : DownloadResult

    /**
     * A non-HTTP I/O failure (disk full, permission denied, etc.). The partial file has been
     * deleted where possible. [cause] is the underlying [Throwable] for diagnostics.
     */
    data class IoFailure(val cause: Throwable) : DownloadResult
}
