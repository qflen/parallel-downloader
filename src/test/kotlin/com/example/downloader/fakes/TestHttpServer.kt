package com.example.downloader.fakes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real HTTP server (built on the JDK's `com.sun.net.httpserver.HttpServer` - zero extra deps)
 * with fault-injection knobs for testing the downloader against:
 *   * normal range requests (default)
 *   * `Accept-Ranges: bytes` toggled off (forces single-GET fallback)
 *   * status overrides (404 / 416 / 503)
 *   * `Content-Length` lies (HEAD-side override)
 *   * 200-instead-of-206 on ranged GETs (server ignores the Range header)
 *   * malformed / off-by-one `Content-Range` headers
 *   * artificial latency
 *   * per-response byte-rate throttling
 *   * deterministic per-request fault injection (failure rate + seed)
 *
 * The server records every request and the maximum concurrent in-flight request count, so tests
 * can assert that the downloader actually exercises parallelism (`maxConcurrentRequests > 1`).
 *
 * Usage:
 * ```
 * TestHttpServer().use { server ->
 *     server.serve("/file.bin", bytes)
 *     val result = downloader.download(server.url("/file.bin"), tmp)
 *     // ...
 * }
 * ```
 */
class TestHttpServer : AutoCloseable {

    private val files = ConcurrentHashMap<String, ServedEntry>()
    private val fileChannels = CopyOnWriteArrayList<FileContent>()
    private val activeRequests = AtomicInteger(0)
    private val maxActiveRequests = AtomicInteger(0)
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newFixedThreadPool(SERVER_EXECUTOR_THREADS)

    init {
        server.executor = executor
        server.createContext("/", ::handle)
        server.start()
    }

    /** Base URL - pick a path with [url]. */
    val baseUrl: URL = URL("http://127.0.0.1:${server.address.port}/")

    /** Maximum number of in-flight requests observed since construction (or last reset). */
    val maxConcurrentRequests: Int get() = maxActiveRequests.get()

    /** All requests received since construction (or last reset). */
    val requests: List<RecordedRequest> get() = recorded.toList()

    fun resetCounters() {
        maxActiveRequests.set(0)
        recorded.clear()
    }

    fun url(path: String): URL = URL(baseUrl, path.removePrefix("/"))

    fun serve(path: String, content: ByteArray, options: FileOptions = FileOptions()) {
        files[normalize(path)] = ServedEntry(ByteArrayContent(content), options)
    }

    /**
     * Serve a file from disk. Used by stress tests that need a 1 GiB body without holding it in
     * memory - the server reads only the requested range from the file on each request.
     */
    fun serveFromFile(path: String, source: Path, options: FileOptions = FileOptions()) {
        val entry = ServedEntry(FileContent(source), options)
        // Track the channel for clean-up on close().
        fileChannels += entry.source.let { it as FileContent }
        files[normalize(path)] = entry
    }

    fun configure(path: String, options: FileOptions) {
        files.compute(normalize(path)) { _, existing ->
            requireNotNull(existing) { "no file served at $path" }.copy(options = options)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        fileChannels.forEach { runCatching { it.close() } }
    }

    // --------------------------------------------------------------------------------------
    // Request handler
    // --------------------------------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        val active = activeRequests.incrementAndGet()
        maxActiveRequests.updateAndGet { current -> maxOf(current, active) }
        try {
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            val ifRangeHeader = exchange.requestHeaders.getFirst("If-Range")
            recorded += RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                rangeHeader = rangeHeader,
                ifRangeHeader = ifRangeHeader,
            )
            val entry = files[exchange.requestURI.path]
            if (entry == null) {
                exchange.sendResponseHeaders(STATUS_NOT_FOUND, NO_BODY)
                return
            }
            handleEntry(exchange, entry, rangeHeader)
        } catch (_: IOException) {
            // Client disconnected, or our `disconnect` fault rethrew: nothing useful to do.
        } finally {
            activeRequests.decrementAndGet()
            exchange.close()
        }
    }

    private fun handleEntry(exchange: HttpExchange, entry: ServedEntry, rangeHeader: String?) {
        val opts = entry.options
        val source = entry.source
        if (opts.latencyMillis > 0) Thread.sleep(opts.latencyMillis)

        // Per-request fault injection (deterministic when configured).
        val fault = opts.faultInjector?.decide(exchange.requestMethod, rangeHeader) ?: FailureMode.None
        val forcedStatus = (fault as? FailureMode.Status)?.statusCode ?: opts.statusOverride
        if (forcedStatus != null) {
            exchange.sendResponseHeaders(forcedStatus, NO_BODY)
            return
        }

        if (opts.acceptsRanges) {
            exchange.responseHeaders["Accept-Ranges"] = listOf("bytes")
        }

        if (exchange.requestMethod == "HEAD") {
            handleHead(exchange, source.totalLength, opts)
        } else {
            val rangedRequested = rangeHeader != null && opts.acceptsRanges && !opts.ignoreRangeHeader
            // RFC 7233 §3.2: If-Range with a stale validator means "give me the full body".
            // Mid-download file change is the canonical use case.
            val ifRangeMismatch = rangedRequested &&
                opts.etag != null &&
                exchange.requestHeaders.getFirst("If-Range")
                    ?.let { it != opts.etag } == true
            if (rangedRequested && !ifRangeMismatch) {
                handleRangedGet(exchange, source, requireNotNull(rangeHeader), opts, fault)
            } else {
                handleFullGet(exchange, source, opts, fault)
            }
        }
    }

    private fun handleHead(exchange: HttpExchange, actualLength: Long, opts: FileOptions) {
        if (!opts.omitContentLength) {
            val advertised = opts.headContentLengthOverride ?: actualLength
            exchange.responseHeaders["Content-Length"] = listOf(advertised.toString())
        }
        if (opts.etag != null) {
            exchange.responseHeaders["ETag"] = listOf(opts.etag)
        }
        exchange.sendResponseHeaders(STATUS_OK, NO_BODY)
    }

    private fun handleRangedGet(
        exchange: HttpExchange,
        source: ContentSource,
        rangeHeader: String,
        opts: FileOptions,
        fault: FailureMode,
    ) {
        val parsed = parseRangeHeader(rangeHeader, source.totalLength)
        if (parsed == null) {
            exchange.sendResponseHeaders(STATUS_RANGE_NOT_SATISFIABLE, NO_BODY)
            return
        }
        val (start, end) = parsed
        val length = end - start + 1
        val contentRange = opts.contentRangeOverride ?: "bytes $start-$end/${source.totalLength}"
        exchange.responseHeaders["Content-Range"] = listOf(contentRange)
        exchange.responseHeaders["Content-Length"] = listOf(length.toString())
        exchange.sendResponseHeaders(STATUS_PARTIAL_CONTENT, length)
        writeBody(exchange.responseBody, source, rangeStart = start, rangeLength = length, opts, fault)
    }

    private fun handleFullGet(
        exchange: HttpExchange,
        source: ContentSource,
        opts: FileOptions,
        fault: FailureMode,
    ) {
        val length = source.totalLength
        exchange.responseHeaders["Content-Length"] = listOf(length.toString())
        exchange.sendResponseHeaders(STATUS_OK, length)
        writeBody(exchange.responseBody, source, rangeStart = 0L, rangeLength = length, opts, fault)
    }

    private fun writeBody(
        out: OutputStream,
        source: ContentSource,
        rangeStart: Long,
        rangeLength: Long,
        opts: FileOptions,
        fault: FailureMode,
    ) {
        val disconnectAt = if (fault == FailureMode.Disconnect) rangeLength / 2 else Long.MAX_VALUE
        val tickSize = computeTickSize(opts.throttleBytesPerSecond).toLong()
        val buffer = ByteArray(tickSize.toInt().coerceAtLeast(1))
        var written = 0L
        while (written < rangeLength) {
            val toWrite = minOf(tickSize, rangeLength - written)
            val bounded = minOf(toWrite, disconnectAt - written)
            if (bounded <= 0L) break
            val read = source.readInto(rangeStart + written, bounded.toInt(), buffer, 0)
            out.write(buffer, 0, read)
            // Flush only when throttling - otherwise per-tick flushes dominate throughput on
            // multi-MiB responses. Without throttling the OS / HttpServer batches naturally.
            if (opts.throttleBytesPerSecond != null) out.flush()
            written += read

            if (read < toWrite && fault == FailureMode.Disconnect) {
                // Force a non-graceful close so the client sees premature EOF rather than
                // a normally-terminated response. Throwing IOException out of the handler
                // is the cleanest way to abort the framed HTTP response.
                throw IOException("simulated mid-stream disconnect")
            }
            opts.throttleBytesPerSecond?.let { rate ->
                val sleepMs = (read * MILLIS_PER_SECOND) / rate
                if (sleepMs > 0L) Thread.sleep(sleepMs)
            }
        }
    }

    // --------------------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------------------

    private fun normalize(path: String): String =
        if (path.startsWith("/")) path else "/$path"

    private companion object {
        private const val SERVER_EXECUTOR_THREADS = 64
        private const val STATUS_OK = 200
        private const val STATUS_PARTIAL_CONTENT = 206
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_RANGE_NOT_SATISFIABLE = 416
        private const val NO_BODY: Long = -1L
        // For unthrottled responses, write large chunks to amortize per-write overhead. Small
        // ticks force tiny TCP segments and dominate total time on multi-MiB chunks.
        private const val UNTHROTTLED_TICK_SIZE = 256 * 1024
        // Throttled responses cap at 16 KiB so the rate limiter is smooth, not bursty.
        private const val THROTTLED_TICK_CAP = 16 * 1024
        private const val MILLIS_PER_SECOND = 1000L

        private val RANGE_HEADER_REGEX = Regex("""^\s*bytes\s*=\s*(\d+)-(\d+)\s*$""")

        fun parseRangeHeader(header: String, totalLength: Long): Pair<Long, Long>? {
            val m = RANGE_HEADER_REGEX.matchEntire(header) ?: return null
            val start = m.groupValues[1].toLongOrNull()
            val end = m.groupValues[2].toLongOrNull()
            val ok = start != null && end != null &&
                start in 0L..<totalLength && end in start..<totalLength
            return if (ok) start!! to end!! else null
        }

        fun computeTickSize(throttleBytesPerSecond: Long?): Int {
            if (throttleBytesPerSecond == null) return UNTHROTTLED_TICK_SIZE
            // Aim for roughly 10 ticks per second so the throttle is smooth, not bursty.
            val tickBytes = (throttleBytesPerSecond / 10L).toInt().coerceAtLeast(1)
            return tickBytes.coerceAtMost(THROTTLED_TICK_CAP)
        }
    }
}

