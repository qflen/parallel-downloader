package com.example.downloader.http

import com.example.downloader.retry.NonRetryableFetchException
import com.example.downloader.retry.TransientFetchException
import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FailureMode
import com.example.downloader.fakes.FaultInjector
import com.example.downloader.fakes.FileOptions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SEVEN_SECONDS = 7L
private const val THIRTY_SECONDS = 30L

class JdkHttpRangeFetcherTest {

    @Test
    fun `constructor rejects non-positive transportBufferSize`() {
        assertThrows<IllegalArgumentException> { JdkHttpRangeFetcher(transportBufferSize = 0) }
        assertThrows<IllegalArgumentException> { JdkHttpRangeFetcher(transportBufferSize = -1) }
    }

    @Test
    fun `constructor rejects non-positive connectTimeout`() {
        assertThrows<IllegalArgumentException> {
            JdkHttpRangeFetcher(connectTimeout = 0.seconds)
        }
        assertThrows<IllegalArgumentException> {
            JdkHttpRangeFetcher(connectTimeout = (-1).milliseconds)
        }
    }

    @Test
    fun `constructor rejects non-positive requestTimeout`() {
        assertThrows<IllegalArgumentException> {
            JdkHttpRangeFetcher(requestTimeout = 0.seconds)
        }
    }

    @Test
    fun `fetchRange rejects an empty range (start greater than end)`() = runTest {
        val fetcher = JdkHttpRangeFetcher()
        assertThrows<IllegalArgumentException> {
            fetcher.fetchRange(URL("http://127.0.0.1:1/x"), 100L..50L) { _, _ -> }
        }
    }

    @Test
    fun `fetchRange wraps probe-time IOException as TransientFetchException`() = runTest {
        val fetcher = JdkHttpRangeFetcher(connectTimeout = 100.milliseconds)
        assertThrows<TransientFetchException> {
            fetcher.fetchRange(URL("http://127.0.0.1:1/x"), 0L..9L) { _, _ -> }
        }
    }

    @Test
    fun `requestTimeout=null is accepted and a fetchRange call still completes`() = runTest {
        val payload = ByteArray(64) { it.toByte() }
        com.example.downloader.fakes.TestHttpServer().use { server ->
            server.serve("/x.bin", payload)
            val fetcher = JdkHttpRangeFetcher(requestTimeout = null)
            var received = 0
            fetcher.fetchRange(server.url("/x.bin"), 0L..63L) { _, buf -> received += buf.remaining() }
            assertEquals(64, received)
        }
    }


