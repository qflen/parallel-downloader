package com.example.downloader.http

import com.example.downloader.retry.NonRetryableFetchException
import com.example.downloader.retry.TransientFetchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

/**
 * Sends a HEAD request and interprets the response into a [ProbeResult]. Extracted from
 * [JdkHttpRangeFetcher] so the probe-response interpretation logic gets its own focused tests.
 *
 * Translates HTTP semantics into the retry exception hierarchy:
 *   * 2xx → [ProbeResult]
 *   * 5xx → [TransientFetchException] (retry helps)
 *   * other non-2xx → [NonRetryableFetchException] (retry doesn't help; surfaces as
 *     [com.example.downloader.DownloadResult.HttpError])
 */
internal class HttpProbe(private val httpClient: HttpClient) {

    suspend fun probe(url: URL): ProbeResult {
        val request = HttpRequest.newBuilder(url.toURI())
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(java.time.Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
            .build()
        val response = try {
            // runInterruptible: synchronous JDK send is blocking; this routes Thread.interrupt()
            // through the coroutine cancellation pipeline so the call site can cancel a hung HEAD.
            runInterruptible(Dispatchers.IO) {
                httpClient.send(request, BodyHandlers.discarding())
            }
        } catch (e: IOException) {
            // Connection failures, DNS lookup failures, TLS negotiation failures — all transient.
            throw TransientFetchException("HEAD ${url}: ${e.message}", e)
        }

        val status = response.statusCode()
        if (status !in SUCCESS_RANGE) throw mapNonSuccessStatus(status, "HEAD $url")

        val acceptsRanges = response.headers().firstValue("Accept-Ranges")
            .map { it.equals("bytes", ignoreCase = true) }
            .orElse(false)
        val contentLength: Long? = response.headers()
            .firstValueAsLong("Content-Length")
            .let { if (it.isPresent) it.asLong else null }

        return ProbeResult(
            status = status,
            contentLength = contentLength,
            acceptsRanges = acceptsRanges,
            finalUrl = response.uri().toURL(),
        )
    }

    private fun mapNonSuccessStatus(status: Int, context: String): Exception = when (status) {
        in SERVER_ERROR_RANGE -> TransientFetchException("$context returned $status")
        else -> NonRetryableFetchException("$context returned $status", statusCode = status)
    }

    companion object {
        private const val PROBE_TIMEOUT_SECONDS: Long = 15L
        private val SUCCESS_RANGE = 200..299
        private val SERVER_ERROR_RANGE = 500..599
    }
}
