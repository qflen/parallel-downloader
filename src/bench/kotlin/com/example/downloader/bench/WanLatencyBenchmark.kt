@file:Suppress("MagicNumber") // JMH parameter strings, the chunk-size constant, and the WAN RTT are literal by nature.

package com.example.downloader.bench

import com.example.downloader.DownloadResult
import com.example.downloader.FileDownloader
import com.example.downloader.MiB
import com.example.downloader.downloadConfig
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Companion to [ParallelismScalingBenchmark] that adds 20 ms of artificial per-request
 * latency at the server. Loopback's parallelism-scaling table is misleading on its own
 * because the network has no latency to hide; this benchmark introduces the latency that
 * parallelism is designed to mask, and the curve flips: more in-flight chunks now win,
 * because each batch of `parallelism` requests pays a single 20 ms cost in parallel rather
 * than 20 ms per chunk in series.
 *
 * 20 ms is in the broadband-RTT range. The shape (more parallelism → faster) is robust
 * across reasonable RTT settings; only the absolute numbers shift.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, batchSize = 1)
@Measurement(iterations = 10, batchSize = 1)
@Fork(1)
@State(Scope.Benchmark)
open class WanLatencyBenchmark {

    @Param("1", "4", "8", "16", "32")
    var parallelism: Int = 0

    private lateinit var fixture: BenchmarkFixture
    private lateinit var downloader: FileDownloader

    @Setup(Level.Trial)
    open fun setupTrial() {
        fixture = BenchmarkFixture(
            totalBytes = BenchmarkFixture.BENCH_PAYLOAD_BYTES,
            firstByteLatencyMillis = WAN_RTT_MS,
        )
        downloader = FileDownloader(JdkHttpRangeFetcher())
    }

    @TearDown(Level.Trial)
    open fun tearDownTrial() {
        fixture.close()
    }

    @Benchmark
    open fun download(): DownloadResult {
        val parallel = parallelism
        val cfg = downloadConfig {
            chunkSize = 4.MiB
            parallelism = parallel
        }
        val dest = fixture.nextDestination()
        val result = runBlocking { downloader.download(fixture.url, dest, cfg) }
        check(result is DownloadResult.Success) { "expected Success, got $result" }
        Files.deleteIfExists(dest)
        return result
    }

    private companion object {
        /** Per-request server-side artificial latency. Broadband-RTT range. */
        const val WAN_RTT_MS: Long = 20L
    }
}
