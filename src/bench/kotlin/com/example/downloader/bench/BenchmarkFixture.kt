@file:Suppress("MagicNumber") // bench fixtures use byte-count and chunk-size literals throughout.

package com.example.downloader.bench

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.JettyFileServer
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-trial JMH fixture: provisions a deterministic source file on disk, starts a Jetty
 * file server in front of it, and hands out unique destination paths so OS page cache and
 * dest-file state don't bleed across measurement invocations.
 *
 * One [BenchmarkFixture] is built per JMH trial (i.e. per @Param combination) and torn down
 * before the next trial starts. The file content is fixed across all trials of a given
 * benchmark class so trial-to-trial variance reflects download-path behavior only.
 */
internal class BenchmarkFixture(
    totalBytes: Long,
    advertiseAcceptRanges: Boolean = true,
    firstByteLatencyMillis: Long = 0L,
) : AutoCloseable {

    private val workDir: Path = Files.createTempDirectory("pdl-bench-")
    private val sourceFile: Path = workDir.resolve("source.bin")
    private val server: JettyFileServer
    val url: URL
    private val invocationId = AtomicLong()

    init {
        Bytes.writeDeterministicFile(sourceFile, totalBytes)
        server = JettyFileServer(
            sourceFile.parent,
            advertiseAcceptRanges = advertiseAcceptRanges,
            firstByteLatencyMillis = firstByteLatencyMillis,
        )
        url = server.url(sourceFile.fileName.toString())
    }

    /** A fresh destination path per JMH invocation. JMH assumes each invocation is independent. */
    fun nextDestination(): Path = workDir.resolve("dest-${invocationId.incrementAndGet()}.bin")

    override fun close() {
        runCatching { server.close() }
        runCatching {
            Files.walk(workDir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }

    companion object {
        /** Standard payload size for the throughput benchmarks. 100 MiB. */
        const val BENCH_PAYLOAD_BYTES: Long = 100L * 1024L * 1024L
    }
}
