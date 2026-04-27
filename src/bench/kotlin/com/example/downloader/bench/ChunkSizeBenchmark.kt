@file:Suppress("MagicNumber") // JMH parameter strings and the parallelism constant are literal by nature.

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
 * Sweeps `chunkSize` over `[1, 4, 8, 16] MiB` at fixed `parallelism = 8` for a 100 MiB payload.
 * The expected curve: small chunks pay header / handshake overhead per chunk and saturate the
 * connection pool serializing many GETs; very large chunks reduce the number of usefully-
 * parallel chunks (here 100 MiB / 16 MiB = 7 chunks vs 100 MiB / 1 MiB = 100). Sweet spot is
 * usually in the middle.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, batchSize = 1)
@Measurement(iterations = 10, batchSize = 1)
@Fork(1)
@State(Scope.Benchmark)
open class ChunkSizeBenchmark {

    @Param("1", "4", "8", "16")
    var chunkSizeMib: Int = 0

    private lateinit var fixture: BenchmarkFixture
    private lateinit var downloader: FileDownloader

    @Setup(Level.Trial)
    open fun setupTrial() {
        fixture = BenchmarkFixture(totalBytes = BenchmarkFixture.BENCH_PAYLOAD_BYTES)
        downloader = FileDownloader(JdkHttpRangeFetcher())
    }

    @TearDown(Level.Trial)
    open fun tearDownTrial() {
        fixture.close()
    }

    @Benchmark
    open fun download(): DownloadResult {
        val cs = chunkSizeMib.MiB
        val cfg = downloadConfig {
            chunkSize = cs
            parallelism = 8
        }
        val dest = fixture.nextDestination()
        val result = runBlocking { downloader.download(fixture.url, dest, cfg) }
        check(result is DownloadResult.Success) { "expected Success, got $result" }
        Files.deleteIfExists(dest)
        return result
    }
}
