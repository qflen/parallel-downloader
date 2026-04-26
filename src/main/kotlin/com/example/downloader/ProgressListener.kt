package com.example.downloader

/**
 * Pattern: **Observer** — decouples I/O reporting (CLI stderr, GUI progress bar, test recorder)
 * from the download logic. The downloader has no opinion on how progress is presented.
 *
 * All callbacks are invoked on the I/O dispatcher, possibly concurrently from multiple
 * coroutines — implementations must be thread-safe (or at least atomic per-callback).
 * Implementations should be cheap; the orchestrator does not throttle calls.
 */
interface ProgressListener {

    /** Called once after a successful probe, before any chunk fetches start. */
    fun onStarted(total: Long) {}

    /**
     * Called whenever bytes are written to disk.
     *
     * @param downloaded total bytes written so far across all chunks
     * @param total total bytes to download (== `Content-Length`)
     */
    fun onProgress(downloaded: Long, total: Long) {}

    /** Called once per chunk after the chunk's bytes have been fully written. */
    fun onChunkComplete(chunkIndex: Int) {}

    /** Called exactly once, after the download has reached a terminal state (success or failure). */
    fun onFinished(result: DownloadResult) {}

    /** Default no-op listener — used by [DownloadConfig] when none is provided. */
    object NoOp : ProgressListener
}
