package com.example.downloader

import java.nio.file.Path
import kotlin.time.Duration

/**
 * Outcome of a [FileDownloader.download] call.
 *
 * Pattern: **Sealed Result Type** — Kotlin's idiomatic equivalent of Either/Result. Expected
 * failure modes (404, length mismatch, cancellation) are visible in the type system and cannot
 * be silently ignored. [FileDownloader] throws only for programmer errors (negative chunk size,
 * blank URL, destination is a directory) — those are bugs in the caller, not transport
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
