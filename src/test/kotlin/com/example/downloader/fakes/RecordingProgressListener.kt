package com.example.downloader.fakes

import com.example.downloader.DownloadResult
import com.example.downloader.ProgressListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe recording listener used in tests to assert observable progress events.
 *
 * The downloader calls listener methods from multiple chunk coroutines concurrently, so the
 * fields here use thread-safe primitives. Tests inspect [chunkCompletions], [progressEvents],
 * [startedTotal], and [finishedResult] after `download()` returns.
 */
class RecordingProgressListener : ProgressListener {

    private val started = AtomicReference<Long?>()
    private val finished = AtomicReference<DownloadResult?>()
    private val lastDownloaded = AtomicLong(0L)
    private val lastTotal = AtomicLong(0L)
    private val onProgressCount = AtomicLong(0L)
    private val chunks = CopyOnWriteArrayList<Int>()

    val startedTotal: Long? get() = started.get()
    val finishedResult: DownloadResult? get() = finished.get()
    val chunkCompletions: List<Int> get() = chunks.toList()
    val progressEvents: Long get() = onProgressCount.get()
    val lastReportedDownloaded: Long get() = lastDownloaded.get()
    val lastReportedTotal: Long get() = lastTotal.get()

    override fun onStarted(total: Long) {
        started.set(total)
    }

    override fun onProgress(downloaded: Long, total: Long) {
        onProgressCount.incrementAndGet()
        lastDownloaded.set(downloaded)
        lastTotal.set(total)
    }

    override fun onChunkComplete(chunkIndex: Int) {
        chunks += chunkIndex
    }

    override fun onFinished(result: DownloadResult) {
        finished.set(result)
    }
}
