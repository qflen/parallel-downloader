package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs

/**
 * RFC 9110 §14.4 defines `Range` to operate on the selected representation in its encoded
 * form: the bytes the server writes after applying `Content-Encoding`. A naive client that
 * combines `Range: bytes=N-M` with `Content-Encoding: gzip` will:
 *
 *   - cut chunks at byte boundaries that don't match user expectations (offsets are over the
 *     encoded stream, not the decoded one);
 *   - risk inconsistent encoded streams across chunks when an intermediate proxy or CDN
 *     re-compresses with a different timestamp / level (the validator may stay the same);
 *   - produce output that decompresses to a partially-correct file, or worse, decompresses
 *     to garbage at chunk boundaries.
 *
 * The orchestrator's defense is conservative: when the probe sees a non-identity
 * `Content-Encoding`, ranged-parallel is refused outright and the download falls through to
 * the single-GET path. Single-GET writes whatever bytes the server sends, byte-for-byte, so
 * the on-disk file matches the server's representation regardless of encoding.
 */
class ContentEncodingRangeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `gzip Content-Encoding plus Accept-Ranges falls through to single-GET`() = runTest {
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 81)
        TestHttpServer().use { server ->
            server.serve(
                "/file.bin",
                payload,
                FileOptions(
                    acceptsRanges = true,
                    contentEncodingOverride = "gzip",
                ),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("dest.bin")
            val result = downloader.download(
                server.url("/file.bin"),
                dest,
                downloadConfig { chunkSize = SMALL_CHUNK },
            )
            assertIs<DownloadResult.Success>(result)
            // Written body equals the server's response body byte-for-byte, even though it
            // was advertised as gzip. The downloader's contract is "what the server sent
            // is what you get"; decoding is the caller's problem.
            assertTrue(payload.contentEquals(Files.readAllBytes(dest)))
            // Exactly one GET request was issued (the single-GET fallback). If ranges had
            // been used, we would see one HEAD plus several ranged GETs.
            val getRequests = server.requests.count { it.method == "GET" }
            assertEquals(1, getRequests, "ranged path must not be used when Content-Encoding is non-identity")
        }
    }

    @Test
    fun `deflate Content-Encoding plus Accept-Ranges falls through to single-GET`() = runTest {
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 82)
        TestHttpServer().use { server ->
            server.serve(
                "/file.bin",
                payload,
                FileOptions(contentEncodingOverride = "deflate"),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(
                server.url("/file.bin"),
                tempDir.resolve("dest.bin"),
                downloadConfig { chunkSize = SMALL_CHUNK },
            )
            assertIs<DownloadResult.Success>(result)
            assertEquals(1, server.requests.count { it.method == "GET" })
        }
    }

    @Test
    fun `identity Content-Encoding still uses ranged-parallel`() = runTest {
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 83)
        TestHttpServer().use { server ->
            server.serve(
                "/file.bin",
                payload,
                FileOptions(contentEncodingOverride = "identity"),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(
                server.url("/file.bin"),
                tempDir.resolve("dest.bin"),
                downloadConfig { chunkSize = SMALL_CHUNK },
            )
            assertIs<DownloadResult.Success>(result)
            // identity is the no-op encoding; ranged path is fine. With a 4-byte payload
            // and 256-byte chunks, the planner emits one chunk - so still one GET. With a
            // 1024-byte payload split into 256-byte chunks, four GETs would be observed.
            // We use the multi-chunk shape:
            val getRequests = server.requests.count { it.method == "GET" }
            assertEquals(SMALL_PAYLOAD / SMALL_CHUNK.toInt(), getRequests)
        }
    }

    @Test
    fun `absent Content-Encoding header keeps ranged-parallel enabled`() = runTest {
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 84)
        TestHttpServer().use { server ->
            // No contentEncodingOverride → header omitted from the response.
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(
                server.url("/file.bin"),
                tempDir.resolve("dest.bin"),
                downloadConfig { chunkSize = SMALL_CHUNK },
            )
            assertIs<DownloadResult.Success>(result)
            val getRequests = server.requests.count { it.method == "GET" }
            assertEquals(SMALL_PAYLOAD / SMALL_CHUNK.toInt(), getRequests)
        }
    }

    private companion object {
        const val SMALL_PAYLOAD = 1024
        const val SMALL_CHUNK = 256L
    }
}