private data class ServedEntry(val source: ContentSource, val options: FileOptions) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Pluggable content backend so [TestHttpServer] can serve either an in-memory byte array
 * (the common case) or a file on disk (the stress-test case where buffering 1 GiB in memory
 * would blow past the heap cap).
 */
internal interface ContentSource {
    val totalLength: Long
    /** Reads up to [length] bytes starting at [offset] into [dest] at [destOffset]. Returns
     *  the number of bytes actually placed (always == length for non-EOF reads). */
    fun readInto(offset: Long, length: Int, dest: ByteArray, destOffset: Int): Int
}

internal class ByteArrayContent(private val bytes: ByteArray) : ContentSource {
    override val totalLength: Long = bytes.size.toLong()
    override fun readInto(offset: Long, length: Int, dest: ByteArray, destOffset: Int): Int {
        System.arraycopy(bytes, offset.toInt(), dest, destOffset, length)
        return length
    }
}

internal class FileContent(path: Path) : ContentSource, AutoCloseable {
    private val channel: FileChannel = FileChannel.open(path, READ)
    override val totalLength: Long = Files.size(path)

    override fun readInto(offset: Long, length: Int, dest: ByteArray, destOffset: Int): Int {
        val buffer = ByteBuffer.wrap(dest, destOffset, length)
        var pos = offset
        var totalRead = 0
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, pos)
            if (n < 0) break
            pos += n
            totalRead += n
        }
        return totalRead
    }

    override fun close() {
        runCatching { channel.close() }
    }
}

