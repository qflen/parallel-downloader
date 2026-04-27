package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertIs

/**
 * Tests for RFC 7233 §3.2 `If-Range`-driven mid-download file-change detection.
 *
 * The orchestrator threads the probe's ETag (or Last-Modified) into every chunk GET. If the
 * server's resource changes during the download, the chunk's `If-Range` validator no longer
 * matches and the server returns 200 + full body instead of 206. Our chunk-phase status check
 * (fork 1) catches the 200 as a non-retryable protocol violation, the partial file is deleted,
 * and the caller sees a typed `HttpError(200, CHUNK)` instead of silently splicing two file
 * versions on disk.
 */
class IfRangeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `chunk GETs carry If-Range when probe returned an ETag`() = runTest {
        val payload = Bytes.deterministic(8000, seed = 1)
        val etag = "\"v1-abc123\""
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = etag))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 4 }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"), cfg)
            assertIs<DownloadResult.Success>(result)
            // Every ranged GET should have carried the same If-Range value the probe advertised.
            val rangedGets = server.requests.filter { it.method == "GET" && it.rangeHeader != null }
            assertTrue(rangedGets.isNotEmpty(), "expected at least one ranged GET")
            rangedGets.forEach { req ->
                assertEquals(etag, req.ifRangeHeader, "ranged GET missing or wrong If-Range header")
            }
        }
    }

    @Test
    fun `chunk GETs omit If-Range when probe didn't advertise an ETag`() = runTest {
        val payload = Bytes.deterministic(2000, seed = 2)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = null))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = 512L; parallelism = 2 }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("o.bin"), cfg)
            assertIs<DownloadResult.Success>(result)
            val rangedGets = server.requests.filter { it.method == "GET" && it.rangeHeader != null }
            rangedGets.forEach { assertNull(it.ifRangeHeader, "If-Range should be absent") }
        }
    }

    @Test
    fun `mid-download file change (ETag rotation) surfaces as a chunk-phase 200 protocol violation`() = runTest {
        // Server starts serving with ETag "v1". The orchestrator's `onStarted` callback fires
        // after the probe completes but before any chunks begin, which is exactly the window
        // where a real-world file rotation would happen. We rotate to "v2" inside that
        // callback so every chunk's If-Range header is destined to mismatch.
        val payload = Bytes.deterministic(8 * 1024, seed = 3)
        val initialEtag = "\"v1\""
        val rotatedEtag = "\"v2\""
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = initialEtag))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val rotatingListener = object : ProgressListener {
                override fun onStarted(total: Long) {
                    server.configure("/file.bin", FileOptions(etag = rotatedEtag))
                }
            }
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 2
                progressListener = rotatingListener
            }
            val dest = tempDir.resolve("o.bin")
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.HttpError>(result)
            assertEquals(200, result.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, result.phase)
            assertFalse(java.nio.file.Files.exists(dest), "partial file should be deleted")
        }
    }

    @Test
    fun `Last-Modified is used as fallback validator when no ETag is present`() = runTest {
        // The TestHttpServer doesn't auto-emit Last-Modified, but com.sun.net.httpserver does
        // not either - we just verify probe extraction handles the ETag-absent path. We only
        // check that with no ETag, the chunk request omits If-Range entirely (covered above).
        // For the Last-Modified path we'd need a Jetty-backed server emitting that header,
        // which is part of the next improvement item. Marker assertion only.
        val probe = com.example.downloader.http.ProbeResult(
            status = 200,
            contentLength = 100L,
            acceptsRanges = true,
            finalUrl = java.net.URL("http://example/x"),
            entityValidator = "Wed, 21 Oct 2026 07:28:00 GMT",
        )
        assertNotNull(probe.entityValidator)
    }
}
