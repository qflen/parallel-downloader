package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FailureMode
import com.example.downloader.fakes.FaultInjector
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import com.example.downloader.http.RetryingHttpRangeFetcher
import com.example.downloader.retry.ExponentialBackoffRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end: a server that returns 503 with `Retry-After: 1` once and then 200 must
 * complete via the retry path within a sane wall-clock window.
 */
class RetryAfterIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `503 with Retry-After 1 then 200 succeeds within roughly one second of the failure`() {
        // Use real-time runBlocking - we need wall-clock honoring of the Retry-After window
        // (runTest's virtual scheduler short-circuits delays).
        val payload = Bytes.deterministic(4_096, seed = 41)
        val attempts = AtomicInteger(0)
        TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(faultInjector = FaultInjector { method, range ->
                    // Fail the *first* ranged GET only; subsequent attempts succeed.
                    if (method == "GET" && range != null && attempts.getAndIncrement() == 0) {
                        FailureMode.Status(503, retryAfter = "1")
                    } else {
                        FailureMode.None
                    }
                }),
            )
            val fetcher = RetryingHttpRangeFetcher(
                JdkHttpRangeFetcher(),
                ExponentialBackoffRetry(
                    maxAttempts = 3,
                    // Initial backoff is much smaller than the server's hint to prove the
                    // retry waits the server's value, not the schedule's.
                    initialDelay = 10.milliseconds,
                    maxDelay = 5.seconds,
                    jitter = 0.0,
                ),
            )
            val downloader = FileDownloader(fetcher)
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 1
            }
            val elapsed = measureTimeMillis {
                runBlocking(Dispatchers.IO) {
                    val result = downloader.download(server.url("/file.bin"), dest, cfg)
                    assertIs<DownloadResult.Success>(result)
                }
            }
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
            // Retry-After=1 enforces >= 1s wait; the backoff schedule alone would have been
            // ~10 ms. A reasonable upper bound (network jitter, multiple chunks) is 5 s.
            assertTrue(
                elapsed >= LOWER_BOUND_MS - LOWER_BOUND_TOLERANCE_MS,
                "expected >= ~1s due to Retry-After, observed ${elapsed}ms",
            )
            assertTrue(
                elapsed < UPPER_BOUND_MS,
                "expected < 5s, observed ${elapsed}ms (regression: server-pin not capped?)",
            )
        }
    }

    private companion object {
        const val LOWER_BOUND_MS = 1000L
        // Tiny tolerance for measureTimeMillis quantization vs. the policy's delay.
        const val LOWER_BOUND_TOLERANCE_MS = 50L
        const val UPPER_BOUND_MS = 5_000L
    }
}
