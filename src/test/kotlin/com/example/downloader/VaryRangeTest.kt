package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs

/**
 * `Vary`-induced cache-key divergence: a probe and a chunk GET resolve to different
 * representations even though our request headers are identical. This shows up in the wild
 * when an intermediate proxy or CDN keys on a header we don't see (or that varies by
 * connection-level metadata), and serves a different body for the chunk than for the probe.
 *
 * Our defense is layered:
 *   1. We send the same User-Agent (empty) and no Cookie / Authorization on HEAD and GET,
 *      so we don't supply a Vary-listed header inconsistently on our own.
 *   2. Whenever the probe yields an `ETag` (or `Last-Modified`), every chunk request carries
 *      it in `If-Range`. The server is then required by RFC 9110 §13.1.5 to send the chunk
 *      only if the validator still matches its current representation; otherwise it falls back
 *      to 200 + full body.
 *   3. Our chunk-phase status check rejects any 200 reply on a ranged GET as a non-retryable
 *      protocol violation, surfaced as `HttpError(200, CHUNK)`.
 *
 * The two tests below pin both halves of the contract. Linked from
 * [docs/RFC-COMPLIANCE.md](../../../../../../../docs/RFC-COMPLIANCE.md) under
 * §12.5.5 (Vary).
 */
class VaryRangeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `validator divergence between probe and chunk surfaces as HttpError 200 CHUNK`() = runTest {
        // Server advertises ETag "v1" at probe time. Between HEAD (probe) and the first
        // chunk GET, the resource's validator rotates to "v2" - the same shape of inconsistency
        // a Vary-keyed CDN cache could produce when a HEAD lands on one variant and a GET
        // lands on another. The chunk's If-Range carries "v1"; the server sees "v2" and falls
        // back to 200 + full body. The orchestrator must reject it.
        val payload = Bytes.deterministic(VARY_PAYLOAD, seed = 91)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = "\"v1\""))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val rotateOnStart = object : ProgressListener {
                override fun onStarted(total: Long) {
                    server.configure("/file.bin", FileOptions(etag = "\"v2\""))
                }
            }
            val cfg = downloadConfig {
                chunkSize = VARY_CHUNK
                parallelism = 2
                progressListener = rotateOnStart
            }
            val dest = tempDir.resolve("dest.bin")
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            val err = assertIs<DownloadResult.HttpError>(result)
            assertEquals(STATUS_OK, err.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, err.phase)
            // The destination must NOT exist - silent splicing of two file versions is the
            // bad outcome this test rules out.
            assertFalse(Files.exists(dest), "destination must not be created when validators diverge")
        }
    }

    @Test
    fun `consistent validator across probe and chunk lets ranged-parallel succeed`() = runTest {
        // The negative case: when the validator stays consistent (no Vary divergence), the
        // ranged path completes normally. Confirms the divergence guard isn't a false alarm
        // that fires on every download.
        val payload = Bytes.deterministic(VARY_PAYLOAD, seed = 92)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(etag = "\"stable\""))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = VARY_CHUNK
                parallelism = 2
            }
            val dest = tempDir.resolve("dest.bin")
            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            val ok = assertIs<DownloadResult.Success>(result)
            assertEquals(VARY_PAYLOAD.toLong(), ok.bytes)
        }
    }

    private companion object {
        const val VARY_PAYLOAD = 4096
        const val VARY_CHUNK = 1024L
        const val STATUS_OK = 200
    }
}