data class FileOptions(
    val acceptsRanges: Boolean = true,
    /** When non-null, HEAD's `Content-Length` is this value instead of the byte array's length. */
    val headContentLengthOverride: Long? = null,
    /** When `true`, HEAD does not return any `Content-Length` header. */
    val omitContentLength: Boolean = false,
    /** When non-null, every response uses this status (probe and chunk). */
    val statusOverride: Int? = null,
    /** When `true`, ranged GET ignores the Range header - returns 200 + full body. */
    val ignoreRangeHeader: Boolean = false,
    /** When non-null, the server's `Content-Range` header literally equals this string. */
    val contentRangeOverride: String? = null,
    /**
     * When non-null, HEAD response includes `ETag: <value>`. Ranged GETs honor `If-Range`:
     * when the request's If-Range value differs from this current value, the server falls
     * back to a 200 + full body response per RFC 7233 §3.2 (used to test mid-download
     * file-change detection).
     */
    val etag: String? = null,
    /** Pre-response delay applied to every request. */
    val latencyMillis: Long = 0L,
    /** When non-null, the response body is throttled to this many bytes per second. */
    val throttleBytesPerSecond: Long? = null,
    /** Per-request decision function (optional). Allows deterministic chaos testing. */
    val faultInjector: FaultInjector? = null,
)

fun interface FaultInjector {
    fun decide(method: String, rangeHeader: String?): FailureMode
}

sealed interface FailureMode {
    /** Default: serve normally. */
    data object None : FailureMode
    /** Reply with this status code and an empty body. */
    data class Status(val statusCode: Int) : FailureMode
    /** Write the first half of the response then close abruptly. */
    data object Disconnect : FailureMode
}

data class RecordedRequest(
    val method: String,
    val path: String,
    val rangeHeader: String?,
    val ifRangeHeader: String? = null,
)
