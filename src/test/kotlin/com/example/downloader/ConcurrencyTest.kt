package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class ConcurrencyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parallelism actually happens - max concurrent in-flight requests greater than one`() = runTest {
        val payload = Bytes.deterministic(64 * 1024, seed = 1)
        TestHttpServer().use { server ->
            // Slow each chunk a little so we can observe overlap; without latency the chunks
            // can finish too quickly to show concurrency.
            server.serve("/file.bin", payload, FileOptions(latencyMillis = 30L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig {
                chunkSize = 4096L
                parallelism = 8
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertTrue(
                server.maxConcurrentRequests > 1,
                "expected > 1 concurrent requests, got ${server.maxConcurrentRequests}",
            )
        }
    }

    @Test
    fun `high-parallelism many-small-chunks - SHA matches and no race conditions`() = runTest {
        val payload = Bytes.deterministic(64 * 1024, seed = 2)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 64
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `two concurrent downloads on the same instance succeed independently`() = runTest {
        val payloadA = Bytes.deterministic(50_000, seed = 3)
        val payloadB = Bytes.deterministic(50_000, seed = 4)
        TestHttpServer().use { server ->
            server.serve("/a.bin", payloadA)
            server.serve("/b.bin", payloadB)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 4096L; parallelism = 4 }

            val destA = tempDir.resolve("a.bin")
            val destB = tempDir.resolve("b.bin")

            val results = listOf(
                async { downloader.download(server.url("/a.bin"), destA, cfg) },
                async { downloader.download(server.url("/b.bin"), destB, cfg) },
            ).awaitAll()

            assertIs<DownloadResult.Success>(results[0])
            assertIs<DownloadResult.Success>(results[1])
            assertEquals(Bytes.sha256(payloadA), Bytes.sha256(destA))
            assertEquals(Bytes.sha256(payloadB), Bytes.sha256(destB))
        }
    }

    @Test
    fun `max in-flight server requests never exceeds config parallelism - small parallelism`() = runTest {
        // 32 chunks at parallelism 4 - pre-Semaphore the dispatcher slot was released while
        // fetchRange suspended on the HTTP body, so peak in-flight requests grew unbounded.
        val payload = Bytes.deterministic(32 * 1024, seed = 6)
        TestHttpServer().use { server ->
            // Latency forces overlap so the bound is actually under stress.
            server.serve("/file.bin", payload, FileOptions(latencyMillis = 25L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 4 }

            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("out.bin"), cfg)
            assertIs<DownloadResult.Success>(result)
            server.assertConcurrencyBound(cfg.parallelism)
        }
    }

    @Test
    fun `max in-flight server requests never exceeds config parallelism - large parallelism`() = runTest {
        val payload = Bytes.deterministic(64 * 1024, seed = 8)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(latencyMillis = 25L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 16 }

            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("out.bin"), cfg)
            assertIs<DownloadResult.Success>(result)
            server.assertConcurrencyBound(cfg.parallelism)
        }
    }

    @Test
    fun `slow chunk does not block siblings - total time bounded by slow chunk plus minimal overhead`() = runTest {
        val payload = Bytes.deterministic(8 * 1024, seed = 5)
        TestHttpServer().use { server ->
            // Chunk size 1024 → 8 chunks. parallelism 8 → all chunks in flight at once.
            // Each chunk gets the same per-request latency (200ms). With parallelism 8, total
            // time should be approximately 200ms + overhead. Sequential would be 8 × 200 = 1600ms.
            server.serve("/file.bin", payload, FileOptions(latencyMillis = 200L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 8 }

            val elapsed = measureTime {
                val result = downloader.download(server.url("/file.bin"), dest, cfg)
                assertIs<DownloadResult.Success>(result)
            }
            // Generous upper bound - accounts for HEAD round-trip + 8 connection setups + 200ms
            // latency. Sequential would take 1600ms+; parallelism collapses to one latency-tick.
            assertTrue(
                elapsed < 1200.milliseconds,
                "parallelism failed to overlap latency: total $elapsed",
            )
        }
    }
}
