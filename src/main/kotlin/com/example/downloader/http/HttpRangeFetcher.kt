package com.example.downloader.http

import java.net.URL
import java.nio.ByteBuffer

/**
 * Pattern: **Adapter / Port** — the downloader depends on this interface, never directly on
 * `java.net.http.HttpClient`. Production has one implementation ([JdkHttpRangeFetcher]); tests
 * substitute a fault-injecting fake; [RetryingHttpRangeFetcher] decorates any implementation.
 */
interface HttpRangeFetcher {

    /** Sends a HEAD-equivalent probe to discover length, range support, and the post-redirect URL. */
    suspend fun probe(url: URL): ProbeResult

    /**
     * Streams the requested byte range into [sink]. Implementations MUST NOT buffer the whole
     * range in memory — they read transport-sized slices and forward them to [sink] with the
     * absolute file offset for each slice.
     *
     * @param range byte range, both ends inclusive (matches HTTP `Range: bytes=start-end` semantics).
     *   Must be non-empty: `range.first <= range.last`.
     * @param sink consumes streamed bytes at their absolute file position.
     */
    suspend fun fetchRange(url: URL, range: LongRange, sink: RangeSink)

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
data class ProbeResult(
    val status: Int,
    val contentLength: Long?,
    val acceptsRanges: Boolean,
    val finalUrl: URL,
)

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
