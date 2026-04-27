package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FailureMode
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import com.example.downloader.http.RetryingHttpRangeFetcher
import com.example.downloader.retry.ExponentialBackoffRetry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class EdgeCaseTest {

    @TempDir
    lateinit var tempDir: Path

    // --------------------------------------------------------------------------------------
    // Range support: missing / disabled
    // --------------------------------------------------------------------------------------

    @Test
    fun `server without Accept-Ranges falls back to single GET (one body request, no Range header)`() = runTest {
        val payload = Bytes.deterministic(2000, seed = 1)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(acceptsRanges = false))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
            val gets = server.requests.filter { it.method == "GET" }
            assertEquals(1, gets.size, "fallback should send exactly one GET; sent ${gets.size}")
            assertNull(gets.first().rangeHeader, "fallback GET must not carry a Range header")
        }
    }

    @Test
    fun `server returning 200 to a Range request (ignored Range) is a chunk-phase HttpError`() = runTest {
        // Server advertises Accept-Ranges but ignores the Range header on GET, returning 200 + full body.
        // Once we've committed to ranged mode, this is a non-retryable protocol violation.
        val payload = Bytes.deterministic(5000, seed = 2)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(acceptsRanges = true, ignoreRangeHeader = true))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig { chunkSize = 1024L }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(200, result.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, result.phase)
            assertFalse(Files.exists(dest), "partial file should not remain on disk")
        }
    }

    // --------------------------------------------------------------------------------------
    // Status code mapping
    // --------------------------------------------------------------------------------------

    @Test
    fun `404 from probe surfaces as PROBE-phase HttpError without writing any bytes`() = runTest {
        TestHttpServer().use { server ->
            // No serve() - root handler returns 404 for unknown paths.
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/missing.bin"), dest)
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(404, result.status)
            assertEquals(DownloadResult.HttpError.Phase.PROBE, result.phase)
            assertFalse(Files.exists(dest))
        }
    }

    @Test
    fun `416 Range Not Satisfiable from probe surfaces as PROBE-phase HttpError`() = runTest {
        val payload = Bytes.deterministic(1000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(statusOverride = 416))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"))
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(416, result.status)
        }
    }

    @Test
    fun `503 from chunk GET with retries exhausted leaves no partial file`() = runTest {
        val payload = Bytes.deterministic(3000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                faultInjector = { method, _ ->
                    if (method == "GET") FailureMode.Status(503) else FailureMode.None
                }
            ))
            val fetcher = RetryingHttpRangeFetcher(
                JdkHttpRangeFetcher(),
                ExponentialBackoffRetry(
                    maxAttempts = 3,
                    initialDelay = 1.milliseconds,
                    maxDelay = 5.milliseconds,
                    jitter = 0.0,
                ),
            )
            val downloader = FileDownloader(fetcher)
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 1 }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.IoFailure>(result)
            assertFalse(Files.exists(dest))
        }
    }

    @Test
    fun `403 Forbidden from probe surfaces as PROBE-phase HttpError`() = runTest {
        TestHttpServer().use { server ->
            server.serve("/file.bin", Bytes.deterministic(100), FileOptions(statusOverride = 403))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"))
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(403, result.status)
            assertEquals(DownloadResult.HttpError.Phase.PROBE, result.phase)
        }
    }

    @Test
    fun `500 from probe surfaces (after retries exhausted) - TransientFetchException`() = runTest {
        TestHttpServer().use { server ->
            server.serve("/file.bin", Bytes.deterministic(100), FileOptions(statusOverride = 500))
            val fetcher = RetryingHttpRangeFetcher(
                JdkHttpRangeFetcher(),
                ExponentialBackoffRetry(
                    maxAttempts = 2,
                    initialDelay = 1.milliseconds,
                    maxDelay = 2.milliseconds,
                    jitter = 0.0,
                ),
            )
            val downloader = FileDownloader(fetcher)
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"))
            // 500 is transient → after retries exhausted surfaces as IoFailure (probe path).
            assertIs<DownloadResult.IoFailure>(result)
        }
    }

    // --------------------------------------------------------------------------------------
    // Content-Range / body integrity
    // --------------------------------------------------------------------------------------

    @Test
    fun `wrong Content-Range header is rejected and surfaces as IoFailure (transient, no retries)`() = runTest {
        val payload = Bytes.deterministic(3000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(contentRangeOverride = "bytes 0-9/3000"))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 1 }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"), cfg)
            assertIs<DownloadResult.IoFailure>(result)
        }
    }

    @Test
    fun `truncated chunk body succeeds on retry`() = runTest {
        val payload = Bytes.deterministic(3000)
        val firstFailed = AtomicInteger(0)
        TestHttpServer().use { server ->
            // Disconnect mid-stream on the first ranged GET only; subsequent requests succeed.
            server.serve("/file.bin", payload, FileOptions(
                faultInjector = { method, range ->
                    if (method == "GET" && range != null && firstFailed.getAndIncrement() == 0) {
                        FailureMode.Disconnect
                    } else {
                        FailureMode.None
                    }
                },
            ))
            val fetcher = RetryingHttpRangeFetcher(
                JdkHttpRangeFetcher(),
                ExponentialBackoffRetry(
                    maxAttempts = 3,
                    initialDelay = 1.milliseconds,
                    maxDelay = 5.milliseconds,
                    jitter = 0.0,
                ),
            )
            val downloader = FileDownloader(fetcher)
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 1 }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `length mismatch in single-GET fallback surfaces as LengthMismatch and deletes partial file`() = runTest {
        val actual = Bytes.deterministic(500)
        TestHttpServer().use { server ->
            // HEAD lies (says 1000), no range support, real body is 500.
            server.serve("/file.bin", actual, FileOptions(
                acceptsRanges = false,
                headContentLengthOverride = 1000L,
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.LengthMismatch>(result)
            assertEquals(1000L, result.expected)
            assertEquals(500L, result.actual)
            assertFalse(Files.exists(dest))
        }
    }

    // --------------------------------------------------------------------------------------
    // Boundary input validation (programmer errors)
    // --------------------------------------------------------------------------------------

    @Test
    fun `URL with non-http protocol throws IllegalArgumentException at boundary`() = runTest {
        val downloader = FileDownloader(JdkHttpRangeFetcher())
        assertThrows<IllegalArgumentException> {
            downloader.download(URL("ftp://example.com/x"), tempDir.resolve("o.bin"))
        }
    }

    @Test
    fun `destination is a directory throws IllegalArgumentException`() = runTest {
        TestHttpServer().use { server ->
            server.serve("/file.bin", Bytes.deterministic(100))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dirDest = Files.createDirectories(tempDir.resolve("subdir"))
            assertThrows<IllegalArgumentException> {
                downloader.download(server.url("/file.bin"), dirDest)
            }
        }
    }

    @Test
    fun `destination's parent directory missing returns IoFailure (per fork 2)`() = runTest {
        TestHttpServer().use { server ->
            server.serve("/file.bin", Bytes.deterministic(100))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("nonexistent_parent/out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.IoFailure>(result)
            assertTrue(result.cause is java.nio.file.NoSuchFileException)
        }
    }

    // --------------------------------------------------------------------------------------
    // Listener contract
    // --------------------------------------------------------------------------------------

    @Test
    fun `retry decorator on single-GET fallback retries 5xx and succeeds`() = runTest {
        val payload = Bytes.deterministic(1500, seed = 11)
        val attempt = AtomicInteger(0)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                acceptsRanges = false,
                faultInjector = { method, _ ->
                    if (method == "GET" && attempt.getAndIncrement() == 0) {
                        FailureMode.Status(503)
                    } else {
                        FailureMode.None
                    }
                },
            ))
            val fetcher = RetryingHttpRangeFetcher(
                JdkHttpRangeFetcher(),
                ExponentialBackoffRetry(
                    maxAttempts = 3,
                    initialDelay = 1.milliseconds,
                    maxDelay = 5.milliseconds,
                    jitter = 0.0,
                ),
            )
            val downloader = FileDownloader(fetcher)
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `chunk GET returning unexpected 304 surfaces as CHUNK-phase HttpError (else branch)`() = runTest {
        val payload = Bytes.deterministic(1000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                faultInjector = { method, _ ->
                    if (method == "GET") FailureMode.Status(304) else FailureMode.None
                },
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 512L }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"), cfg)
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(304, result.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, result.phase)
        }
    }

    @Test
    fun `HttpError Phase enum exposes both PROBE and CHUNK values`() {
        // Cheap coverage for the synthetic Phase.values() / Phase.valueOf() so the enum's
        // static utilities don't show up as dead code.
        assertEquals(2, DownloadResult.HttpError.Phase.entries.size)
        assertEquals(DownloadResult.HttpError.Phase.PROBE, DownloadResult.HttpError.Phase.valueOf("PROBE"))
        assertEquals(DownloadResult.HttpError.Phase.CHUNK, DownloadResult.HttpError.Phase.valueOf("CHUNK"))
    }

    @Test
    fun `chunk GET returning 416 surfaces as CHUNK-phase HttpError`() = runTest {
        val payload = Bytes.deterministic(2000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                faultInjector = { method, _ ->
                    if (method == "GET") FailureMode.Status(416) else FailureMode.None
                },
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"), cfg)
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(416, result.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, result.phase)
        }
    }

    @Test
    fun `chunk GET returning 403 surfaces as CHUNK-phase HttpError`() = runTest {
        val payload = Bytes.deterministic(2000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                faultInjector = { method, _ ->
                    if (method == "GET") FailureMode.Status(403) else FailureMode.None
                },
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"), cfg)
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(403, result.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, result.phase)
        }
    }

    @Test
    fun `single-GET fallback failing with 404 surfaces as CHUNK-phase HttpError`() = runTest {
        val payload = Bytes.deterministic(1000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                acceptsRanges = false,
                faultInjector = { method, _ ->
                    if (method == "GET") FailureMode.Status(404) else FailureMode.None
                },
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"))
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(404, result.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, result.phase)
        }
    }

    @Test
    fun `single-GET fallback failing with 503 surfaces as IoFailure (transient, no retry)`() = runTest {
        val payload = Bytes.deterministic(1000)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                acceptsRanges = false,
                faultInjector = { method, _ ->
                    if (method == "GET") FailureMode.Status(503) else FailureMode.None
                },
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"))
            assertIs<DownloadResult.IoFailure>(result)
        }
    }

    @Test
    fun `server with Accept-Ranges but no Content-Length still falls back to single GET`() = runTest {
        val payload = Bytes.deterministic(1500)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                acceptsRanges = true,
                omitContentLength = true,
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `server omitting Content-Length on HEAD falls back to single GET and succeeds`() = runTest {
        val payload = Bytes.deterministic(2000, seed = 9)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(
                acceptsRanges = false,
                omitContentLength = true,
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(payload.size.toLong(), result.bytes)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `unreachable host yields IoFailure (probe-time IO failure)`() = runTest {
        val downloader = FileDownloader(
            JdkHttpRangeFetcher(connectTimeout = 200.milliseconds, requestTimeout = 200.milliseconds),
        )
        // 127.0.0.1:1 is essentially guaranteed to be unbound; connect attempt fails fast.
        val result = downloader.download(URL("http://127.0.0.1:1/file.bin"), tempDir.resolve("o.bin"))
        assertIs<DownloadResult.IoFailure>(result)
    }

    @Test
    fun `RangeNotSupported is referenced as a singleton-ish data object`() {
        // Smoke test for the data object subtype. It's reserved for future strict-mode use
        // (probe says no Accept-Ranges AND fallback is forbidden). We currently fall back
        // automatically, so this type isn't returned by download() - but we still expect it
        // to be a stable singleton instance, exhaustively matchable in `when` blocks.
        val a: DownloadResult = DownloadResult.RangeNotSupported
        val b: DownloadResult = DownloadResult.RangeNotSupported
        assertTrue(a === b)
        assertTrue(a is DownloadResult.RangeNotSupported)
    }

    @Test
    fun `failed probe still fires onFinished on the progress listener (without onStarted)`() = runTest {
        val listener = com.example.downloader.fakes.RecordingProgressListener()
        TestHttpServer().use { server ->
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { progressListener = listener }
            val result = downloader.download(server.url("/missing.bin"), tempDir.resolve("o.bin"), cfg)
            assertIs<DownloadResult.HttpError>(result)
        }
        assertEquals(null, listener.startedTotal, "onStarted must not fire on probe failure")
        assertIs<DownloadResult.HttpError>(listener.finishedResult)
    }
}
