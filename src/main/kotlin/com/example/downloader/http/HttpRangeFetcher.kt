package com.example.downloader.http

import java.net.URL
import java.nio.ByteBuffer

/**
 * Pattern: **Adapter / Port** - the downloader depends on this interface, never directly on
 * `java.net.http.HttpClient`. Production has one implementation ([JdkHttpRangeFetcher]); tests
 * substitute a fault-injecting fake; [RetryingHttpRangeFetcher] decorates any implementation.
 */
interface HttpRangeFetcher {

    /** Sends a HEAD-equivalent probe to discover length, range support, and the post-redirect URL. */
    suspend fun probe(url: URL): ProbeResult

    /**
     * Streams the requested byte range into [sink]. Implementations MUST NOT buffer the whole
     * range in memory - they read transport-sized slices and forward them to [sink] with the
     * absolute file offset for each slice.
     *
     * @param range byte range, both ends inclusive (matches HTTP `Range: bytes=start-end` semantics).
     *   Must be non-empty: `range.first <= range.last`.
     * @param sink consumes streamed bytes at their absolute file position.
     * @param entityValidator optional `If-Range` validator (an ETag or HTTP-date). When non-null,
     *   the server returns 200 + full body instead of 206 if the resource has changed since the
     *   probe. Implementations MUST surface that 200 as a deterministic terminal failure
     *   (`ValidatorMismatchException` for [JdkHttpRangeFetcher], mapped by the orchestrator to
     *   [com.example.downloader.DownloadResult.ValidatorMismatch]) rather than splicing the
     *   new body's bytes at a chunk offset.
     */
    suspend fun fetchRange(url: URL, range: LongRange, entityValidator: String? = null, sink: RangeSink)

    /**
     * Streams the entire resource into [sink], starting at file offset 0. Used for the
     * single-GET fallback when the server doesn't support ranges.
     */
    suspend fun fetchAll(url: URL, sink: RangeSink)
}

/**
 * Outcome of a probe.
 *
 * @property status HTTP status code of the probe response. 2xx means [contentLength] and
 *   [acceptsRanges] are valid; non-2xx means the caller should surface an [DownloadResult.HttpError].
 * @property contentLength length in bytes; `null` if the server didn't return `Content-Length`
 *   (rare; we fall back to a single GET in that case).
 * @property acceptsRanges `true` only when the server returned `Accept-Ranges: bytes`. Missing
 *   header or `Accept-Ranges: none` → `false`.
 * @property finalUrl the URL after any redirects. Chunk fetches use this so we don't pay the
 *   redirect on every chunk.
 */
/**
 * @property entityValidator The ETag (preferred) or `Last-Modified` HTTP-date the server
 *   advertised on the probe response, suitable for use as an `If-Range` value on subsequent
 *   chunk requests. `null` if the server returned neither header. When non-null, the orchestrator
 *   threads this through to every chunk GET so a mid-download file change is detected via the
 *   server's 200-instead-of-206 fallback (RFC 7233 §3.2) and surfaced as
 *   [com.example.downloader.DownloadResult.ValidatorMismatch].
 * @property contentEncoding The raw value of the response `Content-Encoding` header (lowercased),
 *   or `null` if the header was absent. Per RFC 9110 §14.4, byte ranges are defined over the
 *   selected representation in its encoded form. Combining `Range` with a non-identity
 *   `Content-Encoding` is a documented footgun: chunk boundaries cut through the encoded
 *   bitstream, the byte counts no longer match user expectations, and intermediate proxies
 *   may serve inconsistent encoded streams across chunks. The orchestrator refuses ranged
 *   downloads when [usesIdentityEncoding] is `false`, falling through to single-GET.
 */
data class ProbeResult(
    val status: Int,
    val contentLength: Long?,
    val acceptsRanges: Boolean,
    val finalUrl: URL,
    val entityValidator: String? = null,
    val contentEncoding: String? = null,
) {
    /**
     * `true` when the response carries no `Content-Encoding` header or the value is exactly
     * `identity` (RFC 9110 §8.4.1). Any other value (`gzip`, `deflate`, `br`, multi-coding,
     * etc.) is treated as a non-identity encoding and disqualifies the resource from ranged
     * parallel download.
     */
    val usesIdentityEncoding: Boolean
        get() = contentEncoding == null || contentEncoding == "identity"
}

/**
 * Consumes streamed bytes from a fetch operation. Defined as `fun interface` so the downloader
 * can pass `{ pos, buf -> channel.write(buf, pos) }` directly with no allocation.
 *
 * @see HttpRangeFetcher.fetchRange
 */
fun interface RangeSink {
    /**
     * Called repeatedly with successive byte slices. The slice covers [absolutePosition] through
     * `absolutePosition + buffer.remaining() - 1`.
     *
     * Implementations may consume [buffer] (advancing its position) or copy out of it; the
     * fetcher does not reuse the buffer between calls within a single [HttpRangeFetcher.fetchRange]
     * invocation, but may reuse it across invocations on the same fetcher instance.
     */
    suspend fun write(absolutePosition: Long, buffer: ByteBuffer)
}
