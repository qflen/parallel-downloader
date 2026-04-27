package com.example.downloader

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Pattern: **Observer** (third surface, sibling of [ProgressListener] and `Flow<ProgressEvent>`)
 * — but explicitly typed for the privacy boundary documented in
 * [PRIVACY.md](../../../../../../../PRIVACY.md) and `docs/DESIGN.md#telemetry-boundary`.
 *
 * The method signatures take counters, byte counts, chunk indices, and retry attempt numbers.
 * They deliberately don't take URL hosts, file paths, validator strings, or error message text,
 * so a `Telemetry` implementation can't accidentally re-identify the user. The interface itself
 * is the contract; implementations are free to send what they receive anywhere — but they only
 * receive non-identifying data in the first place.
 *
 * Default-implemented methods so an implementation only overrides what it cares about.
 *
 * Thread-safety: callbacks may fire concurrently from multiple chunks. Implementations should be
 * thread-safe (or at least atomic per-callback). The orchestrator does not throttle calls.
 */
interface Telemetry {

    /** Fired once per chunk, after the chunk's bytes have been written to disk. */
    fun onChunkComplete(chunkIndex: Int, chunkBytes: Long) {}

    /**
     * Fired exactly once per successful download.
     *
     * @param totalBytes bytes written to the destination.
     * @param elapsed wall-clock time from the start of `download()` to success.
     * @param chunks number of chunks the orchestrator scheduled. `1` for the single-GET
     *   fallback path, `0` for a zero-byte download.
     * @param retries number of transient-failure retries the retry decorator absorbed during
     *   this download (across all chunks and the probe). Counts each retry, not retries
     *   exhausted; matches what [onTransientFailure] is called with.
     */
    fun onDownloadComplete(totalBytes: Long, elapsed: Duration, chunks: Int, retries: Int) {}

    /**
     * Fired each time the retry decorator catches a [com.example.downloader.retry.TransientFetchException]
     * and is about to retry. [retryAttempt] is the 1-based attempt number that's about to start
     * (so the first retry, after one failure, fires this with `1`).
     */
    fun onTransientFailure(retryAttempt: Int) {}

    /** Default no-op telemetry; the [DownloadConfig] default. */
    object NoOp : Telemetry
}

/**
 * Coroutine-context element that carries the per-download [Telemetry] reference plus a
 * thread-safe transient-failure counter. The orchestrator installs it via `withContext`
 * around the whole download; the retry decorator reads it from `currentCoroutineContext()`
 * to fire [Telemetry.onTransientFailure] without an API change to fetcher constructors.
 *
 * `internal` because consumers should configure telemetry through [DownloadConfig], not by
 * adding context elements themselves.
 */
internal class TelemetryHandle(val telemetry: Telemetry) :
    AbstractCoroutineContextElement(TelemetryHandle) {

    private val transientFailureCount = AtomicInteger(0)

    val transientFailures: Int get() = transientFailureCount.get()

    fun fireTransientFailure(retryAttempt: Int) {
        transientFailureCount.incrementAndGet()
        telemetry.onTransientFailure(retryAttempt)
    }

    companion object Key : CoroutineContext.Key<TelemetryHandle>
}