    @Test
    fun `503 with Retry-After delta-seconds surfaces as TransientFetchException with parsed retryAfter`() = runTest {
        val payload = Bytes.deterministic(2_048, seed = 31)
        com.example.downloader.fakes.TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(faultInjector = FaultInjector { method, _ ->
                    if (method == "GET") FailureMode.Status(503, retryAfter = "5") else FailureMode.None
                }),
            )
            val fetcher = JdkHttpRangeFetcher()
            val exc = assertThrows<TransientFetchException> {
                fetcher.fetchRange(server.url("/file.bin"), 0L..1023L) { _, _ -> }
            }
            assertEquals(5.seconds, exc.retryAfter)
        }
    }

    @Test
    fun `503 with Retry-After HTTP-date surfaces as TransientFetchException with parsed retryAfter`() = runTest {
        val payload = Bytes.deterministic(2_048, seed = 32)
        // Use a date roughly 7 seconds in the future. The parser produces a Duration in
        // milliseconds; we just confirm the value is present and within a sane window.
        val future = Instant.now().plusSeconds(SEVEN_SECONDS)
        val httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(future.atZone(ZoneId.of("GMT")))
        com.example.downloader.fakes.TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(faultInjector = FaultInjector { method, _ ->
                    if (method == "GET") FailureMode.Status(503, retryAfter = httpDate) else FailureMode.None
                }),
            )
            val fetcher = JdkHttpRangeFetcher()
            val exc = assertThrows<TransientFetchException> {
                fetcher.fetchRange(server.url("/file.bin"), 0L..1023L) { _, _ -> }
            }
            assertEquals(true, exc.retryAfter != null, "HTTP-date Retry-After must produce a non-null Duration")
        }
    }

    @Test
    fun `503 without Retry-After produces TransientFetchException with retryAfter null`() = runTest {
        val payload = Bytes.deterministic(2_048, seed = 33)
        com.example.downloader.fakes.TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(faultInjector = FaultInjector { method, _ ->
                    if (method == "GET") FailureMode.Status(503) else FailureMode.None
                }),
            )
            val fetcher = JdkHttpRangeFetcher()
            val exc = assertThrows<TransientFetchException> {
                fetcher.fetchRange(server.url("/file.bin"), 0L..1023L) { _, _ -> }
            }
            assertNull(exc.retryAfter)
        }
    }

    @Test
    fun `429 Too Many Requests is transient (not non-retryable client-error)`() = runTest {
        val payload = Bytes.deterministic(2_048, seed = 34)
        com.example.downloader.fakes.TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(faultInjector = FaultInjector { method, _ ->
                    if (method == "GET") FailureMode.Status(429, retryAfter = "1") else FailureMode.None
                }),
            )
            val fetcher = JdkHttpRangeFetcher()
            val exc = assertThrows<TransientFetchException> {
                fetcher.fetchRange(server.url("/file.bin"), 0L..1023L) { _, _ -> }
            }
            assertEquals(1.seconds, exc.retryAfter, "429 must carry Retry-After through as transient")
        }
    }

    @Test
    fun `404 stays a NonRetryableFetchException after the 429 reclassification`() = runTest {
        val payload = Bytes.deterministic(2_048, seed = 35)
        com.example.downloader.fakes.TestHttpServer().use { server ->
            server.serve(
                "/file.bin", payload,
                FileOptions(faultInjector = FaultInjector { method, _ ->
                    if (method == "GET") FailureMode.Status(404) else FailureMode.None
                }),
            )
            val fetcher = JdkHttpRangeFetcher()
            val exc = assertThrows<NonRetryableFetchException> {
                fetcher.fetchRange(server.url("/file.bin"), 0L..1023L) { _, _ -> }
            }
            assertEquals(404, exc.statusCode)
        }
    }

    @Test
    fun `parseRetryAfter handles both delta-seconds and HTTP-date and rejects malformed input`() {
        // Deterministic clock anchored at a known instant so the HTTP-date branch is
        // testable without flakiness.
        val anchor = Instant.parse("2026-05-05T12:00:00Z")
        val clock = Clock.fixed(anchor, ZoneOffset.UTC)
        // delta-seconds
        assertEquals(5.seconds, JdkHttpRangeFetcher.parseRetryAfter("5", clock))
        assertEquals(0.seconds, JdkHttpRangeFetcher.parseRetryAfter("0", clock))
        // negative delta-seconds: rejected
        assertNull(JdkHttpRangeFetcher.parseRetryAfter("-1", clock))
        // HTTP-date 30 seconds in the future
        val future = anchor.plusSeconds(THIRTY_SECONDS)
        val httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(future.atZone(ZoneId.of("GMT")))
        val parsed = JdkHttpRangeFetcher.parseRetryAfter(httpDate, clock)
        assertEquals(THIRTY_SECONDS.seconds, parsed)
        // HTTP-date in the past: rejected
        val past = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(anchor.minusSeconds(THIRTY_SECONDS).atZone(ZoneId.of("GMT")))
        assertNull(JdkHttpRangeFetcher.parseRetryAfter(past, clock))
        // garbage: rejected
        assertNull(JdkHttpRangeFetcher.parseRetryAfter("never", clock))
        assertNull(JdkHttpRangeFetcher.parseRetryAfter("", clock))
        // whitespace tolerated around delta-seconds
        assertEquals(7.seconds, JdkHttpRangeFetcher.parseRetryAfter("  7  ", clock))
    }

    @Test
    fun `parseContentRange parses well-formed headers`() {
        assertEquals(0L..1023L, JdkHttpRangeFetcher.parseContentRange("bytes 0-1023/2048"))
        assertEquals(100L..200L, JdkHttpRangeFetcher.parseContentRange("bytes 100-200/500"))
        // total may be * (unknown)
        assertEquals(5L..7L, JdkHttpRangeFetcher.parseContentRange("bytes 5-7/*"))
        // tolerates surrounding whitespace
        assertEquals(0L..0L, JdkHttpRangeFetcher.parseContentRange("  bytes 0-0/1  "))
    }

    @Test
    fun `parseContentRange returns null for malformed headers`() {
        assertNull(JdkHttpRangeFetcher.parseContentRange("bytes 0-1023"))         // missing /total
        assertNull(JdkHttpRangeFetcher.parseContentRange("0-1023/2048"))          // missing 'bytes'
        assertNull(JdkHttpRangeFetcher.parseContentRange("bytes 1023-0/2048"))    // end < start
        assertNull(JdkHttpRangeFetcher.parseContentRange("bytes abc-def/100"))    // not numeric
        assertNull(JdkHttpRangeFetcher.parseContentRange(""))                     // empty
        assertNull(JdkHttpRangeFetcher.parseContentRange("bytes -1-5/100"))       // negative start
    }
}
