package com.example.downloader.http

import com.example.downloader.retry.RetryPolicy
import java.net.URL

/**
 * Pattern: **Decorator** - wraps any [HttpRangeFetcher] and applies a [RetryPolicy] to each
 * call. Single-responsibility: retry mechanics live here, not inside the fetcher implementation
 * or the downloader orchestrator. Composable: arbitrary stacking is supported (e.g. logging
 * decorator over retry over JDK adapter).
 */
class RetryingHttpRangeFetcher(
    private val delegate: HttpRangeFetcher,
    private val retryPolicy: RetryPolicy,
) : HttpRangeFetcher {

    override suspend fun probe(url: URL): ProbeResult = retryPolicy.execute { delegate.probe(url) }

    override suspend fun fetchRange(url: URL, range: LongRange, sink: RangeSink): Unit =
        retryPolicy.execute { delegate.fetchRange(url, range, sink) }

    override suspend fun fetchAll(url: URL, sink: RangeSink): Unit =
        retryPolicy.execute { delegate.fetchAll(url, sink) }
}
