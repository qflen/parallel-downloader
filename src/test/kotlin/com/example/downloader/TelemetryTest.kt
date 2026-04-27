package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FakeRangeFetcher
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import com.example.downloader.http.RetryingHttpRangeFetcher
import com.example.downloader.retry.ExponentialBackoffRetry
import com.example.downloader.retry.TransientFetchException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TelemetryTest {

    @TempDir
    lateinit var tempDir: Path

    private class RecordingTelemetry : Telemetry {
        val chunkCompletes = mutableListOf<Pair<Int, Long>>()
        @Volatile var downloadCompleteCalls = 0
        @Volatile var lastTotalBytes: Long = -1L
        @Volatile var lastElapsed: Duration = Duration.ZERO
        @Volatile var lastChunks: Int = -1
        @Volatile var lastRetries: Int = -1
        val transientFailures = AtomicInteger(0)

        @Synchronized
        override fun onChunkComplete(chunkIndex: Int, chunkBytes: Long) {
            chunkCompletes += chunkIndex to chunkBytes
        }

        override fun onDownloadComplete(totalBytes: Long, elapsed: Duration, chunks: Int, retries: Int) {
            downloadCompleteCalls++
            lastTotalBytes = totalBytes
            lastElapsed = elapsed
            lastChunks = chunks
            lastRetries = retries
        }

        override fun onTransientFailure(retryAttempt: Int) {
            transientFailures.incrementAndGet()
        }
    }

    @Test
    fun `default DownloadConfig telemetry is NoOp`() {
        assertEquals(Telemetry.NoOp, DownloadConfig().telemetry)
    }

    @Test
    fun `telemetry receives one onChunkComplete per chunk and one onDownloadComplete on success`() = runTest {
        val payload = Bytes.deterministic(16 * 1024, seed = 21)
        val telemetry = RecordingTelemetry()
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 4096L
                parallelism = 4
                this.telemetry = telemetry
            }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("out.bin"), cfg)
            assertIs<DownloadResult.Success>(result)
        }

        // 16 KiB / 4 KiB = 4 chunks. Each chunk fires once.
        assertEquals(4, telemetry.chunkCompletes.size, "expected 4 chunk-complete events")
        assertEquals(setOf(0, 1, 2, 3), telemetry.chunkCompletes.map { it.first }.toSet())
        // Every chunk's reported size must be > 0 and sum to total bytes.
        assertEquals(16L * 1024L, telemetry.chunkCompletes.sumOf { it.second })

        assertEquals(1, telemetry.downloadCompleteCalls)
        assertEquals(16L * 1024L, telemetry.lastTotalBytes)
        assertEquals(4, telemetry.lastChunks)
        assertEquals(0, telemetry.lastRetries, "no retries on a clean fetch")
        assertTrue(telemetry.lastElapsed > Duration.ZERO)
    }

    @Test
    fun `telemetry receives onTransientFailure once per retry absorbed by the decorator`() = runTest {
        val payload = Bytes.deterministic(1024, seed = 22)
        // First two fetchRange calls fail transient; third succeeds. Probe always succeeds.
        val fetchAttempts = AtomicInteger(0)
        val fakeUrl = java.net.URL("http://fake/host")
        val fake = FakeRangeFetcher(
            onProbe = { _ ->
                com.example.downloader.http.ProbeResult(
                    status = 200,
                    contentLength = payload.size.toLong(),
                    acceptsRanges = true,
                    finalUrl = fakeUrl,
                )
            },
            onFetchRange = { _, range, sink ->
                if (fetchAttempts.getAndIncrement() < 2) {
                    throw TransientFetchException("simulated transient")
                }
                val slice = payload.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
                sink.write(range.first, java.nio.ByteBuffer.wrap(slice))
            },
        )
        val fetcher = RetryingHttpRangeFetcher(
            fake,
            ExponentialBackoffRetry(
                maxAttempts = 5,
                initialDelay = 1.milliseconds,
                maxDelay = 5.milliseconds,
                jitter = 0.0,
            ),
        )
        val telemetry = RecordingTelemetry()
        val downloader = FileDownloader(fetcher)
        val cfg = downloadConfig {
            chunkSize = 1024L
            parallelism = 1
            this.telemetry = telemetry
        }
        val result = downloader.download(fakeUrl, tempDir.resolve("out.bin"), cfg)
        assertIs<DownloadResult.Success>(result)

        // Two transient failures absorbed before the third try succeeded.
        assertEquals(2, telemetry.transientFailures.get(), "expected 2 transient-failure events")
        assertEquals(2, telemetry.lastRetries, "retries in onDownloadComplete must equal observed events")
    }

    @Test
    fun `Telemetry NoOp ignores all events`() {
        // NoOp is a singleton; calling it must not throw and must not have observable state.
        Telemetry.NoOp.onChunkComplete(0, 1024L)
        Telemetry.NoOp.onDownloadComplete(1024L, Duration.ZERO, 1, 0)
        Telemetry.NoOp.onTransientFailure(1)
    }

    @Test
    fun `single-GET fallback fires onDownloadComplete with chunks 1`() = runTest {
        val payload = Bytes.deterministic(2048, seed = 23)
        val telemetry = RecordingTelemetry()
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, com.example.downloader.fakes.FileOptions(acceptsRanges = false))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { this.telemetry = telemetry }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("out.bin"), cfg)
            assertIs<DownloadResult.Success>(result)
        }
        assertEquals(1, telemetry.downloadCompleteCalls)
        assertEquals(1, telemetry.lastChunks, "single-GET path reports chunks=1")
        assertEquals(0, telemetry.chunkCompletes.size, "single-GET path doesn't fire per-chunk events")
    }

    @Test
    fun `download failure does not fire onDownloadComplete`() = runTest {
        val telemetry = RecordingTelemetry()
        TestHttpServer().use { server ->
            // No file served at /missing - probe fails with 404.
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { this.telemetry = telemetry }
            val result = downloader.download(server.url("/missing"), tempDir.resolve("out.bin"), cfg)
            assertIs<DownloadResult.HttpError>(result)
        }
        assertEquals(0, telemetry.downloadCompleteCalls, "no completion fire on failure")
        assertEquals(0, telemetry.chunkCompletes.size)
    }
}
