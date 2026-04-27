@file:Suppress("MagicNumber") // JMH parameter strings and the parallelism / chunk-size constants are literal by nature.

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
 * Compares the ranged-parallel path (server advertises `Accept-Ranges: bytes`) against the
 * single-GET fallback path (server omits the header, downloader's probe falls through to a
 * single full-body GET). Same 100 MiB payload, same Jetty server class, only the
 * `Accept-Ranges` advertisement differs.
 *
 * The fallback path is single-stream by definition, so this benchmark also acts as a sanity
 * check that ranged-parallel actually wins on a multi-MiB file.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, batchSize = 1)
@Measurement(iterations = 10, batchSize = 1)
@Fork(1)
@State(Scope.Benchmark)
open class RangedVsFallbackBenchmark {

    @Param("true", "false")
    var advertiseAcceptRanges: Boolean = true

    private lateinit var fixture: BenchmarkFixture
    private lateinit var downloader: FileDownloader

    @Setup(Level.Trial)
    open fun setupTrial() {
        fixture = BenchmarkFixture(
            totalBytes = BenchmarkFixture.BENCH_PAYLOAD_BYTES,
            advertiseAcceptRanges = advertiseAcceptRanges,
        )
        downloader = FileDownloader(JdkHttpRangeFetcher())
    }

    @TearDown(Level.Trial)
    open fun tearDownTrial() {
        fixture.close()
    }

    @Benchmark
    open fun download(): DownloadResult {
        val cfg = downloadConfig {
            chunkSize = 4.MiB
            parallelism = 8
        }
        val dest = fixture.nextDestination()
        val result = runBlocking { downloader.download(fixture.url, dest, cfg) }
        check(result is DownloadResult.Success) { "expected Success, got $result" }
        Files.deleteIfExists(dest)
        return result
    }
}
