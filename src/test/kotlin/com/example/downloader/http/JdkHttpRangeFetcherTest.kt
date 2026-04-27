package com.example.downloader.http

import com.example.downloader.retry.TransientFetchException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
