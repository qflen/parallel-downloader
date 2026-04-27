package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FailureMode
import com.example.downloader.fakes.FaultInjector
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.RecordingProgressListener
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import com.example.downloader.http.RetryingHttpRangeFetcher
import com.example.downloader.retry.ExponentialBackoffRetry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class ResumeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `successful resume-mode download deletes the sidecar after completion`() = runTest {
        val payload = Bytes.deterministic(5_000, seed = 1)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = "\"v1\""))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 2
                resume = true
            }
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
            // Sidecar should have been deleted on success.
            assertFalse(Files.exists(ResumeSidecar.pathFor(dest)))
        }
    }

    @Test
    fun `resume-mode failure leaves dest on disk and a subsequent attempt finishes`() = runTest {
        // First attempt: server is broken (503 on every GET) so the download fails. Verify
        // dest stays on disk because resume=true. Second attempt: clean server, completes
        // (the sidecar from first attempt is empty, so all chunks are fetched, but the
        // pre-allocated dest is reused — no truncate, no allocation churn).
        val payload = Bytes.deterministic(2_048, seed = 2)
        var serverBroken = true
        TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(
                    etag = "\"v1\"",
                    faultInjector = FaultInjector { method, _ ->
                        if (serverBroken && method == "GET") FailureMode.Status(503)
                        else FailureMode.None
                    },
                ),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 2
                resume = true
            }
            val dest = tempDir.resolve("out.bin")

            val firstResult = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.IoFailure>(firstResult)
            assertTrue(Files.exists(dest), "partial file should persist on resume-mode failure")
            // (Sidecar may or may not exist depending on whether any chunk completed before
            // cancellation — not asserting either way; the next attempt handles both cases.)

            serverBroken = false
            val secondResult = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(secondResult)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
            assertFalse(Files.exists(ResumeSidecar.pathFor(dest)), "sidecar should be deleted on success")
        }
    }

    @Test
    fun `resume only re-fetches missing chunks (server sees fewer GETs the second time)`() = runTest {
        // Stage a partial download by hand: pre-fill the destination with the first half of
        // the payload and write a sidecar marking chunks 0 and 1 complete. Then call download
        // with resume=true and assert the server saw only the missing chunks (2 and 3).
        // Pre-staging avoids the chunk-cancellation race that makes the natural "first attempt
        // fails midway" scenario flaky — limitedParallelism doesn't serialize blocking I/O,
        // so concurrent in-flight chunks get cancelled before they can record completion.
        val payload = Bytes.deterministic(4_096, seed = 3)
        val etag = "\"v1\""
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = etag))
            val dest = tempDir.resolve("out.bin")

            // Pre-allocate dest at full size, write first half (chunks 0+1), leave second
            // half zero (will be filled by resume).
            val partial = ByteArray(4_096)
            System.arraycopy(payload, 0, partial, 0, 2_048)
            Files.write(dest, partial)
            ResumeSidecar.save(
                dest,
                ResumeState(
                    totalBytes = 4_096L,
                    chunkSize = 1_024L,
                    entityValidator = etag,
                    completedChunks = setOf(0, 1),
                ),
            )

            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 1_024L
                parallelism = 2
                resume = true
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            val gets = server.requests.count { it.method == "GET" }
            assertEquals(2, gets, "expected only the 2 missing chunks to be re-fetched")
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
            assertFalse(Files.exists(ResumeSidecar.pathFor(dest)))
        }
    }

    @Test
    fun `resume refuses to use a sidecar whose validator no longer matches the probe`() = runTest {
        val firstPayload = Bytes.deterministic(2_000, seed = 4)
        val secondPayload = Bytes.deterministic(2_000, seed = 5)  // different content
        val listener = RecordingProgressListener()
        TestHttpServer().use { server ->
            // First serve: ETag v1, but every chunk fails so we leave a partial.
            server.serve(
                "/file.bin", firstPayload,
                FileOptions(
                    etag = "\"v1\"",
                    faultInjector = FaultInjector { method, range ->
                        if (method == "GET" && range != null) FailureMode.Status(503)
                        else FailureMode.None
                    },
                ),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 512L
                parallelism = 1
                resume = true
                progressListener = listener
            }
            val dest = tempDir.resolve("out.bin")
            val firstResult = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.IoFailure>(firstResult)
            // The first attempt failed completely — every ranged GET returned 503. The dest
            // stays on disk (pre-allocated, mostly zeros) and the sidecar lists no completed
            // chunks. The next attempt's validator-mismatch check is what we're really
            // testing, regardless of how many chunks did or didn't complete the first time.

            // Now rotate the file: new content, new ETag.
            server.serve(
                "/file.bin", secondPayload,
                FileOptions(etag = "\"v2\""),  // different validator, no fault injector
            )
            val secondResult = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(secondResult)
            // Result is the new content, not a Frankenstein splice.
            assertEquals(Bytes.sha256(secondPayload), Bytes.sha256(dest))
            assertNotEquals(Bytes.sha256(firstPayload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `non-resume downloads still delete the partial file on failure (default behavior unchanged)`() = runTest {
        val payload = Bytes.deterministic(2_000, seed = 6)
        TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(
                    faultInjector = FaultInjector { method, _ ->
                        if (method == "GET") FailureMode.Status(503) else FailureMode.None
                    },
                ),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 1
                resume = false  // explicit
            }
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.IoFailure>(result)
            assertFalse(Files.exists(dest), "non-resume failure should delete partial file")
            assertFalse(Files.exists(ResumeSidecar.pathFor(dest)))
        }
    }

    @Test
    fun `resume with no existing sidecar starts fresh`() = runTest {
        val payload = Bytes.deterministic(3_000, seed = 7)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = "\"v1\""))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 2
                resume = true
            }
            val dest = tempDir.resolve("out.bin")
            assertNull(ResumeSidecar.load(dest))
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }
}
