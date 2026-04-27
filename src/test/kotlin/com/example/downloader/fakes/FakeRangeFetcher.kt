package com.example.downloader.fakes

import com.example.downloader.http.HttpRangeFetcher
import com.example.downloader.http.ProbeResult
import com.example.downloader.http.RangeSink
import java.net.URL

/**
 * Direct-control fake of [HttpRangeFetcher]. Tests use this to exercise orchestrator catch
 * branches that real HTTP failures don't cleanly hit (e.g. a raw [java.io.IOException]
 * escaping the fetcher layer when the production adapter is meant to wrap it as a typed
 * fetch exception).
 *
 * The real production fetcher [com.example.downloader.http.JdkHttpRangeFetcher] never throws
 * raw IOException — its catches map everything into TransientFetchException or
 * NonRetryableFetchException. This fake exists specifically to test the orchestrator's
 * defensive catch arms.
 */
internal class FakeRangeFetcher(
    private val onProbe: suspend (URL) -> ProbeResult = { error("onProbe not configured") },
    private val onFetchRange: suspend (URL, LongRange, RangeSink) -> Unit = { _, _, _ -> },
    private val onFetchAll: suspend (URL, RangeSink) -> Unit = { _, _ -> },
) : HttpRangeFetcher {
    override suspend fun probe(url: URL): ProbeResult = onProbe(url)
    override suspend fun fetchRange(
        url: URL,
        range: LongRange,
        entityValidator: String?,
        sink: RangeSink,
    ) {
        onFetchRange(url, range, sink)
    }
    override suspend fun fetchAll(url: URL, sink: RangeSink) {
        onFetchAll(url, sink)
    }
}
