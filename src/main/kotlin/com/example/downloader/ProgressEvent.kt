package com.example.downloader

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.net.URL
import java.nio.file.Path

/**
 * A single observable point in the download lifecycle. Equivalent to a [ProgressListener]
 * callback, but materialized as a value so consumers can use the standard Flow operator
 * vocabulary (`take`, `filter`, `collect`, `first`, `runningFold`, etc.) instead of
 * implementing a callback interface.
 *
 * Sealed so an exhaustive `when` at the consumer can statically assert all cases are handled.
 */
sealed interface ProgressEvent {
    /** Emitted once after a successful probe, before any chunk fetches start. */
    data class Started(val total: Long) : ProgressEvent

    /** Emitted as bytes are written to disk. [total] is `-1` if the server didn't return Content-Length. */
    data class Progress(val downloaded: Long, val total: Long) : ProgressEvent

    /** Emitted once per chunk after the chunk's bytes have been fully written. */
    data class ChunkComplete(val chunkIndex: Int) : ProgressEvent

    /** Terminal event. Always the last one emitted, with the typed [DownloadResult]. */
    data class Finished(val result: DownloadResult) : ProgressEvent
}

/**
 * Flow-based alternative to passing a [ProgressListener] in [DownloadConfig]. Suspends until
 * the download completes (success or failure); emits events in real time as they happen.
 *
 * The flow always terminates with a [ProgressEvent.Finished] event carrying the
 * [DownloadResult]. If the parent coroutine is cancelled, the cancellation propagates to the
 * download (partial file deleted) and the flow throws `CancellationException` like any
 * structured-concurrency citizen.
 *
 * Implementation note: bridges the callback API to a [Flow] via an unbounded [Channel] so a
 * burst of events from a high-parallelism chunked download (thousands of chunks) doesn't drop
 * any. The download runs as a sibling coroutine inside [coroutineScope] so the flow can drain
 * the channel concurrently rather than waiting for the suspend [FileDownloader.download] call
 * to return.
 */
fun FileDownloader.downloadAsFlow(
    url: URL,
    destination: Path,
    config: DownloadConfig = DownloadConfig(),
): Flow<ProgressEvent> = flow {
    val events = Channel<ProgressEvent>(Channel.UNLIMITED)
    val flowListener = object : ProgressListener {
        override fun onStarted(total: Long) {
            events.trySend(ProgressEvent.Started(total))
        }
        override fun onProgress(downloaded: Long, total: Long) {
            events.trySend(ProgressEvent.Progress(downloaded, total))
        }
        override fun onChunkComplete(chunkIndex: Int) {
            events.trySend(ProgressEvent.ChunkComplete(chunkIndex))
        }
        override fun onFinished(result: DownloadResult) {
            events.trySend(ProgressEvent.Finished(result))
        }
    }
    val effectiveConfig = downloadConfig {
        chunkSize = config.chunkSize
        parallelism = config.parallelism
        progressListener = flowListener
        overwriteExisting = config.overwriteExisting
        resume = config.resume
        telemetry = config.telemetry
    }
    coroutineScope {
        val downloadJob = launch {
            try {
                download(url, destination, effectiveConfig)
            } finally {
                // Closing in finally guarantees the flow terminates even when the download
                // throws (cancellation, programmer error, etc.).
                events.close()
            }
        }
        for (event in events) {
            emit(event)
        }
        downloadJob.join()
    }
}
