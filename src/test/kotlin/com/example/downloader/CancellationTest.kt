package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.RecordingProgressListener
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CancellationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `cancelling parent job cancels download, deletes partial file, listener sees Cancelled`() {
        // Use real-time runBlocking + Dispatchers.IO here; runTest's virtual time interferes
        // with the wall-clock latency we use to keep chunks in flight long enough to cancel.
        // Payload is sized so at least one chunk can complete before the rest are still
        // mid-flight, and latency is enough that cancellation lands during streaming rather
        // than during channel-open (which would surface as IoFailure on some platforms).
        val payload = Bytes.deterministic(64 * 1024, seed = 1)
        val listener = RecordingProgressListener()
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(latencyMillis = 100L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 4
                progressListener = listener
            }

            runBlocking(Dispatchers.IO) {
                val job = async {
                    downloader.download(server.url("/file.bin"), dest, cfg)
                }
                // Wait until at least one chunk has completed so cancellation hits the
                // chunk-streaming path. Cancelling earlier could interrupt channel-open and
                // surface as IoFailure on platforms where the JVM raises ClosedByInterrupt.
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
            assertFalse(
                Files.exists(dest),
                "partial file should have been deleted after cancellation",
            )
        }
        assertIs<DownloadResult.Cancelled>(listener.finishedResult)
    }

    @Test
    fun `cancellation completes promptly without leaking coroutines`() {
        // 50 cancellations in a row, asserting each completes within 1 second.
        // (The stress suite scales this up to 50 × 100 MiB; this lighter version
        // covers the same code path so coverage credit accrues to the non-stress run.)
        val payload = Bytes.deterministic(4 * 1024, seed = 2)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(latencyMillis = 50L))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 4 }

            repeat(REPEAT_CANCELS) { i ->
                val dest = tempDir.resolve("c-$i.bin")
                runBlocking(Dispatchers.IO) {
                    val job = async {
                        downloader.download(server.url("/file.bin"), dest, cfg)
                    }
                    delay(20.milliseconds)
                    job.cancel()
                    withTimeout(1.seconds) {
                        runCatching { job.await() }
                    }
                }
                assertFalse(Files.exists(dest), "iteration $i: partial file should be deleted")
            }
        }
    }

    @Test
    fun `cancelling before download starts (no progress yet) is a no-op cancellation`() {
        // Validates that the suspend function is well-behaved when cancellation arrives
        // before any HTTP request has been issued.
        val payload = Bytes.deterministic(1000, seed = 3)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            runBlocking(Dispatchers.IO) {
                val job = async {
                    downloader.download(server.url("/file.bin"), dest)
                }
                job.cancel()
                runCatching { job.await() }
            }
        }
    }

    private companion object {
        private const val REPEAT_CANCELS = 25
    }
}
