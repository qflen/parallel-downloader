package com.example.downloader

import com.example.downloader.http.HttpRangeFetcher
import com.example.downloader.http.ProbeResult
import com.example.downloader.http.RangeSink
import com.example.downloader.retry.NonRetryableFetchException
import com.example.downloader.retry.TransientFetchException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Pattern: **Template Method** - orchestrates the download lifecycle:
 *
 *   `validateInputs → probe → checkEnvironment → preallocate → executeChunks → verifyLength → finalize`
 *
 * Each step is a private method with a single responsibility, making the lifecycle readable
 * top-to-bottom in [download]. The choice between ranged-parallel and single-GET fallback at
 * the dispatch step is itself a runtime **Strategy** selection driven by [ProbeResult].
 *
 * Concurrency: chunk fan-out runs on a per-call `Dispatchers.IO.limitedParallelism(parallelism)`
 * view, scoped to the download via `coroutineScope { /* async */ }` (not `supervisorScope`) so
 * any chunk failure cancels its siblings cleanly. Two concurrent `download()` calls on the same
 * instance get independent pools - predictable per-call performance.
 *
 * Streaming: each chunk's bytes go straight from the HTTP response stream to a `FileChannel.write`
 * at the chunk's absolute file offset (NIO documents this as safe for concurrent writes at
 * distinct positions). Nothing is buffered to memory beyond a transport-size `ByteBuffer`
 * (default 64 KiB) per in-flight request.
 *
 * Cancellation: the suspend fun honors structured concurrency - on parent-job cancellation it
 * does cleanup (delete partial file, close channel) under [NonCancellable] and rethrows
 * `CancellationException`. The progress listener receives a synthetic
 * `onFinished(DownloadResult.Cancelled)` event before the rethrow, so listener-based UIs can
 * distinguish cancelled-vs-failed without observing the exception.
 *
 * Programmer errors (negative chunk size, blank URL host, destination is a directory) are
 * thrown as `IllegalArgumentException` rather than returned as a typed result - matches the
 * spec's "throw only for programmer errors" rule. A misbehaving server returning bytes outside
 * the requested chunk range is also surfaced as `IllegalArgumentException` (a paranoia bounds
 * check in [makeChunkSink]) since silently corrupting neighbor chunks is the worse failure mode.
 */
