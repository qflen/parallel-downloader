package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FailureMode
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.RecordingProgressListener
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs

class FileDownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `downloads a small file via parallel ranged GETs and SHA matches`() = runTest {
        val payload = Bytes.deterministic(size = 100_000, seed = 1)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig {
                chunkSize = 16.KiB
                parallelism = 4
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertEquals(payload.size.toLong(), result.bytes)
            assertEquals(dest, result.path)
            assertTrue(result.elapsed.isPositive(), "elapsed should be positive")
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `downloads a single-chunk file (smaller than chunkSize) and SHA matches`() = runTest {
        val payload = Bytes.deterministic(size = 5000, seed = 2)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(payload.size.toLong(), result.bytes)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `progress listener fires onStarted, onChunkComplete per chunk, and onFinished with Success`() = runTest {
        val payload = Bytes.deterministic(size = 3000, seed = 3)
        val listener = RecordingProgressListener()
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig {
                chunkSize = 1024L
                parallelism = 2
                progressListener = listener
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
        }
        assertEquals(3000L, listener.startedTotal)
        // 3000 bytes / 1024 chunkSize = 3 chunks
        assertEquals(setOf(0, 1, 2), listener.chunkCompletions.toSet())
        assertIs<DownloadResult.Success>(listener.finishedResult)
        assertEquals(3000L, listener.lastReportedDownloaded)
        assertEquals(3000L, listener.lastReportedTotal)
    }

    @ParameterizedTest(name = "[{index}] size={0} chunkSize={1} parallelism={2}")
    @MethodSource("sizeMatrixCases")
    fun `download succeeds and SHA matches across the size matrix`(
        size: Int,
        chunkSize: Long,
        parallelism: Int,
    ) = runTest {
        val payload = Bytes.deterministic(size, seed = size.toLong())
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out-$size.bin")
            val cfg = downloadConfig {
                this.chunkSize = chunkSize
                this.parallelism = parallelism
            }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)
            assertEquals(size.toLong(), result.bytes)
            assertEquals(size.toLong(), Files.size(dest))
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `zero-byte file downloads successfully and creates an empty file`() = runTest {
        TestHttpServer().use { server ->
            server.serve("/empty.bin", ByteArray(0))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("empty.bin")
            val result = downloader.download(server.url("/empty.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(0L, result.bytes)
            assertTrue(Files.exists(dest))
            assertEquals(0L, Files.size(dest))
        }
    }

    @Test
    fun `URL with query string and URL-encoded path components works`() = runTest {
        val payload = Bytes.deterministic(size = 1000, seed = 4)
        TestHttpServer().use { server ->
            // The server's request URI exposes a URL-decoded path, so we register the file
            // under the decoded form and request it via an encoded URL with a query string.
            server.serve("/dir/my file+extra.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val url = server.url("/dir/my%20file%2Bextra.bin?token=abc&v=2")
            val result = downloader.download(url, dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `default config (no DSL) downloads correctly`() = runTest {
        val payload = Bytes.deterministic(size = 50_000, seed = 5)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `existing file is overwritten when overwriteExisting=true`() = runTest {
        val payload = Bytes.deterministic(size = 4000, seed = 6)
        val dest = tempDir.resolve("preexisting.bin")
        Files.write(dest, ByteArray(100) { 0xFF.toByte() }) // pre-existing junk
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)
            assertEquals(payload.size.toLong(), Files.size(dest))
            assertEquals(Bytes.sha256(payload), Bytes.sha256(dest))
        }
    }

    @Test
    fun `existing file fails with IoFailure when overwriteExisting=false`() = runTest {
        val dest = tempDir.resolve("preexisting.bin")
        Files.write(dest, ByteArray(100))
        TestHttpServer().use { server ->
            server.serve("/file.bin", Bytes.deterministic(1000))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { overwriteExisting = false }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.IoFailure>(result)
            // Pre-existing content should still be there
            assertEquals(100L, Files.size(dest))
        }
    }

    @Test
    fun `partial file is deleted when chunked download fails after probe success`() = runTest {
        val payload = Bytes.deterministic(size = 5000, seed = 7)
        TestHttpServer().use { server ->
            // Server's HEAD says 5000 bytes with ranges supported, but chunk GETs return 503.
            server.serve("/file.bin", payload, FileOptions(
                faultInjector = { method, _ ->
                    if (method == "GET") FailureMode.Status(503) else FailureMode.None
                },
            ))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 2 }
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertNotNull(result)
            assertFalse(result is DownloadResult.Success, "expected failure, got $result")
            assertFalse(Files.exists(dest), "partial file should have been deleted")
        }
    }

    companion object {
        @JvmStatic
        fun sizeMatrixCases(): List<Arguments> = listOf(
            // Spec's size matrix (sizes given as bytes, parallelism varied to exercise the dispatch logic)
            Arguments.of(1, 1024L, 4),                        // 1 byte
            Arguments.of(1024 - 1, 1024L, 4),                 // chunkSize - 1 (single short chunk)
            Arguments.of(1024, 1024L, 4),                     // exactly chunkSize
            Arguments.of(1024 + 1, 1024L, 4),                 // chunkSize + 1 (boundary tail)
            Arguments.of(4 * 1024, 1024L, 4),                 // N × chunkSize
            Arguments.of(4 * 1024 + 1, 1024L, 4),             // N × chunkSize + 1
            Arguments.of(64 * 1024, 1024L, 8),                // many small chunks
            Arguments.of(100_000, 8 * 1024L, 4),              // multi-chunk normal
            Arguments.of(1_000_000, 64 * 1024L, 8),           // 1 MB at 64 KiB chunks (15+ chunks)
            // Force single-chunk + a tail chunk smaller than chunk size
            Arguments.of(2 * 1024 + 7, 1024L, 4),
        )
    }
}

