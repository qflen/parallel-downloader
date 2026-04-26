package com.example.downloader

import com.example.downloader.http.HttpRangeFetcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.URL
import java.nio.file.Path

/**
 * Pattern: **Template Method** — orchestrates the download lifecycle (validateInputs → probe →
 * chooseMode → preallocate → executeChunks → verify → finalize). Each step is a private method
 * with a single responsibility, making the lifecycle readable top-to-bottom in [download].
 *
 * Concurrency: chunk fan-out runs on a per-call `Dispatchers.IO.limitedParallelism(parallelism)`
 * view, scoped to the download. Two concurrent `download()` calls on the same instance get
 * independent pools — predictable per-call performance.
 *
 * Streaming: each chunk's bytes go straight from the HTTP response stream to a `FileChannel.write`
 * at the chunk's absolute file offset. Nothing is buffered to memory beyond a transport-size
 * `ByteBuffer` (default 64 KiB) per in-flight request.
 *
 * Filled in during Phase 3.
 */
class FileDownloader(
    @Suppress("unused") private val fetcher: HttpRangeFetcher,
    @Suppress("unused") private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Suppress("UnusedParameter") // implemented in phase 3
    suspend fun download(
        url: URL,
        destination: Path,
        config: DownloadConfig = DownloadConfig(),
    ): DownloadResult = TODO("phase 3")
}
