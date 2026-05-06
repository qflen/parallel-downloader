package com.example.downloader.http

import com.example.downloader.retry.NonRetryableFetchException
import com.example.downloader.retry.TransientFetchException
import com.example.downloader.retry.ValidatorMismatchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pattern: **Adapter** - wraps `java.net.http.HttpClient` (JDK built-in, zero extra runtime
 * deps so the implementation stays auditable at a glance - and HttpClient already handles
 * HTTP/2, redirects, and connection pooling correctly) behind the [HttpRangeFetcher] port.
 *
 * Streams each response body via `BodyHandlers.ofInputStream()` and writes transport-sized
 * slices into the [RangeSink] without buffering the whole range in memory. The slice's
 * underlying byte storage is reused across reads within a single fetch invocation, so sinks
 * must consume each [ByteBuffer] synchronously (the FileChannel-write sink does).
 */
class JdkHttpRangeFetcher(
    private val transportBufferSize: Int = DEFAULT_TRANSPORT_BUFFER_SIZE,
    connectTimeout: Duration = 10.seconds,
    private val requestTimeout: Duration? = 60.seconds,
) : HttpRangeFetcher {

    init {
        require(transportBufferSize > 0) {
            "transportBufferSize must be > 0, got $transportBufferSize"
        }
        require(connectTimeout.isPositive()) { "connectTimeout must be > 0" }
        require(requestTimeout == null || requestTimeout.isPositive()) {
            "requestTimeout must be > 0 or null"
        }
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        // Follow http to http and https to https redirects but never downgrade https to http.
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(java.time.Duration.ofMillis(connectTimeout.inWholeMilliseconds))
        .build()

    private val httpProbe = HttpProbe(httpClient)

    override suspend fun probe(url: URL): ProbeResult = httpProbe.probe(url)

    override suspend fun fetchRange(
        url: URL,
        range: LongRange,
        entityValidator: String?,
        sink: RangeSink,
    ) {
        require(range.first <= range.last) { "Range must be non-empty: $range" }
        val expectedLength = range.last - range.first + 1
        val request = buildRangedRequest(url, range, entityValidator)
        val response = sendStreaming(request, url)
        // Validate inside `use {}` so the body stream is always closed - even when validation
        // throws - and the underlying connection is returned to the pool rather than leaked.
        response.body().use { stream ->
            validateRangedResponse(response, range, ifRangeSent = entityValidator)
            val written = copyStreamToSink(stream, range.first, sink)
            if (written != expectedLength) {
                // Server closed the connection mid-body or sent fewer bytes than declared.
                // Transient because the next attempt may succeed cleanly.
                throw TransientFetchException(
                    "premature EOF on Range $range: expected $expectedLength bytes, got $written"
                )
            }
        }
    }

    override suspend fun fetchAll(url: URL, sink: RangeSink) {
        val request = HttpRequest.newBuilder(url.toURI())
            .GET()
            .suppressDefaultUserAgent()
            .applyRequestTimeout()
            .build()
        val response = sendStreaming(request, url)
        response.body().use { stream ->
            val s = response.statusCode()
            when {
                s in SUCCESS_RANGE -> {}
                s == TOO_MANY_REQUESTS -> throw TransientFetchException(
                    "GET $url returned $s",
                    retryAfter = parseRetryAfterFrom(response),
                )
                s in SERVER_ERROR_RANGE -> throw TransientFetchException(
                    "GET $url returned $s",
                    retryAfter = parseRetryAfterFrom(response),
                )
                else -> throw NonRetryableFetchException("GET $url returned $s", statusCode = s)
            }
            copyStreamToSink(stream, startOffset = 0L, sink)
        }
    }

    private fun buildRangedRequest(
        url: URL,
        range: LongRange,
        entityValidator: String?,
    ): HttpRequest = HttpRequest.newBuilder(url.toURI())
        .GET()
        .suppressDefaultUserAgent()
        .header("Range", "bytes=${range.first}-${range.last}")
        .also { builder ->
            // RFC 7233 §3.2: If-Range tells the server "give me 206 only if your current
            // ETag/Last-Modified still matches; otherwise return 200 + full body". A 200 on
            // a request that carried If-Range surfaces as ValidatorMismatchException
            // (mapped to DownloadResult.ValidatorMismatch). A 200 without If-Range is the
            // server-ignored-Range case (NonRetryableFetchException -> HttpError(200, CHUNK)).
            if (entityValidator != null) builder.header("If-Range", entityValidator)
        }
        .applyRequestTimeout()
        .build()

    private fun HttpRequest.Builder.applyRequestTimeout(): HttpRequest.Builder =
        // requestTimeout=null disables JDK's per-request deadline, leaving cancellation as the
        // only way to abort a stuck transfer. Useful for very large bodies where chunk delivery
        // can legitimately take longer than any sensible timeout, especially when many ranged
        // GETs share the JDK HttpClient's connection pool.
        if (requestTimeout != null) {
            timeout(java.time.Duration.ofMillis(requestTimeout.inWholeMilliseconds))
        } else this

    private suspend fun sendStreaming(
        request: HttpRequest,
        url: URL,
    ): HttpResponse<InputStream> = try {
        runInterruptible(Dispatchers.IO) {
            httpClient.send(request, BodyHandlers.ofInputStream())
        }
    } catch (e: java.io.InterruptedIOException) {
        // JDK signals interrupt-driven send abort via InterruptedIOException - propagate as
        // CancellationException so structured concurrency wins over transient classification.
        currentCoroutineContext().ensureActive()
        throw CancellationException("send interrupted by cancellation").apply { initCause(e) }
    } catch (e: IOException) {
        // runInterruptible only converts plain InterruptedException; for any other IOException
        // we still re-check job state in case a cancellation race left the coroutine cancelled
        // but the catch path didn't see it via a specifically-typed exception.
        currentCoroutineContext().ensureActive()
        throw TransientFetchException("send ${request.method()} $url: ${e.message}", e)
    }

    private fun validateRangedResponse(
        response: HttpResponse<*>,
        requested: LongRange,
        ifRangeSent: String?,
    ) {
        val status = response.statusCode()
        if (status != PARTIAL_CONTENT) {
            throw mapNonPartialContentStatus(status, requested, response, ifRangeSent)
        }

        val contentRange = response.headers().firstValue("Content-Range").orElse(null) ?: return
        val parsed = parseContentRange(contentRange)
        if (parsed == null || parsed.first != requested.first || parsed.last != requested.last) {
            throw TransientFetchException(
                "Content-Range invalid: requested ${requested.first}-${requested.last}, " +
                    "header='$contentRange'"
            )
        }
    }

    /**
     * Translates a non-206 status on a ranged GET into the right exception type. Two distinct
     * 200 cases:
     *
     *  * If we sent an `If-Range` header and the reply is 200, the server's validator evaluation
     *    rejected our value - the resource changed between probe and chunk-fetch. Surface as
     *    [ValidatorMismatchException] so the orchestrator can map it to
     *    [com.example.downloader.DownloadResult.ValidatorMismatch]. Splicing the new body's bytes
     *    at a chunk offset would corrupt the file, so this is a deterministic terminal failure.
     *
     *  * If we did NOT send `If-Range` and the reply is 200, the server ignored our `Range`
     *    header - a server-bug case the caller may want to report. Surface as
     *    [NonRetryableFetchException] (mapped by the orchestrator to `HttpError(200, CHUNK)`).
     *
     * The probe path is what catches "server lies about Accept-Ranges" cleanly, before any bytes
     * hit disk.
     *
     * 429 (Too Many Requests) is special-cased out of the 4xx non-retryable bucket: it is
     * a transient back-pressure signal whose `Retry-After` header (when present) carries
     * the server's suggested wait. We propagate that hint via [TransientFetchException].
     */
    private fun mapNonPartialContentStatus(
        status: Int,
        requested: LongRange,
        response: HttpResponse<*>,
        ifRangeSent: String?,
    ): Exception = when (status) {
        HTTP_OK -> if (ifRangeSent != null) {
            // Same priority as HttpProbe: prefer ETag, fall back to Last-Modified. Null when
            // the 200 reply has neither - RFC-permitted shape, captured honestly by
            // ValidatorMismatch.observed = null.
            val observed = response.headers().firstValue("ETag").orElse(null)
                ?: response.headers().firstValue("Last-Modified").orElse(null)
            ValidatorMismatchException(expected = ifRangeSent, observed = observed)
        } else {
            NonRetryableFetchException(
                "server returned 200 to a Range request - protocol violation in chunk phase",
                statusCode = HTTP_OK,
            )
        }
        RANGE_NOT_SATISFIABLE -> NonRetryableFetchException(
            "Range Not Satisfiable for $requested",
            statusCode = RANGE_NOT_SATISFIABLE,
        )
        TOO_MANY_REQUESTS -> TransientFetchException(
            "ranged GET returned $status",
            retryAfter = parseRetryAfterFrom(response),
        )
        in CLIENT_ERROR_RANGE -> NonRetryableFetchException(
            "ranged GET returned $status",
            statusCode = status,
        )
        in SERVER_ERROR_RANGE -> TransientFetchException(
            "ranged GET returned $status",
            retryAfter = parseRetryAfterFrom(response),
        )
        else -> NonRetryableFetchException(
            "ranged GET returned unexpected $status",
            statusCode = status,
        )
    }

    private suspend fun copyStreamToSink(
        stream: InputStream,
        startOffset: Long,
        sink: RangeSink,
    ): Long {
        val storage = ByteArray(transportBufferSize)
        var totalWritten = 0L
        var position = startOffset
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                // runInterruptible: blocking read; routes coroutine cancellation through Thread.interrupt
                // so a hung connection can be cancelled.
                val read = runInterruptible(Dispatchers.IO) { stream.read(storage) }
                if (read < 0) return totalWritten
                if (read > 0) {
                    sink.write(position, ByteBuffer.wrap(storage, 0, read))
                    position += read
                    totalWritten += read
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw classifyReadFailure(e, position, totalWritten)
        }
    }

    /**
     * Maps an [IOException] from the body-reading loop to either a CancellationException (when
     * the failure type unambiguously signals interrupt-driven cancellation) or a transient
     * retryable failure (everything else - server-side mid-stream disconnect, connection reset).
     * Extracted so the catch site stays a single throw and detekt's InstanceOfCheckForException
     * rule has nothing to complain about - the type check is here, not in the catch body.
     */
    private fun classifyReadFailure(e: IOException, position: Long, totalWritten: Long): Throwable = when (e) {
        is java.nio.channels.ClosedChannelException ->
            CancellationException("read interrupted by cancellation").apply { initCause(e) }
        is java.io.InterruptedIOException ->
            CancellationException("read interrupted by cancellation").apply { initCause(e) }
        else -> TransientFetchException(
            "read failed at offset $position after $totalWritten bytes: ${e.message}",
            e,
        )
    }

    companion object {
        const val DEFAULT_TRANSPORT_BUFFER_SIZE: Int = 64 * 1024

        private const val HTTP_OK = 200
        private const val PARTIAL_CONTENT = 206
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val TOO_MANY_REQUESTS = 429
        private val SUCCESS_RANGE = 200..299
        private val CLIENT_ERROR_RANGE = 400..499
        private val SERVER_ERROR_RANGE = 500..599

        private val CONTENT_RANGE_REGEX = Regex("""^\s*bytes\s+(\d+)-(\d+)/(?:\d+|\*)\s*$""")

        internal fun parseContentRange(header: String): LongRange? {
            val m = CONTENT_RANGE_REGEX.matchEntire(header) ?: return null
            val start = m.groupValues[1].toLongOrNull()
            val end = m.groupValues[2].toLongOrNull()
            return if (start != null && end != null && end >= start) start..end else null
        }

        private fun parseRetryAfterFrom(response: HttpResponse<*>): Duration? =
            response.headers().firstValue("Retry-After").orElse(null)
                ?.let { parseRetryAfter(it, Clock.systemUTC()) }

        /**
         * Parses an RFC 7231 §7.1.3 `Retry-After` header value. Two formats:
         *   - delta-seconds: a non-negative integer (`Retry-After: 120`)
         *   - HTTP-date: an RFC 1123 / IMF-fixdate timestamp
         *     (`Retry-After: Tue, 15 Nov 1994 08:12:31 GMT`)
         *
         * Returns null when the header is absent, malformed, or names a moment that has
         * already passed (a negative delta-seconds or a date in the past). The retry
         * strategy then falls back to its scheduled backoff.
         */
        internal fun parseRetryAfter(header: String, clock: Clock): Duration? {
            val trimmed = header.trim()
            // delta-seconds form: a bare non-negative integer.
            val delta = trimmed.toLongOrNull()
            if (delta != null) return if (delta >= 0L) delta.seconds else null
            // HTTP-date form (IMF-fixdate, RFC 1123).
            return runCatching {
                val instant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed))
                val deltaMs = instant.toEpochMilli() - clock.instant().toEpochMilli()
                if (deltaMs > 0L) deltaMs.milliseconds else null
            }.getOrElse { e ->
                if (e is DateTimeParseException) null else throw e
            }
        }
    }
}

/**
 * Suppress the JDK HttpClient's default `User-Agent: Java-http-client/<jdk-version>`. Left as-is,
 * every request would fingerprint the JDK build; PRIVACY.md's "no User-Agent sent by default"
 * claim would have to caveat that. Setting an empty value emits a header with no value - RFC 7230
 * permits empty header values, and the receivers we care about (the test fake, real HTTP servers)
 * accept it.
 */
private fun HttpRequest.Builder.suppressDefaultUserAgent(): HttpRequest.Builder =
    setHeader("User-Agent", "")
