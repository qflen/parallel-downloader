package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.time.measureTime

class RateLimiterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `acquire of zero bytes returns immediately`() = runBlocking {
        val limiter = RateLimiter(bytesPerSecond = 1024L)
        val elapsed = measureTime { limiter.acquire(0) }
        assertTrue(elapsed.inWholeMilliseconds < 50, "zero-byte acquire should be free, took $elapsed")
    }

    @Test
    fun `serial acquire sums to roughly bytes div rate`() = runBlocking {
        val rate = 4 * 1024L  // 4 KiB/s
        val limiter = RateLimiter(rate)
        val totalBytes = 8 * 1024  // 8 KiB → ~2s
        val elapsed = measureTime {
            // Drain in 1 KiB chunks so we exercise the wait path repeatedly.
            repeat(8) { limiter.acquire(1024) }
        }
        // Realized time should be close to 2s (within the leaky-bucket model). Be generous on
        // the upper bound so dispatcher / scheduler noise doesn't flake.
        val expectedMs = totalBytes * 1000L / rate
        assertTrue(
            elapsed.inWholeMilliseconds in (expectedMs * 8 / 10)..(expectedMs * 15 / 10),
            "expected ~${expectedMs}ms, got ${elapsed.inWholeMilliseconds}ms",
        )
    }

    @Test
    fun `constructor rejects non-positive rate`() {
        assertThrows(IllegalArgumentException::class.java) { RateLimiter(0L) }
        assertThrows(IllegalArgumentException::class.java) { RateLimiter(-1L) }
    }

    @Test
    fun `acquire rejects negative byte counts`() = runBlocking {
        val limiter = RateLimiter(1024L)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limiter.acquire(-1) }
        }
    }

    @Test
    fun `download with rate-limit honors the configured cap within 30 percent`() = runTest {
        // 64 KiB at 64 KiB/s → ~1s. Generous tolerance because real-time scheduling on a
        // shared CI runner can introduce skew.
        val payload = Bytes.deterministic(64 * 1024, seed = 51)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 8 * 1024L
                parallelism = 4
                rateLimitBytesPerSec = 64 * 1024L
            }
            val dest = tempDir.resolve("out.bin")
            val elapsed = measureTime {
                val result = runBlocking { downloader.download(server.url("/file.bin"), dest, cfg) }
                assertIs<DownloadResult.Success>(result)
            }
            // Lower bound: must take at least ~700ms (some leeway for the first burst when
            // earliestNextNanos starts at 0).
            assertTrue(
                elapsed.inWholeMilliseconds >= 700,
                "rate limit not honored: $elapsed for 64 KiB at 64 KiB/s",
            )
            assertTrue(
                elapsed.inWholeMilliseconds <= 2500,
                "rate limit too aggressive: $elapsed for 64 KiB at 64 KiB/s",
            )
        }
    }

    @Test
    fun `default config has no rate limit`() {
        val cfg = DownloadConfig()
        assertTrue(cfg.rateLimitBytesPerSec == null, "default rate limit should be null")
    }

    @Test
    fun `rate limit applies to the total across chunks not per chunk`() = runTest {
        // 32 KiB file, 8 KiB chunks (4 chunks), parallelism 4. With rate=32 KiB/s the total
        // download time should be ~1s; if the limit were per-chunk it would be ~250ms.
        val payload = Bytes.deterministic(32 * 1024, seed = 52)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 8 * 1024L
                parallelism = 4
                rateLimitBytesPerSec = 32 * 1024L
            }
            val elapsed = measureTime {
                val result = runBlocking {
                    downloader.download(server.url("/file.bin"), tempDir.resolve("out.bin"), cfg)
                }
                assertIs<DownloadResult.Success>(result)
            }
            // Per-chunk rate would be ~250ms; total-rate should be ~1s. Assert closer to total.
            assertTrue(
                elapsed.inWholeMilliseconds >= 700,
                "rate limit appears per-chunk (too fast): $elapsed for 32 KiB at 32 KiB/s",
            )
        }
    }
}
