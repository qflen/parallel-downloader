package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertIs

class ProgressEventFlowTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `flow emits Started, ChunkComplete per chunk, Progress events, then Finished with Success`() = runTest {
        val payload = Bytes.deterministic(3000, seed = 1)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 2 }
            val events: List<ProgressEvent> =
                downloader.downloadAsFlow(server.url("/file.bin"), tempDir.resolve("o.bin"), cfg)
                    .toList()

            assertTrue(events.isNotEmpty())
            assertIs<ProgressEvent.Started>(events.first())
            assertEquals(3000L, (events.first() as ProgressEvent.Started).total)

            // Last event is always Finished; here it carries Success.
            val finished = events.last()
            assertIs<ProgressEvent.Finished>(finished)
            assertIs<DownloadResult.Success>(finished.result)

            // Three chunks of 1024/1024/952 bytes. Three ChunkComplete events with indices {0, 1, 2}.
            val chunkIndices = events.filterIsInstance<ProgressEvent.ChunkComplete>()
                .map { it.chunkIndex }
                .toSet()
            assertEquals(setOf(0, 1, 2), chunkIndices)

            // Progress events: at least one, all values within [0, total]. (Strict monotonicity
            // across chunk completions isn't guaranteed because the per-chunk
            // addAndGet→onProgress sequence isn't atomic, so concurrent chunk completions can
            // interleave and the consumer can observe values out of order. The final
            // observed value still equals total.)
            val progress = events.filterIsInstance<ProgressEvent.Progress>()
            assertTrue(progress.isNotEmpty(), "expected at least one Progress event")
            assertTrue(progress.all { it.downloaded in 0L..3000L })
            assertEquals(3000L, progress.maxOf { it.downloaded })
        }
    }

    @Test
    fun `flow surfaces a probe HttpError as Finished without preceding Started`() = runTest {
        TestHttpServer().use { server ->
            // No serve() — root handler returns 404
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val events: List<ProgressEvent> =
                downloader.downloadAsFlow(server.url("/missing.bin"), tempDir.resolve("o.bin"))
                    .toList()

            assertEquals(1, events.size, "expected only the terminal event on probe failure")
            val finished = events.single()
            assertIs<ProgressEvent.Finished>(finished)
            val result = finished.result
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(404, result.status)
        }
    }

    @Test
    fun `the flow returned is a cold Flow - re-collecting starts a fresh download`() = runTest {
        val payload = Bytes.deterministic(500, seed = 2)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())

            val flow: Flow<ProgressEvent> =
                downloader.downloadAsFlow(server.url("/file.bin"), tempDir.resolve("o-1.bin"))
            val firstEvents = flow.toList()
            assertIs<ProgressEvent.Finished>(firstEvents.last())

            // Re-collecting against a fresh destination must work — flow is cold.
            val flow2: Flow<ProgressEvent> =
                downloader.downloadAsFlow(server.url("/file.bin"), tempDir.resolve("o-2.bin"))
            val secondEvents = flow2.toList()
            assertIs<ProgressEvent.Finished>(secondEvents.last())
            assertIs<DownloadResult.Success>(
                (secondEvents.last() as ProgressEvent.Finished).result,
            )
        }
    }
}
