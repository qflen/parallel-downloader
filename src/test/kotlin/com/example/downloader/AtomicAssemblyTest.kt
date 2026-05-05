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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Atomic-assembly invariants: the destination only ever appears at the documented path on
 * success, after every byte has been written and length-verified. A failure during the
 * download cannot truncate or partially overwrite the previous occupant of that path.
 */
class AtomicAssemblyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `failed download leaves an existing destination byte-identical to its pre-call content`() = runTest {
        // Pre-existing destination: a sentinel marker the test will check is preserved.
        val preExisting = Bytes.deterministic(2_048, seed = 100)
        val dest = tempDir.resolve("out.bin")
        Files.write(dest, preExisting)

        val payload = Bytes.deterministic(8_192, seed = 101)
        TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(faultInjector = FaultInjector { method, _ ->
                    if (method == "GET") FailureMode.Status(503) else FailureMode.None
                }),
            )
            val fetcher = RetryingHttpRangeFetcher(
                JdkHttpRangeFetcher(),
                ExponentialBackoffRetry(
                    maxAttempts = 2,
                    initialDelay = 1.milliseconds,
                    maxDelay = 5.milliseconds,
                    jitter = 0.0,
                ),
            )
            val downloader = FileDownloader(fetcher)
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 1
                overwriteExisting = true
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.IoFailure>(result)
        }
        assertArrayEquals(
            preExisting, Files.readAllBytes(dest),
            "atomic assembly: a failed download must leave the prior destination untouched",
        )
        assertFalse(Files.exists(partFor(dest)), "part file should be cleaned up on non-resume failure")
    }

    @Test
    fun `successful download produces destination and removes part file and sidecar`() = runTest {
        val payload = Bytes.deterministic(4_096, seed = 102)
        val dest = tempDir.resolve("out.bin")
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = "\"v1\""))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 2
                resume = true
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
        assertFalse(Files.exists(partFor(dest)), "part file must not survive a successful download")
        assertFalse(Files.exists(ResumeSidecar.pathFor(dest)), "sidecar must be deleted on success")
    }

    @Test
    fun `cancellation mid-stream leaves destination absent and part file removed (resume off)`() {
        // Larger payload + smaller latency so a chunk can complete (proving cancellation
        // lands during streaming, not during channel-open which would surface as IoFailure).
        val payload = Bytes.deterministic(64 * 1024, seed = 103)
        val listener = RecordingProgressListener()
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(latencyMillis = 100L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 4
                progressListener = listener
                resume = false
            }
            runBlocking(Dispatchers.IO) {
                val job = async {
                    downloader.download(server.url("/file.bin"), dest, cfg)
                }
                // Wait for at least one chunk to land so cancellation hits the streaming path.
                withTimeout(10.seconds) {
                    while (listener.chunkCompletions.isEmpty() && job.isActive) {
                        delay(10.milliseconds)
                    }
                }
                job.cancel()
                withTimeout(2.seconds) {
                    runCatching { job.await() }
                        .exceptionOrNull()
                        .let { it as? CancellationException ?: error("expected CancellationException, got $it") }
                }
            }
            assertFalse(Files.exists(dest), "destination must not appear on cancellation")
            assertFalse(Files.exists(partFor(dest)), "part file must be cleaned up when resume is off")
        }
    }

    @Test
    fun `cancellation mid-stream retains the part file when resume is on`() {
        // Use a payload large enough that one chunk can complete before the rest
        // (proves the resume contract has bytes to keep) and a per-request latency
        // large enough that subsequent chunks are still in flight when we cancel.
        val payload = Bytes.deterministic(64 * 1024, seed = 104)
        val listener = RecordingProgressListener()
        val dest = tempDir.resolve("out.bin")
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = "\"v1\"", latencyMillis = 100L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 4
                resume = true
                progressListener = listener
            }
            runBlocking(Dispatchers.IO) {
                val job = async {
                    downloader.download(server.url("/file.bin"), dest, cfg)
                }
                // Wait until at least one chunk has completed before cancelling. That guarantees
                // the channel was opened (so the .part file exists) and at least one chunk's
                // bytes are persisted - both prerequisites for the resume invariant we assert.
                withTimeout(10.seconds) {
                    while (listener.chunkCompletions.isEmpty() && job.isActive) {
                        delay(10.milliseconds)
                    }
                }
                job.cancel()
                withTimeout(2.seconds) {
                    runCatching { job.await() }
                        .exceptionOrNull()
                        .let { it as? CancellationException ?: error("expected CancellationException, got $it") }
                }
            }
        }
        assertFalse(Files.exists(dest), "destination must not appear on cancellation")
        assertTrue(Files.exists(partFor(dest)), "part file must be retained when resume is on")
    }

    @Test
    fun `single-GET fallback also goes through the part file before atomic rename`() = runTest {
        val payload = Bytes.deterministic(2_048, seed = 105)
        val dest = tempDir.resolve("out.bin")
        TestHttpServer().use { server ->
            // No range support - downloader uses single-GET fallback path.
            server.serve("/file.bin", payload, FileOptions(acceptsRanges = false))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
            assertFalse(Files.exists(partFor(dest)), "part file gone after single-GET fallback success")
        }
    }

    @Test
    fun `zero-byte download also goes through the part file`() = runTest {
        val dest = tempDir.resolve("empty.bin")
        TestHttpServer().use { server ->
            server.serve("/empty.bin", ByteArray(0))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(server.url("/empty.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(0L, Files.size(dest))
            assertFalse(Files.exists(partFor(dest)), "part file gone after zero-byte success")
        }
    }

    @Test
    fun `partFor sits next to the destination as dot-part`() {
        val dest = tempDir.resolve("nested").resolve("out.bin")
        val part = partFor(dest)
        assertEquals(dest.parent, part.parent, "part file lives next to destination")
        assertEquals("out.bin.part", part.fileName.toString())
    }
}