class FileDownloader(
    private val fetcher: HttpRangeFetcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    @Suppress("ReturnCount") // multi-return guard pattern is the clearest expression of fail-fast probe handling.
    suspend fun download(
        url: URL,
        destination: Path,
        config: DownloadConfig = DownloadConfig(),
    ): DownloadResult {
        validateInputs(url, destination)
        val started = TimeSource.Monotonic.markNow()

        val probe = when (val outcome = handleProbe(url)) {
            is ProbeOutcome.Failure -> return outcome.result.also(config.progressListener::onFinished)
            is ProbeOutcome.Success -> outcome.probe
        }

        checkEnvironment(destination)?.let { failure ->
            return failure.also(config.progressListener::onFinished)
        }

        config.progressListener.onStarted(probe.contentLength ?: UNKNOWN_LENGTH)

        val totalBytes = probe.contentLength
        val result: DownloadResult = try {
            when {
                totalBytes == 0L -> zeroByteDownload(destination, config, started)
                probe.acceptsRanges && totalBytes != null && totalBytes > 0L ->
                    rangedDownload(probe.finalUrl, destination, totalBytes, config, started)
                else ->
                    singleGetDownload(probe.finalUrl, destination, totalBytes, config, started)
            }
        } catch (cancellation: CancellationException) {
            // Synthesize a Cancelled event for the listener so it sees a terminal callback,
            // then rethrow to honor structured concurrency. The function's typed return path
            // never produces Cancelled; the listener is the only observer.
            config.progressListener.onFinished(DownloadResult.Cancelled)
            throw cancellation
        }
        config.progressListener.onFinished(result)
        return result
    }

    // ---------- step: input validation (programmer-error checks) ----------

    private fun validateInputs(url: URL, destination: Path) {
        require(url.protocol in SUPPORTED_PROTOCOLS) {
            "Unsupported URL protocol '${url.protocol}', expected one of $SUPPORTED_PROTOCOLS"
        }
        require(!url.host.isNullOrBlank()) { "URL has no host: $url" }
        require(!Files.isDirectory(destination)) { "Destination is a directory: $destination" }
    }

    // ---------- step: probe ----------

    private suspend fun handleProbe(url: URL): ProbeOutcome = try {
        val probe = fetcher.probe(url)
        if (probe.status !in SUCCESS_RANGE) {
            ProbeOutcome.Failure(
                DownloadResult.HttpError(probe.status, DownloadResult.HttpError.Phase.PROBE)
            )
        } else {
            ProbeOutcome.Success(probe)
        }
    } catch (e: NonRetryableFetchException) {
        ProbeOutcome.Failure(
            DownloadResult.HttpError(e.statusCode, DownloadResult.HttpError.Phase.PROBE)
        )
    } catch (e: TransientFetchException) {
        ProbeOutcome.Failure(DownloadResult.IoFailure(e))
    } catch (e: IOException) {
        ProbeOutcome.Failure(DownloadResult.IoFailure(e))
    }

    // ---------- step: environment ----------

    private fun checkEnvironment(destination: Path): DownloadResult.IoFailure? {
        val parent = destination.parent
        return if (parent != null && !Files.isDirectory(parent)) {
            // Per fork (2): fail clearly rather than auto-mkdir-p so typos surface early.
            DownloadResult.IoFailure(
                NoSuchFileException("Destination parent directory does not exist: $parent")
            )
        } else {
            null
        }
    }

    // ---------- mode: empty-file shortcut ----------

    private suspend fun zeroByteDownload(
        destination: Path,
        config: DownloadConfig,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): DownloadResult = try {
        runInterruptible(Dispatchers.IO) {
            openWriteChannel(destination, sizeHint = 0L, config.overwriteExisting).close()
        }
        DownloadResult.Success(destination, 0L, started.elapsedNow())
    } catch (e: IOException) {
        DownloadResult.IoFailure(e)
    }

    // ---------- mode: ranged-parallel download ----------

    private suspend fun rangedDownload(
        url: URL,
        destination: Path,
        totalBytes: Long,
        config: DownloadConfig,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): DownloadResult {
        val plan = planChunks(totalBytes, config.chunkSize)
        val channel = try {
            runInterruptible(Dispatchers.IO) {
                openWriteChannel(destination, sizeHint = totalBytes, config.overwriteExisting)
            }
        } catch (e: IOException) {
            return DownloadResult.IoFailure(e)
        }

        return runWithCleanup(channel, destination) {
            try {
                executeChunks(url, channel, plan, totalBytes, config)
                channel.close()
                verifyLength(destination, totalBytes)
                DownloadResult.Success(destination, totalBytes, started.elapsedNow())
            } catch (e: NonRetryableFetchException) {
                DownloadResult.HttpError(e.statusCode, DownloadResult.HttpError.Phase.CHUNK)
            } catch (e: TransientFetchException) {
                // Retries (if any) are exhausted. The exception preserves the underlying cause -
                // surface it as IoFailure rather than a synthetic HttpError(status=0).
                DownloadResult.IoFailure(e)
            } catch (e: LengthMismatchException) {
                DownloadResult.LengthMismatch(e.expected, e.actual)
            } catch (e: IOException) {
                DownloadResult.IoFailure(e)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun executeChunks(
        url: URL,
        channel: FileChannel,
        plan: List<Chunk>,
        totalBytes: Long,
        config: DownloadConfig,
    ): Unit = coroutineScope {
        val chunkDispatcher = ioDispatcher.limitedParallelism(config.parallelism)
        val downloadedBytes = AtomicLong(0L)
        plan.map { chunk ->
            async(chunkDispatcher) {
                fetcher.fetchRange(
                    url = url,
                    range = chunk.start..chunk.endInclusive,
                    sink = makeChunkSink(channel, chunk),
                )
                val newTotal = downloadedBytes.addAndGet(chunk.length)
                config.progressListener.onChunkComplete(chunk.index)
                config.progressListener.onProgress(newTotal, totalBytes)
            }
        }.awaitAll()
    }

    // ---------- mode: single-GET fallback ----------

    private suspend fun singleGetDownload(
        url: URL,
        destination: Path,
        expectedTotal: Long?,
        config: DownloadConfig,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): DownloadResult {
        val channel = try {
            runInterruptible(Dispatchers.IO) {
                // Don't preallocate for the single-GET fallback. The destination grows as bytes
                // are written sequentially from offset 0, so Files.size at the end reflects the
                // actual bytes received - necessary to detect a HEAD-vs-GET length mismatch.
                openWriteChannel(destination, sizeHint = 0L, config.overwriteExisting)
            }
        } catch (e: IOException) {
            return DownloadResult.IoFailure(e)
        }

        // maxPosition tracks the highest end-of-write offset seen so far. Idempotent under
        // retries: a retry restarts at offset 0 and overwrites; reported progress only ever
        // increases. (Chunked path uses chunk-completion accounting instead - also retry-safe.)
        val maxPosition = AtomicLong(0L)
        val sink = RangeSink { position, buffer ->
            val endPosition = position + buffer.remaining()
            runInterruptible(Dispatchers.IO) { writeFully(channel, position, buffer) }
            val newMax = maxPosition.updateAndGet { current -> maxOf(current, endPosition) }
            config.progressListener.onProgress(
                downloaded = newMax,
                total = expectedTotal ?: UNKNOWN_LENGTH,
            )
        }

        return runWithCleanup(channel, destination) {
            try {
                fetcher.fetchAll(url, sink)
                channel.close()
                val actual = Files.size(destination)
                if (expectedTotal != null && expectedTotal > 0L && actual != expectedTotal) {
                    throw LengthMismatchException(expectedTotal, actual)
                }
                DownloadResult.Success(destination, actual, started.elapsedNow())
            } catch (e: NonRetryableFetchException) {
                DownloadResult.HttpError(e.statusCode, DownloadResult.HttpError.Phase.CHUNK)
            } catch (e: TransientFetchException) {
                // Retries (if any) are exhausted. The exception preserves the underlying cause -
                // surface it as IoFailure rather than a synthetic HttpError(status=0).
                DownloadResult.IoFailure(e)
            } catch (e: LengthMismatchException) {
                DownloadResult.LengthMismatch(e.expected, e.actual)
            } catch (e: IOException) {
                DownloadResult.IoFailure(e)
            }
        }
    }

    // ---------- cleanup ----------

    private suspend fun runWithCleanup(
        channel: FileChannel,
        destination: Path,
        block: suspend () -> DownloadResult,
    ): DownloadResult {
        // try/finally + flag pattern: avoids a broad `catch (e: Throwable)` while still
        // guaranteeing cleanup for every failure mode (typed result, cancellation, programmer
        // error). On thrown failure, finally runs and then the exception propagates naturally.
        var keepFile = false
        try {
            val result = block()
            keepFile = result is DownloadResult.Success
            return result
        } finally {
            if (keepFile) {
                // Idempotent: closing twice is a no-op. The block may already have closed it.
                runCatching { channel.close() }
            } else {
                cleanupOnFailure(channel, destination)
            }
        }
    }

    private suspend fun cleanupOnFailure(channel: FileChannel, destination: Path) {
        withContext(NonCancellable) {
            runCatching { channel.close() }
            runCatching { Files.deleteIfExists(destination) }
        }
    }

    private companion object {
        val SUPPORTED_PROTOCOLS = listOf("http", "https")
        val SUCCESS_RANGE = 200..299
        const val UNKNOWN_LENGTH: Long = -1L
    }

    private sealed interface ProbeOutcome {
        data class Success(val probe: ProbeResult) : ProbeOutcome
        data class Failure(val result: DownloadResult) : ProbeOutcome
    }
}

// ---------- file-level helpers - pure I/O plumbing extracted from the orchestrator class ----------

private fun openWriteChannel(
    destination: Path,
    sizeHint: Long,
    overwriteExisting: Boolean,
): FileChannel {
    val channel = if (overwriteExisting) {
        FileChannel.open(destination, WRITE, CREATE, TRUNCATE_EXISTING)
    } else {
        // CREATE_NEW throws FileAlreadyExistsException when the path exists.
        FileChannel.open(destination, WRITE, CREATE_NEW)
    }
    if (sizeHint > 0L) {
        // try/finally + flag avoids a broad `catch (e: Throwable)` while still guaranteeing
        // the channel is closed on any pre-extend failure (so a half-opened FD can't leak).
        var ok = false
        try {
            // Pre-extend by writing one byte at the last position. Subsequent writes at chunk
            // offsets fill in the rest. On most filesystems this allocates extents up front,
            // reducing fragmentation across the parallel writes.
            channel.position(sizeHint - 1)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
            ok = true
        } finally {
            if (!ok) channel.close()
        }
    }
    return channel
}

private fun verifyLength(destination: Path, expected: Long) {
    val actual = Files.size(destination)
    if (actual != expected) {
        throw LengthMismatchException(expected, actual)
    }
}

private fun writeFully(channel: FileChannel, startPosition: Long, buffer: ByteBuffer) {
    var p = startPosition
    while (buffer.hasRemaining()) {
        val written = channel.write(buffer, p)
        check(written >= 0) { "FileChannel.write returned $written" }
        p += written
    }
}

/**
 * Builds a [RangeSink] that writes into [channel] at the chunk's absolute offset. The bounds
 * checks are paranoia against a misbehaving server / fetcher returning bytes outside the
 * requested range - caught at the boundary rather than corrupting neighbor chunks silently.
 *
 * Visibility is `internal` so the boundary checks can be unit-tested directly without needing
 * a fault-injecting fake fetcher (the validation in [com.example.downloader.http.JdkHttpRangeFetcher]
 * rejects out-of-range bodies before they reach the sink in the real integration path).
 */
internal fun makeChunkSink(channel: FileChannel, chunk: Chunk): RangeSink = RangeSink { position, buffer ->
    val len = buffer.remaining()
    require(position >= chunk.start) {
        "write position $position before chunk start ${chunk.start}"
    }
    require(position + len - 1 <= chunk.endInclusive) {
        "write extends beyond chunk end: pos=$position len=$len chunk=${chunk.start}..${chunk.endInclusive}"
    }
    runInterruptible(Dispatchers.IO) {
        writeFully(channel, position, buffer)
    }
}

private class LengthMismatchException(val expected: Long, val actual: Long) :
    Exception("expected $expected bytes, got $actual")
