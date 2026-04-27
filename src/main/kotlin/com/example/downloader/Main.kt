package com.example.downloader

import com.example.downloader.http.HttpRangeFetcher
import com.example.downloader.http.JdkHttpRangeFetcher
import com.example.downloader.http.RetryingHttpRangeFetcher
import com.example.downloader.retry.ExponentialBackoffRetry
import com.example.downloader.retry.NoRetry
import com.example.downloader.retry.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * CLI entry point.
 *
 * Usage:
 * ```
 * parallel-downloader [--chunk-size SIZE] [--parallelism N] [--retries N] URL DEST
 * ```
 *
 * Sizes accept suffixes (`KiB`, `MiB`, `GiB`, `KB`, `MB`, `GB`, `B`). Plain numbers are bytes.
 *
 * Exit codes:
 *   * 0   Success
 *   * 1   HTTP-level failure (HttpError, LengthMismatch, RangeNotSupported)
 *   * 2   Local I/O failure (IoFailure)
 *   * 64  Usage error (bad arguments)
 *   * 130 Cancelled (signal-style; not currently emitted because the suspend fun rethrows)
 */
fun main(args: Array<String>) {
    val cli = parseArgs(args) ?: exitProcess(EXIT_USAGE)

    val fetcher = buildFetcher(cli.retries)
    val downloader = FileDownloader(fetcher)
    val printer = CliProgressPrinter()
    val cfg = downloadConfig {
        chunkSize = cli.chunkSize
        parallelism = cli.parallelism
        progressListener = printer
    }

    val result = runBlocking(Dispatchers.IO) {
        downloader.download(cli.url, cli.destination, cfg)
    }
    exitProcess(exitCodeFor(result))
}

// ---------------------------------------------------------------------------------------------
// Argument parsing
// ---------------------------------------------------------------------------------------------

private data class CliArgs(
    val url: URL,
    val destination: Path,
    val chunkSize: Long,
    val parallelism: Int,
    val retries: Int,
)

@Suppress("ReturnCount") // multi-return guard pattern is the clearest expression of arg parsing.
private fun parseArgs(args: Array<String>): CliArgs? {
    var chunkSize: Long = DownloadConfig.DEFAULT_CHUNK_SIZE
    var parallelism: Int = DownloadConfig.DEFAULT_PARALLELISM
    var retries: Int = DEFAULT_RETRIES
    val positional = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--chunk-size" -> { chunkSize = parseSize(args.requireNext(i, arg) ?: return null); i += 2 }
            "--parallelism" -> {
                parallelism = args.requireNext(i, arg)?.toIntOrNull()
                    ?: return printUsage("--parallelism requires a positive integer")
                i += 2
            }
            "--retries" -> {
                retries = args.requireNext(i, arg)?.toIntOrNull()
                    ?: return printUsage("--retries requires a non-negative integer")
                i += 2
            }
            "-h", "--help" -> { printUsage(null); return null }
            else -> { positional += arg; i++ }
        }
    }
    if (positional.size != 2) {
        return printUsage("expected exactly two positional arguments: URL DEST (got ${positional.size})")
    }
    val url = runCatching { URL(positional[0]) }.getOrNull()
        ?: return printUsage("malformed URL: ${positional[0]}")
    return CliArgs(url, Path.of(positional[1]), chunkSize, parallelism, retries)
}

private fun Array<String>.requireNext(index: Int, arg: String): String? {
    if (index + 1 >= size) {
        printUsage("$arg requires a value")
        return null
    }
    return this[index + 1]
}

private val SIZE_REGEX = Regex("""^(\d+)\s*([KMGkmg]i?B?|[bB]?)$""")

private fun parseSize(s: String): Long {
    val match = SIZE_REGEX.matchEntire(s.trim())
        ?: throw IllegalArgumentException("invalid size '$s'; use e.g. 1024, 8MiB, 4MB")
    val n = match.groupValues[1].toLong()
    return when (match.groupValues[2].lowercase()) {
        "", "b" -> n
        "k", "kb", "kib" -> n * BYTES_PER_KIB
        "m", "mb", "mib" -> n * BYTES_PER_KIB * BYTES_PER_KIB
        "g", "gb", "gib" -> n * BYTES_PER_KIB * BYTES_PER_KIB * BYTES_PER_KIB
        else -> throw IllegalArgumentException("unknown size unit in '$s'")
    }
}

private fun printUsage(error: String?): CliArgs? {
    if (error != null) System.err.println("error: $error")
    System.err.println(
        """
        usage: parallel-downloader [OPTIONS] URL DEST

          --chunk-size SIZE   chunk size for parallel ranged GETs (default 8MiB)
          --parallelism N     in-flight chunks (default 8)
          --retries N         per-chunk retry attempts on transient failures (default 3)
          -h, --help          show this help

        SIZE accepts plain bytes or suffixed values: 1024, 8MiB, 4MB, 1GiB.
        """.trimIndent()
    )
    return null
}

// ---------------------------------------------------------------------------------------------
// Wiring & exit code mapping
// ---------------------------------------------------------------------------------------------

