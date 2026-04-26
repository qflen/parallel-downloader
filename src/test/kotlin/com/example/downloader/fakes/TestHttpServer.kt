package com.example.downloader.fakes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real HTTP server (built on the JDK's `com.sun.net.httpserver.HttpServer` — zero extra deps)
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

    /** Base URL — pick a path with [url]. */
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
        files[normalize(path)] = ServedEntry(content, options)
    }

    fun configure(path: String, options: FileOptions) {
        files.compute(normalize(path)) { _, existing ->
            requireNotNull(existing) { "no file served at $path" }.copy(options = options)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    // --------------------------------------------------------------------------------------
    // Request handler
    // --------------------------------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        val active = activeRequests.incrementAndGet()
        maxActiveRequests.updateAndGet { current -> maxOf(current, active) }
        try {
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            recorded += RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                rangeHeader = rangeHeader,
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
        val content = entry.content
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
            handleHead(exchange, content.size.toLong(), opts)
        } else {
            val rangedRequested = rangeHeader != null && opts.acceptsRanges && !opts.ignoreRangeHeader
            if (rangedRequested) {
                handleRangedGet(exchange, content, requireNotNull(rangeHeader), opts, fault)
            } else {
                handleFullGet(exchange, content, opts, fault)
            }
        }
    }

    private fun handleHead(exchange: HttpExchange, actualLength: Long, opts: FileOptions) {
        if (!opts.omitContentLength) {
            val advertised = opts.headContentLengthOverride ?: actualLength
            exchange.responseHeaders["Content-Length"] = listOf(advertised.toString())
        }
        exchange.sendResponseHeaders(STATUS_OK, NO_BODY)
    }

    private fun handleRangedGet(
        exchange: HttpExchange,
        content: ByteArray,
        rangeHeader: String,
        opts: FileOptions,
        fault: FailureMode,
    ) {
        val parsed = parseRangeHeader(rangeHeader, content.size.toLong())
        if (parsed == null) {
            exchange.sendResponseHeaders(STATUS_RANGE_NOT_SATISFIABLE, NO_BODY)
            return
        }
        val (start, end) = parsed
        val length = end - start + 1
        val contentRange = opts.contentRangeOverride ?: "bytes $start-$end/${content.size}"
        exchange.responseHeaders["Content-Range"] = listOf(contentRange)
        exchange.responseHeaders["Content-Length"] = listOf(length.toString())
        exchange.sendResponseHeaders(STATUS_PARTIAL_CONTENT, length)
        writeBody(exchange.responseBody, content, start.toInt(), length.toInt(), opts, fault)
    }

    private fun handleFullGet(
        exchange: HttpExchange,
        content: ByteArray,
        opts: FileOptions,
        fault: FailureMode,
    ) {
        val length = content.size.toLong()
        exchange.responseHeaders["Content-Length"] = listOf(length.toString())
        exchange.sendResponseHeaders(STATUS_OK, length)
        writeBody(exchange.responseBody, content, 0, content.size, opts, fault)
    }

    private fun writeBody(
        out: OutputStream,
        content: ByteArray,
        offset: Int,
        length: Int,
        opts: FileOptions,
        fault: FailureMode,
    ) {
        val disconnectAt = if (fault == FailureMode.Disconnect) length / 2 else Int.MAX_VALUE
        val tickSize = computeTickSize(opts.throttleBytesPerSecond)
        var written = 0
        while (written < length) {
            val toWrite = minOf(tickSize, length - written)
            val bounded = minOf(toWrite, disconnectAt - written)
            if (bounded <= 0) break
            out.write(content, offset + written, bounded)
            out.flush()
            written += bounded

            if (bounded < toWrite && fault == FailureMode.Disconnect) {
                // Force a non-graceful close so the client sees premature EOF rather than
                // a normally-terminated response. Throwing IOException out of the handler
                // is the cleanest way to abort the framed HTTP response.
                throw IOException("simulated mid-stream disconnect")
            }
            opts.throttleBytesPerSecond?.let { rate ->
                val sleepMs = (bounded * MILLIS_PER_SECOND) / rate
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
        private const val DEFAULT_TICK_SIZE = 16 * 1024
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
            if (throttleBytesPerSecond == null) return DEFAULT_TICK_SIZE
            // Aim for roughly 10 ticks per second so the throttle is smooth, not bursty.
            val tickBytes = (throttleBytesPerSecond / 10L).toInt().coerceAtLeast(1)
            return tickBytes.coerceAtMost(DEFAULT_TICK_SIZE)
        }
    }
}

private data class ServedEntry(val content: ByteArray, val options: FileOptions) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class FileOptions(
    val acceptsRanges: Boolean = true,
    /** When non-null, HEAD's `Content-Length` is this value instead of the byte array's length. */
    val headContentLengthOverride: Long? = null,
    /** When `true`, HEAD does not return any `Content-Length` header. */
    val omitContentLength: Boolean = false,
    /** When non-null, every response uses this status (probe and chunk). */
    val statusOverride: Int? = null,
    /** When `true`, ranged GET ignores the Range header — returns 200 + full body. */
    val ignoreRangeHeader: Boolean = false,
    /** When non-null, the server's `Content-Range` header literally equals this string. */
    val contentRangeOverride: String? = null,
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
)
