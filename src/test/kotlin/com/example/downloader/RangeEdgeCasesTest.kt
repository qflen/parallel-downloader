package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FailureMode
import com.example.downloader.fakes.FaultInjector
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertIs

/**
 * Edge-case behaviors for the bits of RFC 9110 §14 (Range Requests) that surface only in
 * unusual server replies. The orchestrator's "happy path" tests cover absolute ranges
 * with a known total; this file pins the corners.
 *
 * - Suffix-range syntax in `Content-Range`: not valid per RFC 9110 §14.3 (Content-Range
 *   always carries absolute coordinates), so the parser must reject it rather than coerce
 *   it to a half-open interval.
 * - 416 Range Not Satisfiable: a non-retryable [DownloadResult.HttpError] in either the
 *   probe phase or the chunk phase. Retrying a 416 cannot recover - the requested range
 *   exceeds the resource length, so we return it cleanly to the caller.
 * - `Content-Range: bytes N-M` over an unknown total: legal per RFC 9110 §14.3 and
 *   accepted by [JdkHttpRangeFetcher.parseContentRange]; ranged downloads complete
 *   normally even when the server can't quote a total resource length on the chunk reply.
 */
class RangeEdgeCasesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parseContentRange rejects suffix-syntax headers`() {
        // Suffix syntax (`bytes=-N`) is request-side only per RFC 9110 §14.1.1. A server
        // that put it into a Content-Range response would be wrong; the parser should not
        // try to rescue malformed headers, since accepting them would mean writing bytes at
        // some guessed offset.
        assertNull(JdkHttpRangeFetcher.parseContentRange("bytes -100/200"))
        assertNull(JdkHttpRangeFetcher.parseContentRange("bytes -100/*"))
        // Suffix-shaped Range (request) header that somehow ended up in a response.
        assertNull(JdkHttpRangeFetcher.parseContentRange("bytes=-100/200"))
    }

    @Test
    fun `416 on probe surfaces as HttpError 416 PROBE`() = runTest {
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 71)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(statusOverride = STATUS_RANGE_NOT_SATISFIABLE))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(
                server.url("/file.bin"),
                tempDir.resolve("dest.bin"),
            )
            val err = assertIs<DownloadResult.HttpError>(result)
            assertEquals(STATUS_RANGE_NOT_SATISFIABLE, err.status)
            assertEquals(DownloadResult.HttpError.Phase.PROBE, err.phase)
        }
    }

    @Test
    fun `416 on a chunk GET surfaces as HttpError 416 CHUNK`() = runTest {
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 72)
        TestHttpServer().use { server ->
            // Inject 416 only on GETs so the HEAD probe succeeds and the orchestrator
            // dispatches to the chunked path.
            server.serve(
                "/file.bin",
                payload,
                FileOptions(faultInjector = FaultInjector { method, _ ->
                    if (method == "GET") FailureMode.Status(STATUS_RANGE_NOT_SATISFIABLE) else FailureMode.None
                }),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(
                server.url("/file.bin"),
                tempDir.resolve("dest.bin"),
                downloadConfig { chunkSize = SMALL_CHUNK },
            )
            val err = assertIs<DownloadResult.HttpError>(result)
            assertEquals(STATUS_RANGE_NOT_SATISFIABLE, err.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, err.phase)
        }
    }

    @Test
    fun `Content-Range with unknown total bytes N-M divided by star is accepted`() = runTest {
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 73)
        TestHttpServer().use { server ->
            // The server quotes the chunk's range relative to an "unknown" total. RFC 9110
            // §14.3 explicitly permits this when the server doesn't (or won't) reveal the
            // resource length on a per-chunk basis. The orchestrator already knows the total
            // from HEAD, so the chunk reply doesn't need to repeat it.
            val unknownTotalRange = "bytes 0-" + (SMALL_PAYLOAD - 1) + "/*"
            server.serve(
                "/file.bin",
                payload,
                FileOptions(contentRangeOverride = unknownTotalRange),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val result = downloader.download(
                server.url("/file.bin"),
                tempDir.resolve("dest.bin"),
                downloadConfig { chunkSize = SMALL_PAYLOAD.toLong() },  // one chunk covers the whole file
            )
            val ok = assertIs<DownloadResult.Success>(result)
            assertEquals(SMALL_PAYLOAD.toLong(), ok.bytes)
        }
    }

    @Test
    fun `parseContentRange accepts unknown total (star)`() {
        // Pinned here alongside the unknown-total integration test so the matrix in
        // RFC-COMPLIANCE.md has a unit-level reference and an end-to-end one.
        assertEquals(0L..1023L, JdkHttpRangeFetcher.parseContentRange("bytes 0-1023/*"))
        assertEquals(0L..0L, JdkHttpRangeFetcher.parseContentRange("bytes 0-0/*"))
    }

    @Test
    fun `200 on a chunk GET without If-Range sent stays HttpError, not ValidatorMismatch`() = runTest {
        // The fetcher distinguishes two 200-on-ranged-GET cases by whether the request carried
        // an If-Range header:
        //   * If-Range sent -> ValidatorMismatch (the server's validator evaluation rejected
        //     our value; mid-download representation change).
        //   * No If-Range sent -> HttpError(200, CHUNK) (the server ignored our Range header
        //     entirely; a server bug rather than a validator-driven outcome).
        // This test pins the second case: a server with no ETag (so the orchestrator never
        // sends If-Range) that ignores Range still surfaces as the original HttpError, not
        // the new typed ValidatorMismatch variant.
        val payload = Bytes.deterministic(SMALL_PAYLOAD, seed = 74)
        TestHttpServer().use { server ->
            // etag=null: probe returns no validator, so the orchestrator's chunk GETs carry
            // no If-Range. ignoreRangeHeader=true: the server returns 200 + full body.
            server.serve(
                "/file.bin",
                payload,
                FileOptions(acceptsRanges = true, etag = null, ignoreRangeHeader = true),
            )
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig { chunkSize = SMALL_CHUNK }
            val result = downloader.download(server.url("/file.bin"), tempDir.resolve("dest.bin"), cfg)
            val err = assertIs<DownloadResult.HttpError>(result)
            assertEquals(STATUS_OK, err.status)
            assertEquals(DownloadResult.HttpError.Phase.CHUNK, err.phase)
            // Confirm no ranged GET carried an If-Range, so the test's preconditions hold.
            val rangedGets = server.requests.filter { it.method == "GET" && it.rangeHeader != null }
            assertTrue(rangedGets.isNotEmpty(), "expected at least one ranged GET")
            rangedGets.forEach { req ->
                assertNull(req.ifRangeHeader, "test premise: chunk GET must omit If-Range when probe yields no validator")
            }
        }
    }

    private companion object {
        const val SMALL_PAYLOAD = 512
        const val SMALL_CHUNK = 256L
        const val STATUS_RANGE_NOT_SATISFIABLE = 416
        const val STATUS_OK = 200
    }
}