private fun buildFetcher(retries: Int): HttpRangeFetcher {
    val policy: RetryPolicy = if (retries <= 0) {
        NoRetry
    } else {
        // attempts = retries + 1 so `--retries 3` means "retry up to 3 times after the first try".
        ExponentialBackoffRetry(
            maxAttempts = retries + 1,
            initialDelay = INITIAL_RETRY_DELAY,
            maxDelay = MAX_RETRY_DELAY,
        )
    }
    return RetryingHttpRangeFetcher(JdkHttpRangeFetcher(), policy)
}

private fun exitCodeFor(result: DownloadResult): Int = when (result) {
    is DownloadResult.Success -> EXIT_OK
    is DownloadResult.HttpError -> EXIT_HTTP_ERROR
    is DownloadResult.LengthMismatch -> EXIT_HTTP_ERROR
    DownloadResult.RangeNotSupported -> EXIT_HTTP_ERROR
    is DownloadResult.IoFailure -> EXIT_IO_ERROR
    DownloadResult.Cancelled -> EXIT_CANCELLED
}

// ---------------------------------------------------------------------------------------------
// Progress printer - Observer implementation, throttled to ~10 Hz on stderr
// ---------------------------------------------------------------------------------------------

private class CliProgressPrinter : ProgressListener {
    private val startedAtNanos = AtomicLong(0L)
    private val lastPrintNanos = AtomicLong(0L)
    @Volatile private var totalBytes: Long = -1L

    override fun onStarted(total: Long) {
        startedAtNanos.set(System.nanoTime())
        lastPrintNanos.set(0L)
        totalBytes = total
    }

    override fun onProgress(downloaded: Long, total: Long) {
        val now = System.nanoTime()
        val last = lastPrintNanos.get()
        if (now - last < PROGRESS_INTERVAL_NANOS) return
        if (lastPrintNanos.compareAndSet(last, now)) {
            renderProgressLine(downloaded, total, partial = true)
        }
    }

    override fun onChunkComplete(chunkIndex: Int) = Unit

    override fun onFinished(result: DownloadResult) {
        // Always render a final line so the user sees end state.
        when (result) {
            is DownloadResult.Success -> renderProgressLine(result.bytes, totalBytes, partial = false)
            else -> {}
        }
        System.err.println()
        when (result) {
            is DownloadResult.Success ->
                System.err.println("✓ saved ${result.bytes} bytes to ${result.path} in ${result.elapsed}")
            is DownloadResult.HttpError ->
                System.err.println("✗ HTTP ${result.status} during ${result.phase}")
            is DownloadResult.LengthMismatch ->
                System.err.println("✗ length mismatch: server said ${result.expected} bytes, got ${result.actual}")
            is DownloadResult.IoFailure ->
                System.err.println("✗ I/O failure: ${result.cause.javaClass.simpleName}: ${result.cause.message}")
            DownloadResult.Cancelled ->
                System.err.println("✗ cancelled")
            DownloadResult.RangeNotSupported ->
                System.err.println("✗ server does not support range requests")
        }
    }

    private fun renderProgressLine(downloaded: Long, total: Long, partial: Boolean) {
        val elapsedNanos = System.nanoTime() - startedAtNanos.get()
        val elapsedSeconds = elapsedNanos.toDouble() / NANOS_PER_SECOND
        val mbDownloaded = downloaded.toDouble() / BYTES_PER_MIB
        val throughput = if (elapsedSeconds > 0) mbDownloaded / elapsedSeconds else 0.0

        val line = if (total > 0) {
            val mbTotal = total.toDouble() / BYTES_PER_MIB
            val pct = MAX_PCT * downloaded / total
            "%-60s %6.1f / %6.1f MiB  %5.1f%%  %6.2f MiB/s  %6.2fs".format(
                "downloading...", mbDownloaded, mbTotal, pct.toDouble(), throughput, elapsedSeconds,
            )
        } else {
            "%-60s %6.1f MiB  %6.2f MiB/s  %6.2fs".format(
                "downloading...", mbDownloaded, throughput, elapsedSeconds,
            )
        }
        // \r returns to start of line - overwrites the previous progress line in place. The
        // final newline is printed separately in onFinished so the last value stays on screen.
        if (partial) System.err.print("\r$line") else System.err.print("\r$line")
    }
}

// ---------------------------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------------------------

private const val DEFAULT_RETRIES: Int = 3
private const val EXIT_OK: Int = 0
private const val EXIT_HTTP_ERROR: Int = 1
private const val EXIT_IO_ERROR: Int = 2
private const val EXIT_USAGE: Int = 64
private const val EXIT_CANCELLED: Int = 130

private const val PROGRESS_INTERVAL_NANOS: Long = 100_000_000L  // 100 ms = ~10 Hz
private const val NANOS_PER_SECOND: Double = 1_000_000_000.0
private const val BYTES_PER_KIB: Long = 1024L
private const val BYTES_PER_MIB: Double = 1024.0 * 1024.0
private const val MAX_PCT: Long = 100L

private val INITIAL_RETRY_DELAY = 200.milliseconds
private val MAX_RETRY_DELAY = 5.seconds
